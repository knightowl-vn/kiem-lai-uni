-- =========================================================
-- V36__align_media_image_variant_key_collation.sql
-- Enforce binary exact collation on media_image_variants.variant_key
-- =========================================================

ALTER TABLE media_image_variants
    MODIFY COLUMN variant_key VARCHAR(100)
        CHARACTER SET utf8mb4
        COLLATE utf8mb4_0900_bin
        NOT NULL;
