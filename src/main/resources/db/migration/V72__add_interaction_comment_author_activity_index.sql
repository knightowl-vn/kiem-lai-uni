-- =========================================================
-- V72__add_interaction_comment_author_activity_index.sql
--
-- Author activity index for personal comment queries
-- =========================================================

CREATE INDEX idx_interaction_comments_author_status_created_id
    ON interaction_comments (author_user_id, status, created_at, id);
