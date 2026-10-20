package com.universe.screen.entry.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Objects;
import java.util.UUID;

/**
 * Explicit, sanitized error response DTO for Screen video admin operations.
 * Prevents direct exception serialization and hides internal system details.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScreenVideoErrorResponse(
        String error,
        String message,
        UUID sessionId,
        UUID videoMediaAssetId
) {
    public ScreenVideoErrorResponse(String error, String message) {
        this(error, message, null, null);
    }

    public ScreenVideoErrorResponse {
        Objects.requireNonNull(error, "error cannot be null.");
        Objects.requireNonNull(message, "message cannot be null.");
    }
}
