"""One disposable process per conversion, killed and reaped by the API on timeout."""
import io
import json
import logging
import os
import socket
import sys
import zipfile
from importlib.metadata import version
from pathlib import Path
from limits import configured_limits

MAX_OUTPUT_BYTES = 8 * 1024 * 1024
MAX_TEXT_CHARS = 2_000_000
MAX_ZIP_BYTES = 64 * 1024 * 1024


class InvalidDocument(Exception):
    pass


def validate_document(source):
    from pypdf import PdfReader
    from defusedxml.ElementTree import fromstring

    max_upload, max_pages = configured_limits()
    if source.stat().st_size > max_upload:
        raise InvalidDocument("FILE_TOO_LARGE")
    extension = source.suffix
    body = source.read_bytes()
    if extension == ".pdf":
        if not body.startswith(b"%PDF-"):
            raise InvalidDocument("INVALID_DOCUMENT")
        try:
            reader = PdfReader(io.BytesIO(body), strict=True)
            if reader.is_encrypted:
                raise InvalidDocument("INVALID_DOCUMENT")
            if len(reader.pages) > max_pages:
                raise InvalidDocument("PDF_PAGE_LIMIT")
            if not reader.pages:
                raise InvalidDocument("INVALID_DOCUMENT")
        except InvalidDocument:
            raise
        except Exception:
            raise InvalidDocument("INVALID_DOCUMENT") from None
    elif extension == ".docx":
        try:
            if not body.startswith(b"PK\x03\x04"):
                raise ValueError()
            with zipfile.ZipFile(io.BytesIO(body)) as archive:
                entries = archive.infolist()
                if len(entries) > 2048 or sum(entry.file_size for entry in entries) > MAX_ZIP_BYTES:
                    raise ValueError()
                if len({entry.filename for entry in entries}) != len(entries):
                    raise ValueError()
                for entry in entries:
                    if entry.flag_bits & 1 or entry.filename.startswith(("/", "\\")) or ".." in Path(entry.filename).parts:
                        raise ValueError()
                    if entry.filename.endswith((".xml", ".rels")):
                        fromstring(archive.read(entry))
                if "word/document.xml" not in archive.namelist() or "[Content_Types].xml" not in archive.namelist():
                    raise ValueError()
        except Exception:
            raise InvalidDocument("INVALID_DOCUMENT") from None
    else:
        try:
            text = body.decode("utf-8-sig", errors="strict")
        except UnicodeDecodeError:
            raise InvalidDocument("INVALID_UTF8") from None
        if any(ord(char) < 32 and char not in "\n\r\t" for char in text) or body.startswith((b"%PDF-", b"PK\x03\x04", b"MZ", b"\x89PNG")):
            raise InvalidDocument("INVALID_DOCUMENT")
    return body


def deny_network(*args, **kwargs):
    raise OSError("Network disabled in parser worker")


def convert(source):
    body = validate_document(source)
    if source.suffix == ".txt":
        # The SDK's .txt format maps to Markdown, which changes literal text.
        # Build a plain SDK document instead; preserve validated decoded content.
        from docling_core.types.doc import DocItemLabel, DoclingDocument
        document = DoclingDocument(name=source.stem)
        markdown = body.decode("utf-8-sig", errors="strict")
        document.add_text(label=DocItemLabel.TEXT, text=markdown)
    else:
        document = convert_with_sdk(source, body)
        markdown = document.export_to_markdown()
    return build_response(document, markdown, source.suffix)


def convert_with_sdk(source, body):
    from docling.backend.pypdfium2_backend import PyPdfiumDocumentBackend
    from docling.datamodel.backend_options import MarkdownBackendOptions, MsWordBackendOptions
    from docling.datamodel.base_models import ConversionStatus, DocumentStream, InputFormat
    from docling.datamodel.pipeline_options import ConvertPipelineOptions, NativePdfPipelineOptions
    from docling.document_converter import DocumentConverter, MarkdownFormatOption, NativePdfFormatOption, WordFormatOption
    from docling_core.types.doc import DocItemLabel, TableItem

    # Block Python outbound connect/DNS in addition to disabled SDK resource
    # fetching. The container network policy provides another isolation layer.
    socket.socket.connect = deny_network
    socket.socket.connect_ex = deny_network
    socket.getaddrinfo = deny_network
    converter = DocumentConverter(
        allowed_formats=[InputFormat.PDF, InputFormat.DOCX, InputFormat.MD],
        format_options={
            InputFormat.PDF: NativePdfFormatOption(
                backend=PyPdfiumDocumentBackend,
                pipeline_options=NativePdfPipelineOptions(generate_page_images=False, generate_picture_images=False,
                    parser_threads=1, enable_remote_services=False, document_timeout=85),
            ),
            InputFormat.DOCX: WordFormatOption(
                backend_options=MsWordBackendOptions(enable_remote_fetch=False, enable_local_fetch=False),
                pipeline_options=ConvertPipelineOptions(enable_remote_services=False),
            ),
            InputFormat.MD: MarkdownFormatOption(
                backend_options=MarkdownBackendOptions(fetch_images=False, enable_remote_fetch=False, enable_local_fetch=False, encoding="utf-8"),
                pipeline_options=ConvertPipelineOptions(enable_remote_services=False),
            ),
        },
    )
    max_upload, max_pages = configured_limits()
    result = converter.convert(DocumentStream(name=source.name, stream=io.BytesIO(body)), max_num_pages=max_pages, max_file_size=max_upload)
    if result.status != ConversionStatus.SUCCESS:
        raise InvalidDocument("CONVERSION_FAILED")
    return result.document


