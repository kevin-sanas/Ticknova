CREATE TABLE ticket (
 id uuid PRIMARY KEY,ticket_no bigint GENERATED ALWAYS AS IDENTITY UNIQUE,title varchar(200) NOT NULL,
 description text NOT NULL,requester_id uuid NOT NULL REFERENCES app_user(id),service_id uuid REFERENCES service_catalog(id),
 assignee_id uuid REFERENCES app_user(id),queue_id uuid NOT NULL REFERENCES support_queue(id),
 priority varchar(16) NOT NULL DEFAULT 'NORMAL' CHECK(priority IN ('LOW','NORMAL','HIGH','URGENT')),
 status varchar(32) NOT NULL DEFAULT 'NEW' CHECK(status IN ('NEW','TRIAGED','IN_PROGRESS','WAITING_CUSTOMER','WAITING_THIRD_PARTY','RESOLVED','CLOSED')),
 resolution_note text,version bigint NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK(length(title)>0),CHECK(length(description) BETWEEN 1 AND 20000),
 CHECK(status NOT IN ('RESOLVED','CLOSED') OR (resolution_note IS NOT NULL AND length(trim(resolution_note))>0))
);
CREATE TABLE ticket_label (ticket_id uuid NOT NULL REFERENCES ticket(id),label varchar(32) NOT NULL CHECK(length(label)>0),PRIMARY KEY(ticket_id,label));
CREATE INDEX ix_ticket_label ON ticket_label(label,ticket_id);
CREATE TABLE ticket_comment (
 id uuid PRIMARY KEY,ticket_id uuid NOT NULL REFERENCES ticket(id),author_id uuid NOT NULL REFERENCES app_user(id),
 visibility varchar(24) NOT NULL CHECK(visibility IN ('PUBLIC_REPLY','INTERNAL_NOTE')),body text NOT NULL CHECK(length(body) BETWEEN 1 AND 10000),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE ticket_transition (
 id uuid PRIMARY KEY,request_id uuid NOT NULL UNIQUE,ticket_id uuid NOT NULL REFERENCES ticket(id),actor_id uuid NOT NULL REFERENCES app_user(id),
 from_status varchar(32) NOT NULL,to_status varchar(32) NOT NULL,reason text,created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE api_idempotency (
 id uuid PRIMARY KEY,user_id uuid NOT NULL REFERENCES app_user(id),operation varchar(100) NOT NULL,idempotency_key varchar(128) NOT NULL,
 request_hash char(64) NOT NULL,state varchar(16) NOT NULL CHECK(state IN ('IN_PROGRESS','COMPLETED')),
 response_status smallint,response_resource_id uuid,created_at timestamptz NOT NULL DEFAULT now(),expires_at timestamptz NOT NULL,
 UNIQUE(user_id,operation,idempotency_key),
 CHECK((state='IN_PROGRESS' AND response_status IS NULL) OR (state='COMPLETED' AND response_status BETWEEN 200 AND 299))
);
CREATE INDEX ix_idempotency_expiry ON api_idempotency(expires_at);
CREATE INDEX ix_ticket_queue ON ticket(queue_id,status,updated_at DESC);
CREATE INDEX ix_ticket_requester ON ticket(requester_id,created_at DESC);
CREATE INDEX ix_comment_ticket ON ticket_comment(ticket_id,created_at,id);
CREATE INDEX ix_transition_ticket ON ticket_transition(ticket_id,created_at,id);
