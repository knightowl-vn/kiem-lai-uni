-- =========================================================
-- V34__add_identity_users_avatar_media_asset_id.sql
-- Add transitional avatar_media_asset_id to identity_users
-- =========================================================

ALTER TABLE identity_users
    ADD COLUMN avatar_media_asset_id CHAR(36) NULL
        AFTER avatar_url;

CREATE INDEX idx_identity_users_avatar_media_asset_id
    ON identity_users(avatar_media_asset_id);
