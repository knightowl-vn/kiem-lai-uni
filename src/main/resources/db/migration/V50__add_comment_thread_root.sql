-- =========================================================
-- V50__add_comment_thread_root.sql
--
-- Add thread root comment ID for arbitrary reply ancestry
-- =========================================================

ALTER TABLE interaction_comments
    ADD COLUMN thread_root_comment_id CHAR(36) NULL AFTER parent_comment_id;

UPDATE interaction_comments
    SET thread_root_comment_id = parent_comment_id
    WHERE parent_comment_id IS NOT NULL;

ALTER TABLE interaction_comments
    ADD CONSTRAINT fk_interaction_comments_thread_root
        FOREIGN KEY (thread_root_comment_id)
        REFERENCES interaction_comments (id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,
    ADD CONSTRAINT chk_interaction_comments_thread_hierarchy
        CHECK ((parent_comment_id IS NULL AND thread_root_comment_id IS NULL)
            OR (parent_comment_id IS NOT NULL AND thread_root_comment_id IS NOT NULL)),
    ADD CONSTRAINT chk_interaction_comments_no_self_thread_root
        CHECK (thread_root_comment_id IS NULL OR thread_root_comment_id <> id);

CREATE INDEX idx_interaction_comments_thread_root_created_id
    ON interaction_comments (thread_root_comment_id, created_at, id);
