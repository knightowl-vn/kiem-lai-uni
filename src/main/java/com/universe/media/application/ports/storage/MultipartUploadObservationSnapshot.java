package com.universe.media.application.ports.storage;

import com.universe.media.domain.StorageKey;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Combined observation snapshot capturing independent provider observations for a target upload.
 * <p>
 * Crucial Invariant: All three observations (upload presence, object presence, parts summary)
 * are preserved independently. They are NOT mutually exclusive and are NOT collapsed into
 * artificial aggregate states such as ABORTED or CLEANED_UP.
 */
public record MultipartUploadObservationSnapshot(
        StorageKey storageKey,
        String uploadId,
        MultipartUploadPresence uploadPresence,
        StorageObjectPresence objectPresence,
        MultipartPartsSummary partsSummary,
        List<MultipartUploadedPart> parts,
        MultipartObjectMetadata objectMetadata,
        Instant observedAt,
        String safeDiagnostic
) {
    public MultipartUploadObservationSnapshot {
        Objects.requireNonNull(storageKey, "storageKey cannot be null");
        Objects.requireNonNull(uploadId, "uploadId cannot be null");
        Objects.requireNonNull(uploadPresence, "uploadPresence cannot be null");
        Objects.requireNonNull(objectPresence, "objectPresence cannot be null");
        Objects.requireNonNull(partsSummary, "partsSummary cannot be null");
        parts = (parts != null) ? List.copyOf(parts) : List.of();
        observedAt = (observedAt != null) ? observedAt : Instant.now();
    }
}
