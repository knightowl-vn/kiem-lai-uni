package com.universe.screen.contracts.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Contract-safe immutable projection representing public navigation destination for a ScreenTitle.
 */
public record ScreenTitleNavigationDTO(
        UUID titleId,
        String slug
) {
    public ScreenTitleNavigationDTO {
        Objects.requireNonNull(titleId, "titleId cannot be null");
        Objects.requireNonNull(slug, "slug cannot be null");
    }
}
