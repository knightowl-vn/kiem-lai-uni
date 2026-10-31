-- =============================================================================
-- V93__enlarge_media_upload_sessions_provider_upload_id.sql
--
-- Media Storage: Enlarge provider_upload_id capacity for Cloudflare R2 (MS-08 R2-02)
--
-- Context:
-- Cloudflare R2 multipart Upload IDs are opaque cryptographic tokens (~343+ chars)
-- exceeding the original VARCHAR(255) allocated in V89.
--
-- Actions:
-- 1. Drop existing composite index idx_media_upload_sessions_provider_upload.
-- 2. Modify provider_upload_id to VARCHAR(1024) NULL.
-- 3. Recreate idx_media_upload_sessions_provider_upload with prefix length (255)
--    on provider_upload_id to satisfy InnoDB 3072-byte key limits in utf8mb4:
--    (50 * 4) + (255 * 4) = 1220 bytes <= 3072 bytes (even compatible with 8KB page: 1536).
--
-- Invariants preserved:
-- - Preserves NULL semantics for ALLOCATED sessions.
-- - Preserves existing CHECK constraint chk_media_upload_sessions_provider_upload_id.
-- - Preserves all existing B2 upload sessions and foreign keys.
-- =============================================================================

ALTER TABLE media_upload_sessions
    DROP INDEX idx_media_upload_sessions_provider_upload,
    MODIFY COLUMN provider_upload_id VARCHAR(1024) NULL,
    ADD INDEX idx_media_upload_sessions_provider_upload (storage_provider_id, provider_upload_id(255));
