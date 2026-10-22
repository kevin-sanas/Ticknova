CREATE TABLE knowledge_space (
 id uuid PRIMARY KEY,name varchar(160) NOT NULL CHECK(length(trim(name))>0),active boolean NOT NULL DEFAULT true,
 created_by uuid NOT NULL REFERENCES app_user(id),created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE knowledge_space_member (
 space_id uuid NOT NULL REFERENCES knowledge_space(id),user_id uuid NOT NULL REFERENCES app_user(id),
 can_read boolean NOT NULL DEFAULT true,can_edit boolean NOT NULL DEFAULT false,
 PRIMARY KEY(space_id,user_id),CHECK(NOT can_edit OR can_read)
);
CREATE INDEX ix_space_member_user ON knowledge_space_member(user_id,space_id);
CREATE TABLE knowledge_document (
 id uuid PRIMARY KEY,space_id uuid NOT NULL REFERENCES knowledge_space(id),title varchar(240) NOT NULL CHECK(length(trim(title))>0),
 latest_version_id uuid,published_version_id uuid,publication_state varchar(16) NOT NULL DEFAULT 'DRAFT'
 CHECK(publication_state IN ('DRAFT','PUBLISHED','DISABLED','ARCHIVED')),
 created_by uuid NOT NULL REFERENCES app_user(id),created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE document_version (
 id uuid PRIMARY KEY,document_id uuid NOT NULL REFERENCES knowledge_document(id),version_no integer NOT NULL CHECK(version_no>0),
 file_name varchar(255) NOT NULL,mime_type varchar(120) NOT NULL,sha256 char(64) NOT NULL,storage_key varchar(80) NOT NULL UNIQUE,
 file_size bigint NOT NULL CHECK(file_size>0),ingest_state varchar(20) NOT NULL DEFAULT 'QUEUED'
 CHECK(ingest_state IN ('UPLOADED','QUEUED','EXTRACTING','CHUNKING','EMBEDDING','READY','FAILED')),
 publication_state varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(publication_state IN ('DRAFT','PUBLISHED','SUPERSEDED')),
 published_at timestamptz,error_code varchar(80),parser_version varchar(160),job_id uuid,
 created_by uuid NOT NULL REFERENCES app_user(id),created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(document_id,version_no),UNIQUE(document_id,sha256),UNIQUE(document_id,id)
);
ALTER TABLE knowledge_document ADD CONSTRAINT fk_latest_same_document FOREIGN KEY(id,latest_version_id)
 REFERENCES document_version(document_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE knowledge_document ADD CONSTRAINT fk_published_same_document FOREIGN KEY(id,published_version_id)
 REFERENCES document_version(document_id,id) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE knowledge_chunk (
 id uuid PRIMARY KEY,document_version_id uuid NOT NULL REFERENCES document_version(id) ON DELETE CASCADE,
 chunk_index integer NOT NULL CHECK(chunk_index>=0),page_no integer,heading_path text,content text NOT NULL,
 strategy_version varchar(100) NOT NULL,embedding vector(1024),
 search_tsv tsvector GENERATED ALWAYS AS (to_tsvector('simple',content)) STORED,
 created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(document_version_id,chunk_index)
);
CREATE TABLE ai_job (
 id uuid PRIMARY KEY,requested_by uuid NOT NULL REFERENCES app_user(id),job_type varchar(32) NOT NULL,
 subject_type varchar(32) NOT NULL,subject_id uuid NOT NULL,status varchar(20) NOT NULL DEFAULT 'QUEUED'
 CHECK(status IN ('QUEUED','RUNNING','RETRYING','COMPLETED','FAILED')),
 attempt_count integer NOT NULL DEFAULT 0,idempotency_key varchar(160) NOT NULL UNIQUE,
 result_json jsonb,error_code varchar(80),created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE document_version ADD CONSTRAINT fk_version_job FOREIGN KEY(job_id) REFERENCES ai_job(id);
CREATE TABLE outbox_event (
 id uuid PRIMARY KEY,job_id uuid NOT NULL UNIQUE REFERENCES ai_job(id),event_type varchar(120) NOT NULL,
 aggregate_type varchar(80) NOT NULL,aggregate_id uuid NOT NULL,payload jsonb NOT NULL DEFAULT '{}',
 state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','PROCESSING','DELIVERED','DEAD')),
 attempts integer NOT NULL DEFAULT 0,available_at timestamptz NOT NULL DEFAULT now(),locked_until timestamptz,lease_token uuid,
 last_error_code varchar(80),created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_document_space ON knowledge_document(space_id,created_at DESC,id);
CREATE INDEX ix_outbox_due ON outbox_event(available_at,created_at) WHERE state IN ('PENDING','PROCESSING');
CREATE INDEX ix_chunk_search ON knowledge_chunk USING gin(search_tsv);
