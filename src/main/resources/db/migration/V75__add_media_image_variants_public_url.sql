-- =========================================================
-- V75__add_media_image_variants_public_url.sql
-- Add public_url column to media_image_variants, make
-- storage location and derivative measurements nullable
-- for external virtual variants, preserve unique index
-- uq_media_image_variants_provider_key, and enforce
-- delivery-mode fail-closed check constraint.
-- =========================================================

ALTER TABLE media_image_variants
    ADD COLUMN public_url VARCHAR(1000) NULL AFTER storage_key;

-- Make storage provider/key nullable for external virtual variants.
-- Note: uq_media_image_variants_provider_key (storage_provider_id, storage_key)
-- is preserved. In MySQL/InnoDB, unique indexes permit multiple rows where
-- either column is NULL (standard SQL NULL != NULL semantics).
ALTER TABLE media_image_variants
    MODIFY COLUMN storage_provider_id VARCHAR(50) NULL,
    MODIFY COLUMN storage_key VARCHAR(500)
        CHARACTER SET utf8mb4
        COLLATE utf8mb4_0900_bin
        NULL;

-- Make derivative measurement columns nullable for virtual variants
ALTER TABLE media_image_variants
    MODIFY COLUMN content_hash CHAR(64) NULL,
    MODIFY COLUMN size_bytes BIGINT NULL,
    MODIFY COLUMN width INT NULL,
    MODIFY COLUMN height INT NULL;

-- Update check constraints to allow NULL for virtual variants
ALTER TABLE media_image_variants
    DROP CHECK chk_media_image_variants_size_bytes;

ALTER TABLE media_image_variants
    ADD CONSTRAINT chk_media_image_variants_size_bytes
        CHECK (size_bytes IS NULL OR size_bytes > 0);

ALTER TABLE media_image_variants
    DROP CHECK chk_media_image_variants_dimensions;

ALTER TABLE media_image_variants
    ADD CONSTRAINT chk_media_image_variants_dimensions
        CHECK ((width IS NULL AND height IS NULL) OR (width > 0 AND width <= target_width AND height > 0));

-- Enforce delivery mode XOR invariant: physical vs external virtual variant
ALTER TABLE media_image_variants
    ADD CONSTRAINT chk_media_image_variants_delivery_mode
        CHECK (
            (
                public_url IS NULL
                AND storage_provider_id IS NOT NULL
                AND storage_key IS NOT NULL
                AND content_hash IS NOT NULL
                AND size_bytes IS NOT NULL
                AND width IS NOT NULL
                AND height IS NOT NULL
            )
            OR
            (
                public_url IS NOT NULL
                AND CHAR_LENGTH(TRIM(public_url)) > 0
                AND storage_provider_id IS NULL
                AND storage_key IS NULL
                AND content_hash IS NULL
                AND size_bytes IS NULL
                AND width IS NULL
                AND height IS NULL
            )
        );
