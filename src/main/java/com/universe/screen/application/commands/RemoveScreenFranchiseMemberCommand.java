package com.universe.screen.application.commands;

import java.util.UUID;

public record RemoveScreenFranchiseMemberCommand(
        UUID franchiseId,
        UUID membershipId
) {
}
