package com.universe.media.domain;

/**
 * Lifecycle states of a durable multipart media upload session.
 * Matches V89 MySQL CHECK constraint chk_media_upload_sessions_status.
 */
public enum MediaUploadSessionStatus {
    ALLOCATED,
    INITIATED,
    COMPLETING,
    COMPLETED,
    ABORTED,
    EXPIRED,
    FAILED,
    CLEANED_UP
}
