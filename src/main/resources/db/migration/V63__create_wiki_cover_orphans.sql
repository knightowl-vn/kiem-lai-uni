-- =========================================================
-- V63__create_wiki_cover_orphans.sql
--
-- Wiki Cover Orphan State Persistence Foundation (MS-05G8C3)
-- Tracks Media assets observed with zero WikiArticle cover references.
-- Bounded-context purity: No foreign keys to media_assets or wiki_articles.
-- =========================================================

CREATE TABLE wiki_cover_orphans (
    media_asset_id CHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL,
    first_seen_orphan_at DATETIME(6) NOT NULL,
    claim_token CHAR(36) NULL,
    locked_at DATETIME(6) NULL,
    retry_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,

    CONSTRAINT pk_wiki_cover_orphans
        PRIMARY KEY (media_asset_id),

    CONSTRAINT chk_wiki_cover_orphans_status
        CHECK (status IN ('PENDING', 'PROCESSING'))
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_wiki_cover_orphans_status_first_seen
    ON wiki_cover_orphans (status, first_seen_orphan_at);

CREATE INDEX idx_wiki_cover_orphans_status_locked
    ON wiki_cover_orphans (status, locked_at);
