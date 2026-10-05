-- =========================================================
-- V83__add_community_post_publication_timestamps.sql
--
-- Community Post Publication Timestamps (MS-07B8.5.4)
-- - Adds published_at and review_requested_at timestamps
-- - Backfills historical data according to canonical semantics
-- - Replaces old created_at status indexes with published_at / review_requested_at indexes
-- =========================================================

ALTER TABLE community_posts
    ADD COLUMN published_at DATETIME(6) NULL AFTER content_version,
    ADD COLUMN review_requested_at DATETIME(6) NULL AFTER published_at;

-- Backfill PUBLISHED and HIDDEN posts: historically published at created_at
UPDATE community_posts
    SET published_at = created_at,
        review_requested_at = NULL
    WHERE status IN ('PUBLISHED', 'HIDDEN');

-- Backfill PENDING_REVIEW posts: never published, waiting since created_at
UPDATE community_posts
    SET published_at = NULL,
        review_requested_at = created_at
    WHERE status = 'PENDING_REVIEW';

-- Backfill REJECTED posts: never published, not waiting for review
UPDATE community_posts
    SET published_at = NULL,
        review_requested_at = NULL
    WHERE status = 'REJECTED';

-- Drop obsolete created_at status indexes
DROP INDEX idx_community_posts_status_feed ON community_posts;
DROP INDEX idx_community_posts_author_status_feed ON community_posts;
DROP INDEX idx_community_posts_status_created_asc ON community_posts;

-- Add query-supporting indexes for public feeds (published_at DESC) and review queue (review_requested_at ASC)
CREATE INDEX idx_community_posts_status_published
    ON community_posts (status, published_at DESC, id DESC);

CREATE INDEX idx_community_posts_author_status_published
    ON community_posts (author_user_id, status, published_at DESC, id DESC);

CREATE INDEX idx_community_posts_status_review_requested_asc
    ON community_posts (status, review_requested_at ASC, id ASC);
