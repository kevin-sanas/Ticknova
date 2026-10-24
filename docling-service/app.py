"""Internal file-only conversion API. No application database or model provider."""
import asyncio
import hashlib
import hmac
import json
import os
import re
import sys
import tempfile
from pathlib import Path
from uuid import UUID, uuid4

from fastapi import FastAPI, Request
from starlette.datastructures import UploadFile
from starlette.exceptions import HTTPException
from starlette.responses import JSONResponse
from limits import configured_limits

MAX_FILE_BYTES = 20 * 1024 * 1024
MAX_BODY_BYTES = MAX_FILE_BYTES + 64 * 1024
MAX_OUTPUT_BYTES = 8 * 1024 * 1024
CONVERSION_TIMEOUT_SECONDS = 90
TEMP_ROOT = None  # Uses TMPDIR, supplied as a bounded tmpfs in deployment.
MIMES = {
    ".pdf": {"application/pdf"},
    ".docx": {"application/vnd.openxmlformats-officedocument.wordprocessingml.document"},
    ".md": {"text/markdown", "text/plain", "text/x-markdown"},
    ".txt": {"text/plain"},
}
api = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)


class ParserError(Exception):
    def __init__(self, code, status=400):
        self.code, self.status = code, status


@api.exception_handler(ParserError)
async def parser_error(request, error):
    return JSONResponse({"errorCode": error.code}, status_code=error.status)


@api.exception_handler(HTTPException)
async def http_error(request, error):
    return JSONResponse({"errorCode": "INVALID_REQUEST"}, status_code=400)


@api.get("/health")
async def health():
    return {"status": "UP"}


async def execute_worker(source, output):
    process = await asyncio.create_subprocess_exec(
        sys.executable, str(Path(__file__).with_name("worker.py")), str(source), str(output),
        stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL,
    )
    try:
        await process.wait()
    finally:
        if process.returncode is None:
            try:
                process.kill()
            except ProcessLookupError:
                # The child may exit after the returncode check. Preserve cancellation
                # so the boundary still returns 504, and always reap the child.
                pass
            await process.wait()
    if process.returncode != 0 or not output.exists():
        raise ParserError("CONVERSION_FAILED", 422)
    if output.stat().st_size > MAX_OUTPUT_BYTES:
        raise ParserError("OUTPUT_TOO_LARGE", 422)
    result = json.loads(output.read_text(encoding="utf-8"))
    if "errorCode" in result:
        raise ParserError(result["errorCode"], 422 if result["errorCode"] in {"CONVERSION_FAILED", "NO_EXTRACTABLE_TEXT", "OUTPUT_TOO_LARGE"} else 400)
    return result


@api.post("/internal/v1/convert")
async def convert(request: Request):
    try:
        async with request.form(max_files=1, max_fields=3, max_part_size=1024) as form:
            if set(form) != {"documentVersionId", "sha256", "originalFileName", "sourceFile"} or len(form.multi_items()) != 4:
                raise ParserError("INVALID_REQUEST")
            version, digest, name, file = (form[key] for key in ("documentVersionId", "sha256", "originalFileName", "sourceFile"))
            if not all(isinstance(value, str) for value in (version, digest, name)) or not isinstance(file, UploadFile):
                raise ParserError("INVALID_REQUEST")
            try:
                UUID(version)
            except ValueError:
                raise ParserError("INVALID_REQUEST") from None
            if not re.fullmatch(r"[0-9a-f]{64}", digest):
                raise ParserError("INVALID_REQUEST")
            if not name or len(name) > 255 or any(c in name for c in '/\\:\x00') or any(ord(c) < 32 for c in name) or name in {".", ".."}:
                raise ParserError("INVALID_FILENAME")
            extension = Path(name).suffix.lower()
            if extension not in MIMES:
                raise ParserError("UNSUPPORTED_FORMAT")
            if (file.content_type or "").lower().split(";", 1)[0].strip() not in MIMES[extension]:
                raise ParserError("MIME_MISMATCH")
            with tempfile.TemporaryDirectory(prefix="ticknova-convert-", dir=TEMP_ROOT) as directory:
                source, output = Path(directory) / (str(uuid4()) + extension), Path(directory) / "result.json"
                hasher, size = hashlib.sha256(), 0
                with source.open("xb") as target:
                    while chunk := await file.read(64 * 1024):
                        size += len(chunk)
                        if size > request.scope["parser_max_upload_bytes"]:
                            raise ParserError("FILE_TOO_LARGE", 413)
                        hasher.update(chunk)
                        target.write(chunk)
                if not size:
                    raise ParserError("INVALID_DOCUMENT")
                if not hmac.compare_digest(hasher.hexdigest(), digest):
                    raise ParserError("HASH_MISMATCH")
                result = await execute_worker(source, output)
            return {"conversionId": str(uuid4()), "documentVersionId": version,
                    "sourceSha256": digest, "status": "SUCCEEDED", **result}
    except (ParserError, asyncio.CancelledError):
        raise
    except Exception:
        raise ParserError("INVALID_REQUEST") from None


class InternalBoundary:
    """Authenticate before multipart consumes bytes; bound chunked and declared bodies."""
    def __init__(self, application):
        self.application = application
        self.active = 0

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or scope["path"] != "/internal/v1/convert":
            return await self.application(scope, receive, send)
        headers = dict(scope["headers"])
        token = os.environ.get("DOCLING_INTERNAL_TOKEN", "")
        if not token:
            return await JSONResponse({"errorCode": "SERVICE_UNAVAILABLE"}, status_code=503)(scope, receive, send)
        if not hmac.compare_digest(headers.get(b"authorization", b""), ("Bearer " + token).encode("utf-8")):
            return await JSONResponse({"errorCode": "UNAUTHORIZED"}, status_code=401)(scope, receive, send)
        try:
            max_file_bytes, _ = configured_limits()
        except ValueError:
            return await JSONResponse({"errorCode": "SERVICE_UNAVAILABLE"}, status_code=503)(scope, receive, send)
        scope["parser_max_upload_bytes"] = max_file_bytes
        if self.active >= 2:
            return await JSONResponse({"errorCode": "PARSER_BUSY"}, status_code=503)(scope, receive, send)
        consumed = 0

        async def bounded_receive():
            nonlocal consumed
            message = await receive()
            consumed += len(message.get("body", b""))
            if consumed > max_file_bytes + 64 * 1024:
                raise ParserError("FILE_TOO_LARGE", 413)
            return message

        self.active += 1
        response_started = False

        async def tracked_send(message):
            nonlocal response_started
            await send(message)
            if message["type"] == "http.response.start":
                response_started = True

        try:
            try:
                async with asyncio.timeout(CONVERSION_TIMEOUT_SECONDS):
                    await self.application(scope, bounded_receive, tracked_send)
            except TimeoutError:
                if response_started:
                    # ASGI cannot replace an in-flight response with new headers.
                    # Let the transport close it rather than reporting a false success.
                    raise
                await JSONResponse({"errorCode": "CONVERSION_TIMEOUT"}, status_code=504)(scope, receive, send)
        finally:
            self.active -= 1


app = InternalBoundary(api)
