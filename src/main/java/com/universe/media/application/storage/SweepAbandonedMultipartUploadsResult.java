package com.universe.media.application.storage;

import java.time.Instant;

/**
 * Result record containing counts and execution timestamp for a multipart upload session sweep run (MS-08E2-L3-2).
 *
 * @param scannedCount          total candidate sessions evaluated during the run
 * @param attemptedCount        candidates that satisfied local eligibility and attempted guarded abort invocation (not necessarily leased)
 * @param skippedCount          candidates skipped due to local ineligibility, non-pending status, active lease, or missing provider upload ID
 * @param quarantinedCount      sessions whose provider multipart upload was successfully aborted/quarantined
 * @param verifiedCleanedCount  sessions whose durable physical cleanup was verified and transitioned to CLEANED_UP (explicitly 0 in this slice)
 * @param failedCount           sessions whose guarded abort claim or execution failed (isolated per session)
 * @param expirationFailedCount stale active sessions whose expiration transition failed (isolated per session)
 * @param executedAt            timestamp when the sweep was orchestrated
 */
public record SweepAbandonedMultipartUploadsResult(
        int scannedCount,
        int attemptedCount,
        int skippedCount,
        int quarantinedCount,
        int verifiedCleanedCount,
        int failedCount,
        int expirationFailedCount,
        Instant executedAt
) {
}
