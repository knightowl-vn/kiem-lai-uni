package com.universe.screen.application.dto;

import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenStructure;

import java.util.Objects;
import java.util.UUID;

/**
 * Public DTO representing one published installment within a franchise navigation group.
 */
public record PublicScreenFranchiseInstallmentDTO(
        UUID id,
        String slug,
        String title,
        ScreenStructure structure,
        ScreenPresentation presentation,
        boolean isCurrent
) {

    public PublicScreenFranchiseInstallmentDTO {
        Objects.requireNonNull(id, "Installment ID cannot be null.");
        Objects.requireNonNull(slug, "Slug cannot be null.");
        Objects.requireNonNull(title, "Title cannot be null.");
        Objects.requireNonNull(structure, "ScreenStructure cannot be null.");
        Objects.requireNonNull(presentation, "ScreenPresentation cannot be null.");
    }
}
