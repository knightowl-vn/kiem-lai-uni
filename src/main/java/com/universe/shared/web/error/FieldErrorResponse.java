package com.universe.shared.web.error;

public record FieldErrorResponse(
        String field,
        String message
) {
}