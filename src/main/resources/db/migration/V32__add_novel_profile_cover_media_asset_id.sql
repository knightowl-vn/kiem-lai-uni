-- =========================================================
-- V32__add_novel_profile_cover_media_asset_id.sql
-- Add transitional cover_media_asset_id to novel_profile
-- =========================================================

ALTER TABLE novel_profile
    ADD COLUMN cover_media_asset_id CHAR(36) NULL
    AFTER cover_image_url;
