package com.universe.wiki.application.contribution.workflow;

import java.util.Objects;
import java.util.UUID;

/**
 * Command yêu cầu phân công lại đóng góp đang xem xét cho quản trị viên khác (SUPER_ADMIN only).
 */
public record ReassignWikiContributionCommand(
        UUID contributionId,
        UUID actorId,
        UUID targetUserId,
        String reason,
        long expectedVersion
) {
    public static final int MIN_REASON_LENGTH = 5;
    public static final int MAX_REASON_LENGTH = 500;

    public ReassignWikiContributionCommand {
        Objects.requireNonNull(contributionId, "ID đóng góp không được để trống.");
        Objects.requireNonNull(actorId, "ID quản trị viên thao tác không được để trống.");
        Objects.requireNonNull(targetUserId, "ID quản trị viên nhận phân công không được để trống.");
        if (expectedVersion < 0L) {
            throw new IllegalArgumentException("Expected version không được nhỏ hơn 0.");
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("Lý do phân công lại không được để trống.");
        }
        String trimmedReason = reason.trim();
        if (trimmedReason.length() < MIN_REASON_LENGTH || trimmedReason.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException(
                    String.format("Độ dài lý do phân công lại phải từ %d đến %d ký tự.", MIN_REASON_LENGTH, MAX_REASON_LENGTH)
            );
        }
    }
}
