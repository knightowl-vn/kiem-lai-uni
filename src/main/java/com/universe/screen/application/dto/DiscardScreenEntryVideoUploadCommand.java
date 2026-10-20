package com.universe.screen.application.dto;

import java.util.UUID;

/**
 * Command carrying parameters for guarded discard of a completed video upload.
 *
 * <p>Supports both target-nested discard (where {@code titleId}, {@code entryId},
 * and {@code videoMediaAssetId} are supplied and validated against the session) and
 * session-rooted discard (where {@code titleId} and {@code entryId} are omitted/nullable
 * and resolved from the authoritative server-side upload session).
 */
public record DiscardScreenEntryVideoUploadCommand(
        UUID titleId,
        UUID entryId,
        UUID sessionId,
        UUID videoMediaAssetId,
        String authenticatedActor
) {

    public static DiscardScreenEntryVideoUploadCommand forSession(
            UUID sessionId,
            UUID videoMediaAssetId,
            String authenticatedActor
    ) {
        return new DiscardScreenEntryVideoUploadCommand(null, null, sessionId, videoMediaAssetId, authenticatedActor);
    }

    public static DiscardScreenEntryVideoUploadCommand forSession(
            UUID sessionId,
            String authenticatedActor
    ) {
        return new DiscardScreenEntryVideoUploadCommand(null, null, sessionId, null, authenticatedActor);
    }
}
