-- =========================================================
-- V91__allow_quarantined_media_upload_session_cleanup_status.sql
--
-- Media Upload Sessions: Durable Cleanup Quarantine Foundation (MS-08E2 / S2D-2B1)
-- Extends chk_media_upload_sessions_cleanup_status CHECK constraint to permit
-- 'QUARANTINED' while preserving all existing permitted statuses.
-- Combined into a single atomic statement to avoid leaving the
-- table unconstrained if statement execution fails.
-- =========================================================

ALTER TABLE media_upload_sessions
    DROP CHECK chk_media_upload_sessions_cleanup_status,
    ADD CONSTRAINT chk_media_upload_sessions_cleanup_status
    CHECK (cleanup_status IN (
        'NONE',
        'PENDING',
        'IN_PROGRESS',
        'COMPLETED',
        'FAILED',
        'QUARANTINED'
    ));
