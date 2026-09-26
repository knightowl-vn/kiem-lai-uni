-- =========================================================
-- V73__update_interaction_comment_foreign_keys_for_hard_delete.sql
--
-- Enable true physical deletion for interaction comments (MS-05J4)
-- - Update self-referential parent/thread_root foreign keys to CASCADE
-- - Drop restrict foreign key from interaction_reports to allow hard deletion
-- =========================================================

-- 1. Update self-referential foreign keys on interaction_comments to CASCADE on delete
ALTER TABLE interaction_comments
    DROP FOREIGN KEY fk_interaction_comments_parent,
    DROP FOREIGN KEY fk_interaction_comments_thread_root;

ALTER TABLE interaction_comments
    ADD CONSTRAINT fk_interaction_comments_parent
        FOREIGN KEY (parent_comment_id)
        REFERENCES interaction_comments (id)
        ON UPDATE RESTRICT
        ON DELETE CASCADE,
    ADD CONSTRAINT fk_interaction_comments_thread_root
        FOREIGN KEY (thread_root_comment_id)
        REFERENCES interaction_comments (id)
        ON UPDATE RESTRICT
        ON DELETE CASCADE;

-- 2. Drop the restrictive foreign key on interaction_reports
-- Historical moderation reports preserve reported_body_snapshot and metadata
-- without blocking physical deletion of comments.
ALTER TABLE interaction_reports
    DROP FOREIGN KEY fk_interaction_reports_comment;
