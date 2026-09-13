-- =========================================================
-- V35__create_media_image_variants.sql
-- Create table for derivative image variants of MediaAssetVersion
-- =========================================================

CREATE TABLE media_image_variants (
    id CHAR(36) NOT NULL,
    version_id CHAR(36) NOT NULL,
    variant_key VARCHAR(100) NOT NULL,
    target_width INT NOT NULL,
    storage_provider_id VARCHAR(50) NOT NULL,
    storage_key VARCHAR(500)
        CHARACTER SET utf8mb4
        COLLATE utf8mb4_0900_bin
        NOT NULL,
    content_hash CHAR(64) NOT NULL,
    mime_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    width INT NOT NULL,
    height INT NOT NULL,
    created_at DATETIME(6) NOT NULL,

    CONSTRAINT pk_media_image_variants
        PRIMARY KEY (id),

    CONSTRAINT fk_media_image_variants_version
        FOREIGN KEY (version_id)
        REFERENCES media_asset_versions (id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,

    CONSTRAINT uq_media_image_variants_version_key
        UNIQUE (version_id, variant_key),

    CONSTRAINT uq_media_image_variants_provider_key
        UNIQUE (storage_provider_id, storage_key),

    CONSTRAINT chk_media_image_variants_target_width
        CHECK (target_width BETWEEN 16 AND 7680),

    CONSTRAINT chk_media_image_variants_size_bytes
        CHECK (size_bytes > 0),

    CONSTRAINT chk_media_image_variants_dimensions
        CHECK (width > 0 AND width <= target_width AND height > 0)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_media_image_variants_version_id
    ON media_image_variants (version_id);
