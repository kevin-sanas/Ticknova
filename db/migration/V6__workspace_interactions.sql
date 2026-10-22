ALTER TABLE ai_job DROP CONSTRAINT ai_job_status_check;
ALTER TABLE ai_job ADD CONSTRAINT ai_job_status_check
 CHECK(status IN ('QUEUED','RUNNING','RETRYING','COMPLETED','FAILED','CANCELLED'));

-- One authorized reader contributes at most one view to a document per UTC day.
CREATE TABLE knowledge_document_view (
 document_id uuid NOT NULL REFERENCES knowledge_document(id) ON DELETE CASCADE,
 user_id uuid NOT NULL REFERENCES app_user(id),
 view_date date NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(document_id,user_id,view_date)
);
CREATE INDEX ix_ticket_created ON ticket(created_at,id);
CREATE INDEX ix_ticket_resolved_transition ON ticket_transition(created_at,ticket_id) WHERE to_status='RESOLVED';
