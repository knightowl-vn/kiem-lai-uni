package com.universe.wiki.application.ports;

import com.universe.wiki.domain.orphan.WikiCoverOrphanStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Application read representation of a Wiki cover orphan reconciliation record.
 */
public record WikiCoverOrphanRecord(
        UUID mediaAssetId,
        WikiCoverOrphanStatus status,
        Instant firstSeenOrphanAt,
        UUID claimToken,
        Instant lockedAt,
        int retryCount,
        String lastError,
        Instant createdAt,
        Instant updatedAt
) {
    public WikiCoverOrphanRecord {
        Objects.requireNonNull(mediaAssetId, "Media asset ID cannot be null.");
        Objects.requireNonNull(status, "Status cannot be null.");
        Objects.requireNonNull(firstSeenOrphanAt, "First seen orphan timestamp cannot be null.");
        Objects.requireNonNull(createdAt, "Created at timestamp cannot be null.");
        Objects.requireNonNull(updatedAt, "Updated at timestamp cannot be null.");
        if (retryCount < 0) {
            throw new IllegalArgumentException("Retry count cannot be negative: " + retryCount);
        }
    }
}
