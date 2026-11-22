package com.universe.media.application.hls;

/**
 * Explicit outcome classification for processing one claimed HLS packaging lease.
 */
public enum HlsPackagingOutcomeStatus {
    SUCCESS,
    STALE_OR_CANCELLED,
    TRANSIENT_FAILURE,
    PERMANENT_FAILURE,
    OPERATIONAL_BLOCKED
}
