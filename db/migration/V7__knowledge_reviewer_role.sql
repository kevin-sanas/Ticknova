ALTER TABLE app_role DROP CONSTRAINT app_role_code_check;
ALTER TABLE app_role ADD CONSTRAINT app_role_code_check
 CHECK(code IN ('ADMIN','AGENT','KB_EDITOR','KB_REVIEWER','REQUESTER'));
INSERT INTO app_role(id,code) VALUES ('10000000-0000-0000-0000-000000000005','KB_REVIEWER');

ALTER TABLE knowledge_space_member ADD COLUMN can_review boolean NOT NULL DEFAULT false;
ALTER TABLE knowledge_space_member ADD CONSTRAINT knowledge_space_member_review_read
 CHECK(NOT can_review OR can_read);
ALTER TABLE knowledge_space_member ADD CONSTRAINT knowledge_space_member_separate_duties
 CHECK(NOT (can_edit AND can_review));
