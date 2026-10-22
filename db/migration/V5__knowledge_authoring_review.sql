CREATE TABLE knowledge_category (
 id uuid PRIMARY KEY,space_id uuid NOT NULL REFERENCES knowledge_space(id),parent_id uuid,
 name varchar(160) NOT NULL CHECK(length(trim(name))>0),version integer NOT NULL DEFAULT 1 CHECK(version>0),
 UNIQUE(space_id,id),FOREIGN KEY(space_id,parent_id) REFERENCES knowledge_category(space_id,id),CHECK(parent_id IS DISTINCT FROM id)
);
CREATE INDEX ix_knowledge_category_parent ON knowledge_category(space_id,parent_id);
ALTER TABLE knowledge_document ADD COLUMN category_id uuid;
ALTER TABLE knowledge_document ADD CONSTRAINT fk_document_category FOREIGN KEY(space_id,category_id) REFERENCES knowledge_category(space_id,id);
ALTER TABLE document_version ADD COLUMN review_state varchar(16) NOT NULL DEFAULT 'DRAFT'
 CHECK(review_state IN ('DRAFT','PENDING','APPROVED','REJECTED'));
ALTER TABLE document_version ADD COLUMN review_grandfathered boolean NOT NULL DEFAULT false;
ALTER TABLE document_version ADD COLUMN reviewer_id uuid REFERENCES app_user(id);
ALTER TABLE document_version ADD COLUMN review_reason varchar(500);
ALTER TABLE document_version ADD COLUMN reviewed_at timestamptz;
ALTER TABLE document_version ADD COLUMN authored_title varchar(240);
ALTER TABLE document_version ADD COLUMN authored_category_id uuid REFERENCES knowledge_category(id);
ALTER TABLE document_version ADD COLUMN rich_content jsonb CHECK(rich_content IS NULL OR jsonb_typeof(rich_content)='object');
ALTER TABLE document_version ADD COLUMN rich_fingerprint char(64);
ALTER TABLE document_version ADD CONSTRAINT ck_rich_snapshot CHECK((rich_content IS NULL)=(rich_fingerprint IS NULL));
UPDATE document_version v SET authored_title=d.title,authored_category_id=d.category_id,
 review_grandfathered=(v.published_at IS NOT NULL OR v.publication_state IN ('PUBLISHED','SUPERSEDED')),
 review_state=CASE WHEN v.published_at IS NOT NULL OR v.publication_state IN ('PUBLISHED','SUPERSEDED') THEN 'APPROVED' ELSE 'DRAFT' END
 FROM knowledge_document d WHERE d.id=v.document_id;
ALTER TABLE document_version ALTER COLUMN authored_title SET DEFAULT 'Untitled';
ALTER TABLE document_version ALTER COLUMN authored_title SET NOT NULL;
ALTER TABLE document_version ADD CONSTRAINT ck_review_independent CHECK(reviewer_id IS NULL OR reviewer_id<>created_by);
ALTER TABLE document_version ADD CONSTRAINT ck_review_evidence CHECK(
 (review_state IN ('DRAFT','PENDING') AND reviewer_id IS NULL AND reviewed_at IS NULL AND review_reason IS NULL)
 OR (review_state='APPROVED' AND ((reviewer_id IS NOT NULL AND reviewed_at IS NOT NULL) OR (reviewer_id IS NULL AND review_grandfathered)))
 OR (review_state='REJECTED' AND reviewer_id IS NOT NULL AND reviewed_at IS NOT NULL AND length(trim(review_reason))>0));
-- Preserve legacy publications without fabricating review identities.
-- Existing real publications always carry published_at; no new version may use this exception through the API.
CREATE TABLE knowledge_draft (
 document_id uuid PRIMARY KEY REFERENCES knowledge_document(id),title varchar(240) NOT NULL CHECK(length(trim(title))>0),
 category_id uuid REFERENCES knowledge_category(id),content jsonb NOT NULL CHECK(jsonb_typeof(content)='object'),
 version integer NOT NULL DEFAULT 1 CHECK(version>0),updated_at timestamptz NOT NULL DEFAULT now(),updated_by uuid NOT NULL REFERENCES app_user(id)
);
CREATE INDEX ix_document_category ON knowledge_document(space_id,category_id);
ALTER TABLE document_version DROP CONSTRAINT document_version_document_id_sha256_key;
CREATE UNIQUE INDEX ux_file_version_source ON document_version(document_id,sha256,authored_title,authored_category_id) NULLS NOT DISTINCT
 WHERE rich_content IS NULL AND review_state<>'REJECTED';
CREATE UNIQUE INDEX ux_rich_version_source ON document_version(document_id,rich_fingerprint)
 WHERE rich_content IS NOT NULL AND review_state<>'REJECTED';
CREATE FUNCTION immutable_knowledge_version_source() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.rich_content IS DISTINCT FROM OLD.rich_content OR NEW.rich_fingerprint IS DISTINCT FROM OLD.rich_fingerprint
 OR NEW.authored_title IS DISTINCT FROM OLD.authored_title OR NEW.authored_category_id IS DISTINCT FROM OLD.authored_category_id
 OR NEW.sha256 IS DISTINCT FROM OLD.sha256 OR NEW.storage_key IS DISTINCT FROM OLD.storage_key
 OR NEW.created_by IS DISTINCT FROM OLD.created_by OR NEW.review_grandfathered IS DISTINCT FROM OLD.review_grandfathered THEN
  RAISE EXCEPTION 'Knowledge version source is immutable';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER document_version_source_immutable BEFORE UPDATE ON document_version
 FOR EACH ROW EXECUTE FUNCTION immutable_knowledge_version_source();

