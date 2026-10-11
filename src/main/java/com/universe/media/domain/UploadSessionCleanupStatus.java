package com.universe.media.domain;

/**
 * Cleanup lifecycle states of a durable multipart media upload session.
 * Matches V91 MySQL CHECK constraint chk_media_upload_sessions_cleanup_status.
 */
public enum UploadSessionCleanupStatus {
    NONE,
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    QUARANTINED
}
