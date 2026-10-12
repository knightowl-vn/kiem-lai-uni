package com.universe.media.application.ports.storage;

import com.universe.media.domain.StorageKey;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable snapshot of a storage object presence observation from provider metadata.
 */
public record StorageObjectPresenceObservation(
        StorageKey storageKey,
        StorageObjectPresence presence,
        MultipartObjectMetadata metadata,
        Instant observedAt,
        String safeDiagnostic
) {
    public StorageObjectPresenceObservation {
        Objects.requireNonNull(storageKey, "storageKey cannot be null");
        Objects.requireNonNull(presence, "presence cannot be null");
        observedAt = (observedAt != null) ? observedAt : Instant.now();
    }
}