def json_string_bytes(value):
    """Exact UTF-8 JSON string cost, without allocating an escaped string/copy."""
    size = 2  # Quotes. ensure_ascii=False still escapes control characters.
    for char in value:
        code = ord(char)
        if char in '\\"\b\f\n\r\t':
            size += 2
        elif code < 32:
            size += 6
        elif code < 128:
            size += 1
        elif code < 2048:
            size += 2
        elif code < 65536:
            size += 3
        else:
            size += 4
    return size


def build_response(document, markdown, extension):
    from docling_core.types.doc import DocItemLabel, TableItem

    if len(markdown) > MAX_TEXT_CHARS:
        raise InvalidDocument("OUTPUT_TOO_LARGE")
    # Reserve more than the fixed JSON keys/warnings/parser metadata and the API's
    # conversion/version/hash envelope need. Every repeated heading is charged
    # before adding a section; UTF-8 and JSON escapes are included, not just chars.
    budget = 1024 + json_string_bytes(markdown)
    sections, heading_stack, total = [], [], len(markdown)
    if budget > MAX_OUTPUT_BYTES:
        raise InvalidDocument("OUTPUT_TOO_LARGE")
    for item, _ in document.iterate_items():
        if item.label in {DocItemLabel.TITLE, DocItemLabel.SECTION_HEADER}:
            level = 1 if item.label == DocItemLabel.TITLE else min(getattr(item, "level", 1), 32)
            # Exact pinned Markdown backend maps source H1 to TITLE, and source
            # H2..H6 to SECTION_HEADER level 1..5. DOCX levels are already native.
            if extension == ".md" and item.label == DocItemLabel.SECTION_HEADER:
                level += 1
            while heading_stack and heading_stack[-1][0] >= level:
                heading_stack.pop()
            heading_stack.append((level, item.text, json_string_bytes(item.text)))
        headings = [heading[1] for heading in heading_stack]
        text = item.export_to_markdown(document) if isinstance(item, TableItem) else getattr(item, "text", "")
        if not text.strip():
            continue
        total += len(text)
        if total > MAX_TEXT_CHARS:
            raise InvalidDocument("OUTPUT_TOO_LARGE")
        page_no = item.prov[0].page_no if item.prov else 1
        merge = sections and sections[-1]["pageNo"] == page_no and sections[-1]["headingPath"] == headings
        text_cost = json_string_bytes(text)
        if merge:
            # Replace the two quotes with an escaped newline; headings occur once.
            budget += text_cost
        else:
            path_cost = 2 + sum(heading[2] for heading in heading_stack) + max(len(headings) - 1, 0)
            budget += 64 + path_cost + text_cost
        if budget > MAX_OUTPUT_BYTES or (not merge and len(sections) >= 10000):
            raise InvalidDocument("OUTPUT_TOO_LARGE")
        if merge:
            sections[-1]["parts"].append(text)
        else:
            sections.append({"pageNo": page_no, "headingPath": headings, "parts": [text]})
    if not sections:
        raise InvalidDocument("NO_EXTRACTABLE_TEXT")
    # Join once only after all content plus metadata has passed the byte budget.
    pages = [{"pageNo": section["pageNo"], "headingPath": section["headingPath"],
              "text": "\n".join(section["parts"])} for section in sections]
    return {"markdown": markdown, "pages": pages,
            "warnings": ["PDF_NATIVE_TEXT_ONLY_NO_OCR_OR_LAYOUT"] if extension == ".pdf" else [],
            "parserVersion": f"docling/{version('docling-slim')};ticknova/0.1.0;native-pdf"}


if __name__ == "__main__":
    logging.disable(logging.CRITICAL)
    os.environ.update(HF_HUB_OFFLINE="1", TRANSFORMERS_OFFLINE="1", DO_NOT_TRACK="1")
    source, output = map(Path, sys.argv[1:])
    try:
        payload = convert(source)
    except InvalidDocument as error:
        payload = {"errorCode": str(error)}
    except Exception:
        payload = {"errorCode": "CONVERSION_FAILED"}
    # A second byte guard protects persistence without materializing the full
    # serialized JSON or a second full UTF-8 copy. Each encoded write is <=32 KiB.
    try:
        written = 0
        with output.open("wb") as target:
            for fragment in json.JSONEncoder(ensure_ascii=False, separators=(",", ":")).iterencode(payload):
                for offset in range(0, len(fragment), 8192):
                    encoded = fragment[offset:offset + 8192].encode("utf-8")
                    written += len(encoded)
                    if written > MAX_OUTPUT_BYTES - 1024:
                        raise InvalidDocument("OUTPUT_TOO_LARGE")
                    target.write(encoded)
    except InvalidDocument:
        output.write_bytes(b'{"errorCode":"OUTPUT_TOO_LARGE"}')
