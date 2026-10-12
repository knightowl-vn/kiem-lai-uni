package com.universe.media.application.ports.storage;

import com.universe.media.domain.StorageKey;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable snapshot of a multipart upload presence observation in provider listings.
 */
public record MultipartUploadPresenceObservation(
        StorageKey storageKey,
        String uploadId,
        MultipartUploadPresence presence,
        Instant observedAt,
        String safeDiagnostic
) {
    public MultipartUploadPresenceObservation {
        Objects.requireNonNull(storageKey, "storageKey cannot be null");
        Objects.requireNonNull(uploadId, "uploadId cannot be null");
        Objects.requireNonNull(presence, "presence cannot be null");
        observedAt = (observedAt != null) ? observedAt : Instant.now();
    }
}
