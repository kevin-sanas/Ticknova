ARG PYTHON_IMAGE=python:3.12.12-slim-bookworm@sha256:593bd06efe90efa80dc4eee3948be7c0fde4134606dd40d8dd8dbcade98e669c
FROM ghcr.io/astral-sh/uv:0.13.1@sha256:4a1f3666816dafadd20ceb71eac9a7d56d7ef30279f7d64048519ab901ada336 AS uv
FROM ${PYTHON_IMAGE}
COPY --from=uv /uv /usr/local/bin/uv
ENV UV_NO_CACHE=1 UV_PYTHON_DOWNLOADS=never UV_LINK_MODE=copy \
    PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 \
    HF_HUB_OFFLINE=1 TRANSFORMERS_OFFLINE=1 DO_NOT_TRACK=1 \
    OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1 HOME=/tmp TMPDIR=/tmp
WORKDIR /app
COPY docling-service/pyproject.toml docling-service/uv.lock docling-service/.python-version ./
RUN uv sync --frozen --no-dev --no-install-project && \
    groupadd --gid 10001 ticknova && useradd --uid 10001 --gid ticknova --no-create-home ticknova
COPY docling-service/app.py docling-service/worker.py docling-service/limits.py docling-service/THIRD-PARTY-NOTICES.txt ./
USER 10001:10001
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s CMD ["/app/.venv/bin/python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=2)"]
CMD ["/app/.venv/bin/python", "-m", "uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log", "--limit-concurrency", "8", "--timeout-keep-alive", "5"]
