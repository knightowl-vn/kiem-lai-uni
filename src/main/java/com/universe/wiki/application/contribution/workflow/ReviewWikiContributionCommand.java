package com.universe.wiki.application.contribution.workflow;

import java.util.Objects;
import java.util.UUID;

/**
 * Command yêu cầu chuyển trạng thái đóng góp sang REVIEWING.
 */
public record ReviewWikiContributionCommand(
        UUID contributionId,
        UUID actorId,
        long expectedVersion
) {
    public ReviewWikiContributionCommand {
        Objects.requireNonNull(contributionId, "ID đóng góp không được để trống.");
        Objects.requireNonNull(actorId, "ID quản trị viên không được để trống.");
        if (expectedVersion < 0L) {
            throw new IllegalArgumentException("Expected version không được nhỏ hơn 0.");
        }
    }
}
