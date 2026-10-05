-- =========================================================
-- V80__add_community_post_moderation_status.sql
--
-- Community Post Moderation Status (MS-07B8.5.2)
-- - Adds status column with strict domain CHECK constraint
-- - Backfills existing posts as PUBLISHED
-- - Enforces NOT NULL without DB DEFAULT
-- - Adds query-justified composite indexes for status-aware feed queries
-- =========================================================

ALTER TABLE community_posts
    ADD COLUMN status VARCHAR(32) NULL AFTER image_media_asset_id;

UPDATE community_posts
    SET status = 'PUBLISHED'
    WHERE status IS NULL;

ALTER TABLE community_posts
    MODIFY COLUMN status VARCHAR(32) NOT NULL;

ALTER TABLE community_posts
    ADD CONSTRAINT chk_community_posts_status
        CHECK (status IN ('PUBLISHED', 'PENDING_REVIEW', 'HIDDEN', 'REJECTED'));

CREATE INDEX idx_community_posts_status_feed
    ON community_posts (status, created_at DESC, id DESC);

CREATE INDEX idx_community_posts_author_status_feed
    ON community_posts (author_user_id, status, created_at DESC, id DESC);

CREATE INDEX idx_community_posts_status_created_asc
    ON community_posts (status, created_at ASC, id ASC);
