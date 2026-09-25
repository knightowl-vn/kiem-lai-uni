-- =========================================================
-- V67__add_wiki_contribution_workflow_metadata.sql
--
-- Wiki Contribution Workflow & Decision Metadata (MS-05H7)
-- Adds decision metadata columns to wiki_contributions table.
-- Scalar user ID, no cross-context foreign key to Identity.
-- =========================================================

ALTER TABLE wiki_contributions
    ADD COLUMN resolution_note VARCHAR(2000) NULL,
    ADD COLUMN resolved_by_user_id CHAR(36) NULL,
    ADD COLUMN resolved_at DATETIME(6) NULL,
    ADD COLUMN resolved_article_content_version BIGINT NULL;
