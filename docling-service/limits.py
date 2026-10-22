"""Operator limits may tighten the contract, never enlarge its hard safety bounds."""
import os


def configured_limits():
    upload = int(os.environ.get("MAX_UPLOAD_BYTES", str(20 * 1024 * 1024)))
    pages = int(os.environ.get("MAX_PDF_PAGES", "100"))
    if upload <= 0 or pages <= 0:
        raise ValueError("Invalid parser limits")
    return min(upload, 20 * 1024 * 1024), min(pages, 100)
