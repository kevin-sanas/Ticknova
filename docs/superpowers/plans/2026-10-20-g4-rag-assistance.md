# Ticknova G4 RAG and AI Assistance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox syntax for tracking.

**Goal:** Deliver authorized knowledge answers with citations/refusal and human-reviewed ticket summary/classification/reply suggestions, with repeatable mock evaluation.

**Architecture:** Java/Spring AI owns model adapters, a separate leased embedding indexer and durable AI request worker. PostgreSQL performs ACL/publication filtering before vector/full-text candidates, with profile-isolated 1024-dimensional embeddings. Vue polls authenticated jobs and inserts accepted suggestions into existing drafts only.

**Tech Stack:** Existing Java21/Boot4.1.1/JdbcClient/Flyway/PostgreSQL17+pgvector; Spring AI2.0.1; existing Vue/TypeScript/Element Plus.

**Spec:** Existing docs02/03/04/05/07/08/09/10/12/13; docs10's G4 implementation design states settled integration choices. User has approved existing stage roadmap and requested continued implementation after G3 acceptance. No new user decisions are required for default mock operation.

## Global Constraints

- Preserve all staged G0–G2 and unstaged G3/user work, no index writes/commit/push or host configuration changes. No HEAD exists; use the current checkout and focused source review.
- Only Ticknova local/demo/test containers/databases are authorized. No real provider calls unless user later configures/authorizes them; fake local HTTP servers are allowed contract tests.
- Fixed1024 dimensions, versioned provider profile, no mixing embeddings. G3 READY remains extraction complete; embedding readiness is independent.
- SQL ACL before recall, current published version only. ADMIN has no content bypass. Entire saved response is withheld if any original space/ticket/citation becomes inaccessible.
- No internal notes in prompts/results. No model tools, automatic replies, state changes or field writes. Accepted UI suggestions require normal manual submit.
- Never log keys/tokens/prompt/business content. Bound input, provider time, output and worker concurrency. Stable non-sensitive errors.
- Create only this additional plan Markdown; update existing docs. No new design/handoff Markdown beyond the existing project total.

## Review Focus

1. Revoke ACL/withdraw/supersede a source during model generation and after completion: no cached or persisted result leaks on a subsequent read/feedback.
2. Duplicate submits/worker restart/expired lease: one job/suggestion, stale claims cannot overwrite newer work, retry limit and audit rollback hold.
3. Chinese queries, unrelated questions, malicious instructions and forged citation IDs: useful authorized retrieval or explicit refusal, no fabricated sources.
4. Provider/profile switches, invalid dimensions/NaN/slow transport: old vectors excluded, finite bounded failure and core tickets remain usable.
5. Session/doc changes and nonempty reply drafts: no stale poll results or accidental public sends/overwrites; 403/404 clears private AI content.

## Task1: Spring AI provider port and bounded mock/real adapters

Files: create `backend/src/main/java/com/ticknova/ai/model/{AiModel,ModelConfiguration,MockAiModel,SpringAiModel,AiModelFailure,AiText}.java`; modify backend/pom.xml only; tests under `backend/src/test/java/com/ticknova/ai/model/`.

Interfaces owned by Task1:
`AiModel` has `String profile()`, `String mode()`, `List<float[]> embed(List<String> texts)`, `Output generate(Input input)`.
Nested records: `Source(UUID chunkId,String content)`; `Input(String kind,String question,String ticketTitle,String ticketDescription,List<String> publicReplies,List<Source> sources,List<ServiceOption> services)`; `ServiceOption(UUID id,String name)`; `Output(String answer,String summary,UUID serviceId,String priority,List<String> tags,List<UUID> citationChunkIds)`.
`AiModelFailure` exposes `String code()` and `boolean retryable()`.
`AiText.terms(String)` returns deterministic bounded List<String> (Chinese overlapping bigrams + lowercase Latin words); used by embeddings, lexical query and mock evidence tests. `AiText.vectorLiteral(float[])` validates1024 finite nonzero values and creates SQL vector text. Mock profile `mock:hash-v1:1024`; real includes configured model/baseURL digest, never API key. Model-specific embedding input upper bounds explicit.

- [x] Write failing tests for repeatable1024 finite embeddings, Chinese query similarity, empty/unrelated evidence refusal, literal injection handling and bounded output.
- [x] Observe focused RED then pin Spring AI BOM2.0.1, chat-client and OpenAI model dependencies and implement port, mock ChatModel/ChatClient generation, OpenAI-compatible adapter using official APIs. No unused paid-provider auto beans in mock mode.
- [x] Properties `ticknova.ai.provider` default mock, `base-url`, `api-key`, `chat-model`, `embedding-model`, `worker-enabled`; root owns application.yml/env/Compose. Real mode requires explicit model names/key/URL, no fallback; constructor does not make network calls. Max provider transport30s, retries0 (durable worker owns retries), input batch32/chars64k, response16k chars, embedding1024 validated on every response.
- [x] Test real adapter against local fake HTTP including correct paths/auth/schema, dimensions, errors and delayed response; do not call external models. Run focused GREEN and self-review. Report actual commands and warnings, no commit.

