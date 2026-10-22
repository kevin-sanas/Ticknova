# Ticknova G3 Knowledge Ingestion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Deliver the G3 knowledge ingestion gate: secure uploads, Docling extraction, versioned chunks, space ACL, publish/withdraw, retries, audit, and a usable knowledge management page.

**Completion (2026-10-20):** Tasks 1–4 complete. Backend 65, parser 45 and frontend 44 tests passed; lint/typecheck/build, real parser containers and browser ticket9/knowledge8 checks passed. Evidence: `docs/evidence/g3-verification.json`, `g3-review.json`, parser JUnit and browser JSON/screenshots. Two nonblocking timeout follow-ups are documented in docs16. G4/G5 and performance/backup/remote CI remain unverified. Source remains local and uncommitted; original staged baseline preserved.

**Architecture:** Spring Boot owns authorization, file storage, metadata, chunking, publication, and a PostgreSQL Outbox worker. A private Python/FastAPI Docling service receives only bounded file streams from the Java API and returns structured extraction data. Knowledge remains unavailable to retrieval until G4 adds embeddings and RAG.

**Tech Stack:** Java 21, Spring Boot 4.1.1, JdbcClient, PostgreSQL 17 + pgvector, Flyway; Python 3.12, FastAPI, pinned Docling and uv lock; Vue 3.5, TypeScript, Vite, Element Plus, Pinia.

**Spec:** `docs/02-角色用例与需求.md`, `docs/03-领域模型与状态机.md`, `docs/04-边界与模块设计.md`, `docs/06-数据模型ERD与SQL.md`, `docs/07-API契约.md`, `docs/08-异步消息与幂等.md`, `docs/09-安全隐私与权限.md`, `docs/10-RAG设计与评测.md`, `docs/11-本地部署运维与CI-CD.md`, `docs/12-测试矩阵与阶段门.md`, `docs/13-迭代任务与发布交付.md`.

## Global Constraints

- Accept PDF, DOCX, Markdown, and TXT only; maximum 20 MiB and 100 PDF pages.
- Original files use random server-generated storage keys under a private upload root; client filenames are display metadata only.
- Docling is internal-only, bearer protected, accepts streamed file content only (no URL or path input), and has a 90 second request timeout.
- Preserve an existing published version while a new version parses; publish only READY versions with an atomic pointer change; withdrawal clears the pointer immediately.
- Apply knowledge ACL in SQL before exposing document metadata or content. Roles do not inherit implicitly.
- Outbox and job creation share the upload transaction; consumers are lease-based, retry up to five attempts, and are idempotent.
- Do not introduce an embedding provider, RAG answer endpoint, or model-generated content in G3. G4 owns embeddings and retrieval.
- Do not log document contents, bearer tokens, secrets, or parser stack traces to API responses.
- Keep the current staged G0–G2 baseline intact; do not commit or push this phase.

## Review Focus

- Unauthorized users cannot enumerate spaces/documents or retrieve parser results; test direct API access without ACL.
- Spoofed MIME, unsupported extension, traversal filename, and oversized files fail at the upload boundary. PDF page-limit violations fail in the internal service preflight before the Docling SDK performs conversion.
- Repeated or concurrent same-file uploads within one document create one version/job; retries and duplicate Outbox delivery do not duplicate chunks.
- Parser timeout or crash leaves the previous publication usable and the failed version retryable with a stable error code.
- Publishing, disabling, archiving, and membership changes are audited and immediately reflected in visibility.

---

### Task 1: Schema and knowledge authorization/API foundation

**Files:** Create `db/migration/V3__knowledge_ingestion.sql`; create `backend/src/main/java/com/ticknova/knowledge/KnowledgeController.java`, `KnowledgeDtos.java`, `KnowledgeRepository.java`, `KnowledgeService.java`, `KnowledgePolicy.java`; modify security registration only as needed; create focused unit/integration tests under `backend/src/test/java/com/ticknova/knowledge/`.

**Interfaces:** Implement `GET/POST /knowledge/spaces`, `GET /knowledge/spaces/{spaceId}/documents`, `PUT /knowledge/spaces/{spaceId}/members/{userId}`, `POST /knowledge/spaces/{spaceId}/documents`, and document detail/publish/disable/retry/archive routes from `docs/07-API契约.md`. Add `POST /knowledge/documents/{id}/versions` for later versions of an existing document. Upload returns `202 {documentId,versionId,jobId}`. Detail returns metadata and version/job states only, never extracted text by default. Space creators and ADMIN may manage members; ADMIN does not bypass `can_read` for document access.

