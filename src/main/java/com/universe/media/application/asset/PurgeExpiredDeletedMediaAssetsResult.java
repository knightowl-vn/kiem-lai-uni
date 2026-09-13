package com.universe.media.application.asset;

import java.time.Instant;

/**
 * Passive result record capturing the outcome of an expired DELETED media asset batch purge run.
 *
 * @param candidates number of candidate assets found for purging
 * @param purged number of candidate assets successfully purged
 * @param failed number of candidate assets whose purge failed
 * @param executedAt timestamp when the batch execution was performed
 */
public record PurgeExpiredDeletedMediaAssetsResult(
        int candidates,
        int purged,
        int failed,
        Instant executedAt
) {
}