## Task2: V4 embedding index, ACL retrieval and durable AI API/jobs

Files: create V4 migration; `backend/src/main/java/com/ticknova/ai/` DTO/controller/service/repository/indexer/retriever/worker/result validator; focused integration tests. Modify KnowledgeController only to relocate existing `/jobs/{id}` dispatch into a new unified JobController. Do not modify model package/POM/frontend/deployment.

Consume Task1 exact AiModel types. Produce:
- `GET /ai/config` => `{mode,profile,externalProvider:boolean}`.
- `POST /ai/answers {question:string,spaceIds:UUID[]}` =>202 `{jobId:UUID}`.
- `POST /tickets/{id}/ai-drafts {kind:"REPLY"|"SUMMARY"|"CLASSIFICATION",spaceIds:UUID[]}` =>202 `{jobId}`. A visible ticket with canCommentPublic is required; only title/description/public replies are model input. Service options come from active catalog.
- Both POSTs require Idempotency-Key max128 and fresh replay authorization, max10 unique selected spaces; [] means all readable. question1–4000; no blank.
- Resolve [] once at request creation to explicit readable space IDs and persist that effective set (all-readable may exceed10;10 limits explicit client selection). Revalidate the entire original set on replay/pre-model/completion/read/feedback, including refusal/ticket-only results. Later-added memberships do not expand an existing job. Zero-readable spaces is valid and yields refusal for knowledge answers/replies.
- `GET /jobs/{id}` AI shape `{id,status,errorCode,attemptCount,result:AiResult|null}`; G3 DOC_INGEST response unchanged through KnowledgeService. AI job/suggestion visible only original creator, active/must-change=false, original scopes/ticket and every citation rechecked on read and feedback.
- `AiResult` `{suggestionId,kind,mode,profile,outcome:"ANSWERED"|"INSUFFICIENT_EVIDENCE",answer,summary,serviceId,priority,tags,citations,requiresHumanReview:true,feedback:null|"ACCEPTED"|"REJECTED"}`. Citation `{documentId,versionId,version,title,page:number|null,chunkId,snippet}`. No raw model response, prompt or hidden candidates returned.
- `POST /ai/suggestions/{id}/feedback {feedback,reason?}` =>204; reason<=500; same feedback replay idempotent, changed choice409. No ticket writes.
- `AiIndexWorker.runOnce()` / `AiWorker.runOnce()` public deterministic entrypoints for tests; schedules disabled through `ticknova.ai.worker-enabled=false` in tests.

- [x] RED tests for profile-safe retrieval/indexing, SQL publication/ACL, missing scopes, owner-only jobs, requester/internal-note exclusion and idempotent repeated create.
- [x] V4 adds separate index status/profile/lease/attempts/backoff fields on document_version, chunk embedding_profile and lexical terms index, suggestions+citations and necessary request linkage. Existing ai_job/outbox are reused with AI_REQUEST event; no edits to V1–V3. Migration existing G3 and empty DB.
- [x] Indexer claims READY chunked versions, computes batch<=32 outside txn, fenced commit; mark index READY only when all chunks match profile. Five finite failures/backoff; automatic profile reindex old/new rows; external error never breaks old publication or ingest. Source64k budget, deadline<lease120s.
- [x] Retriever SQL filters active actor/ACL/space/current PUBLISHED pointer/READY embedding state/profile before top8 cosine and top8 FTS. RRF top6, deterministic tie order; threshold/lexical-evidence guard documented. All SQL parameters bound. Support Chinese via AiText terms and generated OR tsquery. No global recall then hide.
- [x] Durable request creation/job/outbox/audit one txn. Worker SKIP LOCKED/120sec lease/fencing/max5; no HTTP in DB txn. Fresh actor/scopes before input and completion. Bound context and output. Validate citation IDs are nonempty subset of recalled sources for answers/replies, exact service ID/priority/tags bounds for classifications. No evidence/unsupported output produces explicit refusal without model text.
- [x] RR snapshot job read and feedback revalidate original space/ticket and all current citations; withhold entire answer if stale, audit once without body. Add regressions for withdrawal/ACL revoke during generation/after completion, fake citations, injection, lease duplicate/concurrency, DB/audit rollback. Test with isolated PostgreSQL only; coordinate Maven execution with root.
- [x] GREEN focused suite and self-review. Report commands, counts, known limitations and needed root integration changes; no commit.

## Task3: Chinese assistant and ticket suggestion UI

