package com.universe.media.application.hls;

/**
 * Explicit deterministic outcome for opt-in automatic HLS packaging enqueue attempts.
 */
public enum AutoPackagingEnqueueStatus {
    CREATED,
    REUSED_ACTIVE,
    SKIPPED_DISABLED,
    SKIPPED_AMBIENT_TRANSACTION,
    SKIPPED_INELIGIBLE_ASSET,
    SKIPPED_STALE_VERSION,
    SKIPPED_READY,
    SKIPPED_TERMINAL_JOB,
    SKIPPED_FAILED_ISOLATION
}
