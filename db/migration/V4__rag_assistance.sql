ALTER TABLE document_version
 ADD COLUMN embedding_state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(embedding_state IN ('PENDING','PROCESSING','READY','FAILED')),
 ADD COLUMN embedding_profile varchar(200), ADD COLUMN embedding_lease uuid, ADD COLUMN embedding_locked_until timestamptz,
 ADD COLUMN embedding_attempts integer NOT NULL DEFAULT 0, ADD COLUMN embedding_available_at timestamptz NOT NULL DEFAULT now(),
 ADD COLUMN embedding_error_code varchar(80);
ALTER TABLE knowledge_chunk ADD COLUMN embedding_profile varchar(200),ADD COLUMN lexical_terms text NOT NULL DEFAULT '';
ALTER TABLE knowledge_chunk ADD COLUMN lexical_tsv tsvector GENERATED ALWAYS AS (to_tsvector('simple',lexical_terms)) STORED;
CREATE INDEX ix_chunk_lexical ON knowledge_chunk USING gin(lexical_tsv);
CREATE INDEX ix_version_embedding_due ON document_version(embedding_available_at,id) WHERE ingest_state='READY';
CREATE TABLE ai_request (
 job_id uuid PRIMARY KEY REFERENCES ai_job(id),kind varchar(20) NOT NULL CHECK(kind IN ('ANSWER','REPLY','SUMMARY','CLASSIFICATION')),
 question varchar(4000),ticket_id uuid REFERENCES ticket(id),space_ids uuid[] NOT NULL,
 CHECK((kind='ANSWER' AND ticket_id IS NULL AND question IS NOT NULL) OR (kind<>'ANSWER' AND ticket_id IS NOT NULL))
);
CREATE TABLE ai_suggestion (
 id uuid PRIMARY KEY,job_id uuid NOT NULL UNIQUE REFERENCES ai_job(id),result_json jsonb NOT NULL,
 feedback varchar(16) CHECK(feedback IN ('ACCEPTED','REJECTED')),feedback_reason varchar(500),
 withheld boolean NOT NULL DEFAULT false,created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE ai_citation (
 suggestion_id uuid NOT NULL REFERENCES ai_suggestion(id),chunk_id uuid NOT NULL REFERENCES knowledge_chunk(id),
 PRIMARY KEY(suggestion_id,chunk_id)
);
CREATE TABLE ai_context_source (
 suggestion_id uuid NOT NULL REFERENCES ai_suggestion(id),chunk_id uuid NOT NULL REFERENCES knowledge_chunk(id),
 PRIMARY KEY(suggestion_id,chunk_id)
);