- [x] Write failing authorization and migration integration tests for space membership, explicit KB_EDITOR role, document object scope, and cross-space denial.
- [x] Run the focused tests against the isolated `ticknova-test` PostgreSQL container and observe the expected failures.
- [x] Add V3 tables/constraints/indexes for ACL, documents, versions, chunks, jobs, and outbox (include only objects not already present); set RLS-independent application SQL scope. Add `GET /knowledge/spaces` and `GET /knowledge/spaces/{id}/documents` to the API contract, plus version-upload route, ACL rules, and upload error codes.
- [x] Implement the documented API and transactional audit behavior with role plus object ACL checks.
- [x] Run focused tests; verify migration from an empty test database and from V1/V2.

### Task 2: Safe Docling parser service

**Files:** Create `docling-service/pyproject.toml`, `uv.lock`, service source, tests, `THIRD-PARTY-NOTICES.txt`, and `deploy/docling.Dockerfile`; modify `.dockerignore`.

**Interfaces:** `POST /internal/v1/convert` accepts multipart `documentVersionId`, `sha256`, `originalFileName`, `sourceFile`; requires `Authorization: Bearer <DOCLING_INTERNAL_TOKEN>`; returns conversion ID, matching version ID/hash, extracted Markdown/structured page-heading content, and parser version. Errors use stable non-sensitive codes.

- [x] Write failing FastAPI contract tests for missing/wrong token, hash mismatch, unsupported input, size/page limit, and successful PDF/DOCX/Markdown/TXT conversion.
- [x] Run service tests and observe expected failures.
- [x] Implement bounded stream handling and Docling conversion with network access disabled; never accept paths or URLs.
- [x] Pin Python/runtime/dependencies, produce a lock and license report, and run as non-root with resource/read-only restrictions where supported.
- [x] Run parser tests and container build; verify the service has no host-published port.

### Task 3: Upload storage, Outbox worker, chunking, and lifecycle

**Files:** Create focused storage, Docling client, worker, and chunker classes under `backend/src/main/java/com/ticknova/knowledge/`; add backend config; extend tests in `backend/src/test/java/com/ticknova/knowledge/`; modify `deploy/compose.yaml` and compose-test service wiring.

**Interfaces:** Worker claims due events with `FOR UPDATE SKIP LOCKED` and a lease, calls Docling outside a database transaction, then persists sanitized versioned chunks and completion state idempotently. Upload writes file atomically and, in one DB transaction, persists version/job/outbox/audit. API idempotency uses document ID plus SHA-256.

- [x] Write failing tests for safe random storage keys, atomic upload/DB rollback cleanup, duplicate upload, extraction/chunking state changes, duplicate event, lease expiry, retry cap, and preservation of prior publication.
- [x] Run focused tests and observe expected failures.
- [x] Implement extension and content-signature validation, UTF-8 validation for text formats, pypdf page-count rejection above 100 pages, 20 MiB limit, SHA-256, random filenames, path confinement, and a private volume.
- [x] Implement bounded paragraph-aware 500–700-token approximate chunks with 80-token overlap, page/heading provenance, and a recorded strategy version. Leave embedding NULL for G4.
- [x] Implement outbox lease/retry (maximum five attempts), stable error codes, manual retry, publish/withdraw/archive transactions, and audits.
- [x] Run focused tests and all backend tests; exercise the real Docling service in the isolated compose test stack.

### Task 4: Knowledge management UI and phase evidence

**Files:** Create `frontend/src/views/KnowledgeView.vue`; modify router, app navigation, API types/client, tests, and existing README/stage docs.

**Interfaces:** Role-authorized UI lists readable spaces, uploads supported files to editable spaces, shows upload/processing/failure states, retries failed versions, publishes READY versions, disables published documents, and archives documents. UI never displays extracted content as HTML.

- [x] Write failing view/API tests for upload, processing, failure/retry, READY publish, disable/archive, empty state, and forbidden roles.
- [x] Implement the Chinese responsive page and phase routes.
- [x] Run frontend tests, lint, typecheck, and production build; verify upload and publish in a browser against the local stack.
- [x] Update docs 07/12/13/16 with added routes, actual commands/results, limitations, and any NOT RUN items; leave G4/G5 requirements unchanged.

---

## Ruling

G3 marks a document version READY after successful extraction and chunk persistence, with embedding NULL. The G3 stage gate requires parsing, versioning, ACL publication, and retry; no embedding provider or RAG endpoint exists in the G3 baseline. G4 must populate/version embeddings before retrieval and must not treat G3 publication as searchable content. Cost if wrong: G4 needs a migration or state transition adjustment before enabling retrieval.

