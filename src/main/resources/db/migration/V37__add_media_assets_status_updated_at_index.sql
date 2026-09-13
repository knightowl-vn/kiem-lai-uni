-- =========================================================
-- V37__add_media_assets_status_updated_at_index.sql
-- Add composite index for finding expired DELETED media assets
-- =========================================================

CREATE INDEX idx_media_assets_status_updated_at
    ON media_assets (status, updated_at);
