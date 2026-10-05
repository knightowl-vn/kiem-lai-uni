-- =========================================================
-- V85__add_community_post_pending_caption.sql
--
-- Community Post Pending Caption Edit Review Model (MS-07B8.5.4)
-- - Adds pending_caption to community_posts
-- - Allows published posts to undergo caption edit review without disappearing publicly
-- =========================================================

ALTER TABLE community_posts
    ADD COLUMN pending_caption TEXT NULL AFTER caption;

ALTER TABLE community_posts
    ADD CONSTRAINT chk_community_posts_pending_caption
        CHECK (pending_caption IS NULL OR (CHAR_LENGTH(TRIM(pending_caption)) > 0 AND CHAR_LENGTH(pending_caption) <= 2000));

CREATE INDEX idx_community_posts_review_queue
    ON community_posts (review_requested_at ASC, id ASC);

CREATE INDEX idx_community_posts_author_review_queue
    ON community_posts (author_user_id, review_requested_at DESC, id DESC);
