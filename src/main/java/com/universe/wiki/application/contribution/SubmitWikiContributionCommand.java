package com.universe.wiki.application.contribution;

import java.util.List;
import java.util.UUID;

/**
 * Lệnh gửi đóng góp bài viết Wiki từ người dùng đã xác thực.
 */
public record SubmitWikiContributionCommand(
        UUID articleId,
        UUID submittedByUserId,
        Long articleContentVersion,
        String contextType,
        String contributionType,
        String message,
        String selectedText,
        String selectedPrefix,
        String selectedSuffix,
        String selectedHeadingAnchor,
        List<String> sources
) {
}
