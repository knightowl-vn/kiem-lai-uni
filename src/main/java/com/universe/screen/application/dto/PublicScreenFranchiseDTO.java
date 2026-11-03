package com.universe.screen.application.dto;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Public DTO representing franchise navigation context for a Screen title.
 */
public record PublicScreenFranchiseDTO(
        UUID id,
        String name,
        String slug,
        List<PublicScreenFranchiseInstallmentDTO> installments
) {

    public PublicScreenFranchiseDTO {
        Objects.requireNonNull(id, "Franchise ID cannot be null.");
        Objects.requireNonNull(name, "Franchise name cannot be null.");
        Objects.requireNonNull(slug, "Franchise slug cannot be null.");
        installments = installments == null ? List.of() : List.copyOf(installments);
    }
}
