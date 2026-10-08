package com.universe.screen.application.commands;

import java.util.UUID;

public record AddScreenFranchiseMemberCommand(
        UUID franchiseId,
        UUID titleId,
        Integer displayOrder
) {
}
