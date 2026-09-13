-- =========================================================
-- V33__add_wiki_images_media_reference.sql
-- Add transitional media_asset_id and make public_id nullable
-- =========================================================

ALTER TABLE wiki_images
    ADD COLUMN media_asset_id CHAR(36) NULL
        AFTER public_id,
    MODIFY COLUMN public_id VARCHAR(255) NULL;

CREATE INDEX idx_wiki_images_media_asset_id
    ON wiki_images(media_asset_id);
