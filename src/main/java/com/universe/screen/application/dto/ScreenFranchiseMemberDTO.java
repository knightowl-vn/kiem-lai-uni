package com.universe.screen.application.dto;

import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenPublicationStatus;
import com.universe.screen.domain.ScreenStructure;

import java.util.UUID;

public record ScreenFranchiseMemberDTO(
        UUID membershipId,
        UUID franchiseId,
        UUID titleId,
        String title,
        String titleSlug,
        int displayOrder,
        ScreenStructure structure,
        ScreenPresentation presentation,
        ScreenPublicationStatus status
) {
}