Files: new AssistantView.vue/AiSuggestionPanel.vue/AiResultView.vue or focused composable; modify frontend types/router/App navigation and TicketDetailView; frontend tests. Own only frontend.

Consume Task2 exact routes/DTOs above. `/assistant` available to every signed-in account, selector lists only canRead spaces from existing `/knowledge/spaces`. Submit a new question with stable Idempotency-Key retained on unknown failure; poll `/jobs/{id}`, bounded/backoff/cancel on unmount/session/draft change; preserve question on network/conflict. No answer body in localStorage/sessionStorage.

- [x] RED view tests: valid cited result, empty/no-evidence/refusal, busy/failed/retry, 403/404 clear stale content, delayed prior-session/poll ignored, network key reuse.
- [x] Implement assistant question/space selection, result/plain-text citations/provenance, explicit mock or third-party notice, feedback; loading/empty/failure/expired session/narrow cards. Citation preview uses server-validated snippet, not arbitrary URLs/HTML.
- [x] Ticket suggestion panel generates REPLY/SUMMARY/CLASSIFICATION for canCommentPublic, excludes current private notes, handles current ticket/session scopes. Accept/reject calls feedback first. Reply/summary fills public draft only; classification prefills existing priority/tags when canEdit; service only shown. Confirm before replacing nonempty inputs. No automatic send/PATCH/status/assignment; preserve409 inputs and require existing form actions.
- [x] GREEN focused tests then full44+ suite/lint/typecheck/build once, report counts and visual checks. Do not run root browser integration or mutate backend/env; no commit.

## Task4: Root integration, fixed evaluation, supply chain and stage evidence

Files: root owns application.yml, .env.example, Compose, scripts, existing docs/evidence and `eval/` fixtures. Coordinate Task1/2 API while preserving ownership.

- [x] Add no-key mock configuration defaults and runtime env, disable AI schedules in isolated tests. Reuse test container/network/DB; no other project/config writes. Build/index old G3 demo files, leave originals intact.
- [x] Add30+ fixed query fixtures and evaluation runner/tests with source labels, injection/unanswerable/revocation cases. Separate automated mock metrics from unrun real-model/human quality/P95; record dataset/profile/strategy/provider and actual results.
- [x] Run fresh PostgreSQL V1–V4 migration, full backend suite including real parser regression, frontend suite and actual browser ticket/knowledge/assistant/suggestion flow. Confirm non-root parser unaffected. Restart final demo images and verify readiness.
- [x] Generate actual Java updated SBOM/notices and audit including new dependencies, fixed mirrors/versions only. Inspect credentials without emitting values; never rewrite displayed demo passwords. Update README/docs05/07/09/10/11/12/13/15/16 with actual commands/results/limitations.
- [x] Independent scoped reviews per task/fix and final broad review. Archive review/evidence and every Ruling before deleting only this plan's scratch logs/reports. Stop test containers, preserve demo services/data/cache. No remote publish, commit or push.

## Rulings

Existing written docs and user instruction to continue approve the stage scope; this plan settles implementation details without another permission round. Wrong integration choices require local rework, not external side effects. Use existing checkout because read-only .git/no HEAD prevents isolated commits. All formal reviews must inspect working source rather than claim an immutable commit diff. Record any further choices in the ledger and docs16.


## User-requested stop checkpoint

2026-10-20 21:40 Asia/Shanghai stop; do not resume automatically. Tasks1–4 implementation and local acceptance completed with a nonblocking final-review Minor deferred: ticket REPLY/SUMMARY output may be16k while public comments allow10k. Next user-resumed session should add a destination-boundary regression and align validation before moving to G5. Source reviews, all rulings and sanitized command outcomes archived in docs/evidence/g4-review.json and g4-test-runs.json; actual gate results and limitations in docs12/16. Dedicated test containers stopped; demo services/data and dependency caches preserved. No commit or remote publication.

## Approved G4 closeout — 2026-10-21

- [x] RED boundary tests then shared backend10000 ticket suggestion/comment limit, kind-specific refusal without truncation; ANSWER16000 unchanged, prompt aligned.
- [x] Legacy UI guard before ACCEPTED/confirmation, preserve original text/draft and allow rejection; valid10000 accepted intact then manual send only.
- [x] Backend focused15/full153; frontend focused32/full76, lint/typecheck/build; fixed32-case mock evaluation PASS.
- [x] Original final reviewer resumed after user pause: scoped PASS; new API/web local images deployed; actualAI browser7 PASS and screenshots inspected.
- [x] Dated evidence preserves prior outputs; existing docs updated, original133 staged files retained, temporary task artifacts archived/cleaned, test containers stopped and demo retained.

No new API/DTO fields or migration, no external model calls. G5 remains next-round work. Detailed commands, rulings and evidence: docs12/16 and docs/evidence/g4-closeout-2026-10-21-*. Completed after explicit user resume; historical stop checkpoints above are superseded for this task.
