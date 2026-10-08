package com.universe.screen.application.dto;

import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenPublicationStatus;
import com.universe.screen.domain.ScreenStructure;

import java.util.UUID;

public record ScreenTitleSummaryDTO(
        UUID id,
        String title,
        String slug,
        ScreenStructure structure,
        ScreenPresentation presentation,
        ScreenPublicationStatus status
) {
}
