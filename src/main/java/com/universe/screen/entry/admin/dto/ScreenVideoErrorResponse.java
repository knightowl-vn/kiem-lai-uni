package com.universe.screen.entry.admin.dto;

import java.util.Objects;

/**
 * Explicit, sanitized error response DTO for Screen video admin operations.
 * Prevents direct exception serialization and hides internal system details.
 */
public record ScreenVideoErrorResponse(
        String error,
        String message
) {
    public ScreenVideoErrorResponse {
        Objects.requireNonNull(error, "error cannot be null.");
        Objects.requireNonNull(message, "message cannot be null.");
    }
}
