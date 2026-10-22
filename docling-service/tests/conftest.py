"""Keep all parser-test temporary files inside the project workspace."""
import tempfile
from pathlib import Path

import pytest


@pytest.fixture(scope="session", autouse=True)
def workspace_temporary_directory():
    root = Path(__file__).resolve().parents[2] / ".cache"
    root.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="docling-tests-", dir=root) as directory:
        previous = tempfile.tempdir
        tempfile.tempdir = directory
        try:
            yield
        finally:
            tempfile.tempdir = previous
