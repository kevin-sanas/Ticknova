"""Reproduce notices from the locked runtime environment; run after uv sync --no-dev."""
from importlib.metadata import distributions
from pathlib import Path

lines = [
    "Ticknova Docling parser dependency notices",
    "Generated from the Linux runtime uv.lock installation on Python 3.12.12 (2026-10-24).",
    "Docling SDK: docling-slim 2.142.1, MIT; official slim distribution of docling.",
    "PDF: official NativePdfPipeline with PyPdfiumDocumentBackend; no OCR/layout models or model weights.",
    "SDK source: https://github.com/docling-project/docling/tree/v2.142.1",
    "PDFium bundled third-party notices are included below from the actual platform wheel.",
    "Python runtime: PSF-2.0, https://docs.python.org/3.12/license.html",
    "uv build tool 0.13.1: Apache-2.0 OR MIT, https://github.com/astral-sh/uv/tree/0.13.1",
    "Dependency versions, hashes and platform markers are frozen in uv.lock.",
    "This service does not download or distribute any model weights.",
    "",
]
for dist in sorted(distributions(), key=lambda value: value.metadata["Name"].lower()):
    metadata = dist.metadata
    license_name = metadata.get("License-Expression") or metadata.get("License") or "; ".join(
        item for item in metadata.get_all("Classifier", []) if item.startswith("License ::")
    ) or "See bundled license below"
    lines.extend(["=" * 72, f"{metadata['Name']} {dist.version}", f"Declared license: {license_name}",
                  f"Package evidence: https://pypi.org/project/{metadata['Name']}/{dist.version}/"])
    for file in dist.files or []:
        name = str(file).lower()
        if any(marker in name for marker in ("license", "copying", "notice", "copyright")) and file.suffix.lower() not in (".py", ".pyc", ".json"):
            path = dist.locate_file(file)
            if path.is_file() and path.stat().st_size < 600_000:
                lines.extend([f"--- Bundled {file} ---", path.read_text(encoding="utf-8", errors="replace")])
print("\n".join(lines))
