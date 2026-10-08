package com.universe.screen.application.commands;

import java.util.UUID;

public record UpdateScreenFranchiseMemberOrderCommand(
        UUID franchiseId,
        UUID membershipId,
        int displayOrder
) {
}
