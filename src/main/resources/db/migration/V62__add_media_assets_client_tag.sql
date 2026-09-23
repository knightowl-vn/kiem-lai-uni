-- =========================================================
-- V62__add_media_assets_client_tag.sql
-- Add optional opaque client_tag to media_assets and generic discovery index
-- =========================================================

ALTER TABLE media_assets
    ADD COLUMN client_tag VARCHAR(64) NULL
        AFTER visibility;

CREATE INDEX idx_media_assets_client_tag_status_created_at
    ON media_assets (client_tag, status, created_at, id);
