package com.universe.media.application.ports.storage;

import com.universe.media.domain.StorageKey;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Immutable snapshot of a multipart parts observation.
 */
public record MultipartPartsObservation(
        StorageKey storageKey,
        String uploadId,
        MultipartPartsSummary summary,
        List<MultipartUploadedPart> parts,
        Instant observedAt,
        String safeDiagnostic
) {
    public MultipartPartsObservation {
        Objects.requireNonNull(storageKey, "storageKey cannot be null");
        Objects.requireNonNull(uploadId, "uploadId cannot be null");
        Objects.requireNonNull(summary, "summary cannot be null");
        parts = (parts != null) ? List.copyOf(parts) : List.of();
        observedAt = (observedAt != null) ? observedAt : Instant.now();
    }
}
