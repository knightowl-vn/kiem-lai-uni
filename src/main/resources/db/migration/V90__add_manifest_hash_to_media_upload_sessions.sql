-- =========================================================
-- V90__add_manifest_hash_to_media_upload_sessions.sql
-- Media Upload Sessions: Add nullable manifest_hash column
-- =========================================================

ALTER TABLE media_upload_sessions
    ADD COLUMN manifest_hash CHAR(64) NULL;
