package com.universe.wiki.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

/**
 * Ngoại lệ ném ra khi phát hiện xung đột ghi đè đồng thời (optimistic concurrency conflict)
 * trên đóng góp bài viết Wiki.
 */
public class WikiContributionStaleMutationException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public WikiContributionStaleMutationException(UUID contributionId, long expectedVersion, long currentVersion) {
        super(
                "WIKI_CONTRIBUTION_STALE_MUTATION",
                String.format(
                        "Đóng góp bài viết Wiki [%s] đã bị thay đổi đồng thời (phiên bản hiện tại: %d, phiên bản yêu cầu: %d).",
                        contributionId,
                        currentVersion,
                        expectedVersion
                )
        );
    }

    public WikiContributionStaleMutationException(UUID contributionId, Throwable cause) {
        super(
                "WIKI_CONTRIBUTION_STALE_MUTATION",
                "Đóng góp bài viết Wiki [" + contributionId + "] đã bị thay đổi đồng thời trong giao dịch khác.",
                cause
        );
    }
}
