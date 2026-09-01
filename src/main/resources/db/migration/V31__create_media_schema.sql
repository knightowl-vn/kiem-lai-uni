-- =========================================================
-- V31__create_media_schema.sql
-- Media Core Foundation: media_assets and media_asset_versions
-- =========================================================

CREATE TABLE media_assets (
    id CHAR(36) NOT NULL,
    media_type VARCHAR(20) NOT NULL,
    visibility VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    current_version_number INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    persistence_version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT pk_media_assets
        PRIMARY KEY (id),

    CONSTRAINT chk_media_assets_media_type
        CHECK (media_type IN ('IMAGE', 'AUDIO', 'VIDEO', 'DOCUMENT')),

    CONSTRAINT chk_media_assets_visibility
        CHECK (visibility IN ('PUBLIC', 'PRIVATE', 'RESTRICTED')),

    CONSTRAINT chk_media_assets_status
        CHECK (status IN ('ACTIVE', 'ARCHIVED', 'DELETED')),

    CONSTRAINT chk_media_assets_current_version_number
        CHECK (current_version_number >= 1),

    CONSTRAINT chk_media_assets_persistence_version
        CHECK (persistence_version >= 0),

    CONSTRAINT chk_media_assets_updated_at
        CHECK (updated_at >= created_at)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;


CREATE TABLE media_asset_versions (
    id CHAR(36) NOT NULL,
    asset_id CHAR(36) NOT NULL,
    version_number INT NOT NULL,
    storage_provider_id VARCHAR(50) NOT NULL,
    storage_key VARCHAR(500)
        CHARACTER SET utf8mb4
        COLLATE utf8mb4_0900_bin
        NOT NULL,
    public_url VARCHAR(1000) NULL,
    content_hash CHAR(64) NOT NULL,
    mime_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL,

    CONSTRAINT pk_media_asset_versions
        PRIMARY KEY (id),

    CONSTRAINT fk_media_asset_versions_asset
        FOREIGN KEY (asset_id)
        REFERENCES media_assets (id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,

    CONSTRAINT uq_media_asset_versions_asset_version
        UNIQUE (asset_id, version_number),

    CONSTRAINT uq_media_asset_versions_provider_key
        UNIQUE (storage_provider_id, storage_key),

    CONSTRAINT chk_media_asset_versions_version_number
        CHECK (version_number >= 1),

    CONSTRAINT chk_media_asset_versions_size_bytes
        CHECK (size_bytes > 0)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_media_asset_versions_content_hash
    ON media_asset_versions (content_hash);
