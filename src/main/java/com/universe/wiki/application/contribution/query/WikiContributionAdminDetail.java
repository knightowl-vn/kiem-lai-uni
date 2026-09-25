package com.universe.wiki.application.contribution.query;

import com.universe.wiki.domain.contribution.WikiContribution;
import com.universe.wiki.domain.contribution.WikiContributionContextType;
import com.universe.wiki.domain.contribution.WikiContributionSource;
import com.universe.wiki.domain.contribution.WikiContributionStatus;
import com.universe.wiki.domain.contribution.WikiContributionType;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Read-model đại diện cho thông tin chi tiết đầy đủ của một đóng góp Wiki
 * dành cho trang quản trị kiểm duyệt (Admin Detail View).
 *
 * Chứa trọn vẹn:
 * - Định danh đóng góp & trạng thái & concurrency version;
 * - Ảnh chụp thông tin bài viết tại thời điểm gửi;
 * - Định danh người gửi (submittedByUserId);
 * - Toàn văn thông điệp đóng góp;
 * - Bằng chứng trích dẫn văn bản (TEXT_SELECTION);
 * - Danh sách nguồn tham khảo đầy đủ (sắp xếp tăng dần theo sourceOrder);
 * - Dữ liệu quyết định kiểm duyệt (nếu đã kết thúc).
 */
public record WikiContributionAdminDetail(
        UUID contributionId,
        UUID articleId,
        String articleTypeSnapshot,
        String articleTitleSnapshot,
        String articleSlugSnapshot,
        long articleContentVersion,
        UUID submittedByUserId,
        WikiContributionContextType contextType,
        WikiContributionType contributionType,
        String message,
        String selectedText,
        String selectedPrefix,
        String selectedSuffix,
        String selectedHeadingAnchor,
        WikiContributionStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt,
        List<WikiContributionSource> sources,
        String resolutionNote,
        UUID resolvedByUserId,
        Instant resolvedAt,
        Long resolvedArticleContentVersion
) {
    public WikiContributionAdminDetail {
        Objects.requireNonNull(contributionId, "contributionId cannot be null");
        Objects.requireNonNull(articleId, "articleId cannot be null");
        Objects.requireNonNull(submittedByUserId, "submittedByUserId cannot be null");
        Objects.requireNonNull(contextType, "contextType cannot be null");
        Objects.requireNonNull(contributionType, "contributionType cannot be null");
        Objects.requireNonNull(message, "message cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        Objects.requireNonNull(createdAt, "createdAt cannot be null");
        Objects.requireNonNull(updatedAt, "updatedAt cannot be null");
        sources = sources != null ? Collections.unmodifiableList(sources) : List.of();
    }

    public static WikiContributionAdminDetail from(
            WikiContribution contribution,
            List<WikiContributionSource> sources
    ) {
        Objects.requireNonNull(contribution, "contribution cannot be null");
        return new WikiContributionAdminDetail(
                contribution.getId(),
                contribution.getArticleId(),
                contribution.getArticleTypeSnapshot(),
                contribution.getArticleTitleSnapshot(),
                contribution.getArticleSlugSnapshot(),
                contribution.getArticleContentVersion(),
                contribution.getSubmittedByUserId(),
                contribution.getContextType(),
                contribution.getContributionType(),
                contribution.getMessage(),
                contribution.getSelectedText(),
                contribution.getSelectedPrefix(),
                contribution.getSelectedSuffix(),
                contribution.getSelectedHeadingAnchor(),
                contribution.getStatus(),
                contribution.getVersion(),
                contribution.getCreatedAt(),
                contribution.getUpdatedAt(),
                sources,
                contribution.getResolutionNote(),
                contribution.getResolvedByUserId(),
                contribution.getResolvedAt(),
                contribution.getResolvedArticleContentVersion()
        );
    }
}
