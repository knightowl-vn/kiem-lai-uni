-- =========================================================
-- V86__create_community_post_creation_guard.sql
--
-- Community Post Creation Anti-Spam / Creation Guard (MS-07B8.5.5)
-- Multi-instance safe creation rate limiter and immutable event ledger.
-- - Per-author database lock row for concurrent creation serialization
-- - Append-only immutable ledger for successful post creations
-- - No FK to Identity (boundary decoupling)
-- - No FK to community_posts (post hard-delete preserves quota history)
-- =========================================================

CREATE TABLE community_post_creation_guard (
    author_user_id CHAR(36) NOT NULL,

    CONSTRAINT pk_community_post_creation_guard
        PRIMARY KEY (author_user_id)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE TABLE community_post_creation_events (
    id CHAR(36) NOT NULL,
    author_user_id CHAR(36) NOT NULL,
    post_id CHAR(36) NOT NULL,
    normalized_caption_hash CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,

    CONSTRAINT pk_community_post_creation_events
        PRIMARY KEY (id)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_cpce_author_created
    ON community_post_creation_events (author_user_id, created_at ASC);

CREATE INDEX idx_cpce_author_hash_created
    ON community_post_creation_events (author_user_id, normalized_caption_hash, created_at DESC);
