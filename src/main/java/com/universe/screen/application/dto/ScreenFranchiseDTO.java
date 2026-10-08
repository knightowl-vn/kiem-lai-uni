package com.universe.screen.application.dto;

import java.util.UUID;

public record ScreenFranchiseDTO(
        UUID id,
        String name,
        String slug,
        int memberCount
) {
}
