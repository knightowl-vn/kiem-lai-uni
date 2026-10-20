package com.universe.screen.application.dto;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * View representation of a recoverable completed Screen video upload session.
 */
public record RecoverableVideoUploadView(
        UUID sessionId,
        UUID videoMediaAssetId,
        UUID targetEntryId,
        String targetEntryTitle,
        UUID targetTitleId,
        String originalFilename,
        long declaredSizeBytes,
        Instant completedAt,
        String intent,
        UUID replacementExpectedAssetId,
        RecoveryClassification classification,
        boolean canRetry,
        boolean canDiscard
) {
    public RecoverableVideoUploadView {
        Objects.requireNonNull(sessionId, "sessionId cannot be null.");
        Objects.requireNonNull(targetEntryId, "targetEntryId cannot be null.");
        Objects.requireNonNull(classification, "classification cannot be null.");
    }
}
