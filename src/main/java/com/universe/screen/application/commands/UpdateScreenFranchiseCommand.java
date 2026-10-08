package com.universe.screen.application.commands;

import java.util.UUID;

public record UpdateScreenFranchiseCommand(
        UUID franchiseId,
        String name,
        String slug
) {
}
