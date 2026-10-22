CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE app_user (
 id uuid PRIMARY KEY, username varchar(80) NOT NULL UNIQUE, password_hash text NOT NULL,
 display_name varchar(120) NOT NULL, active boolean NOT NULL DEFAULT true,
 must_change_password boolean NOT NULL DEFAULT true, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE app_role (id uuid PRIMARY KEY, code varchar(40) NOT NULL UNIQUE CHECK(code IN ('ADMIN','AGENT','KB_EDITOR','REQUESTER')));
INSERT INTO app_role(id,code) VALUES
 ('10000000-0000-0000-0000-000000000001','ADMIN'),('10000000-0000-0000-0000-000000000002','AGENT'),
 ('10000000-0000-0000-0000-000000000003','REQUESTER'),('10000000-0000-0000-0000-000000000004','KB_EDITOR');
CREATE TABLE user_role (user_id uuid NOT NULL REFERENCES app_user(id),role_id uuid NOT NULL REFERENCES app_role(id),PRIMARY KEY(user_id,role_id));
CREATE TABLE user_session (
 id uuid PRIMARY KEY,user_id uuid NOT NULL REFERENCES app_user(id),token_hash char(64) NOT NULL UNIQUE,
 expires_at timestamptz NOT NULL,revoked_at timestamptz,created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_session_user ON user_session(user_id);
CREATE INDEX ix_session_expiry ON user_session(expires_at);
CREATE TABLE support_queue (id uuid PRIMARY KEY,code varchar(80) NOT NULL UNIQUE,name varchar(160) NOT NULL,active boolean NOT NULL DEFAULT true);
INSERT INTO support_queue(id,code,name) VALUES ('20000000-0000-0000-0000-000000000001','GENERAL','通用支持');
CREATE TABLE queue_member (queue_id uuid NOT NULL REFERENCES support_queue(id),user_id uuid NOT NULL REFERENCES app_user(id),can_process boolean NOT NULL DEFAULT true,PRIMARY KEY(queue_id,user_id));
CREATE INDEX ix_queue_member_user ON queue_member(user_id,queue_id);
CREATE TABLE service_catalog (id uuid PRIMARY KEY,code varchar(60) NOT NULL UNIQUE,name varchar(160) NOT NULL,active boolean NOT NULL DEFAULT true);
CREATE TABLE audit_event (
 id uuid PRIMARY KEY,actor_id uuid REFERENCES app_user(id),action varchar(100) NOT NULL,subject_type varchar(80) NOT NULL,
 subject_id uuid,trace_id varchar(80),metadata jsonb NOT NULL DEFAULT '{}',created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_created ON audit_event(created_at DESC,id);
CREATE INDEX ix_audit_subject ON audit_event(subject_type,subject_id,created_at DESC);
