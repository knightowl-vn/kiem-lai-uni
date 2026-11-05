package com.universe.screen.contracts.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Contract-safe immutable projection representing a ScreenTitle target for Admin comment reporting.
 */
public record ScreenTitleReportTargetDTO(
        UUID titleId,
        String title,
        String slug,
        String status,
        boolean published
) {
    public ScreenTitleReportTargetDTO {
        Objects.requireNonNull(titleId, "titleId cannot be null");
    }
}
