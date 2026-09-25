package com.universe.wiki.application.contribution.query;

import com.universe.wiki.domain.contribution.WikiContribution;
import com.universe.wiki.domain.contribution.WikiContributionContextType;
import com.universe.wiki.domain.contribution.WikiContributionResolutionOutcome;
import com.universe.wiki.domain.contribution.WikiContributionSource;
import com.universe.wiki.domain.contribution.WikiContributionStatus;
import com.universe.wiki.domain.contribution.WikiContributionType;
import com.universe.wiki.domain.contribution.WikiContributionWorkflowEvent;

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
 * - Dữ liệu quyết định kiểm duyệt (nếu đã kết thúc);
 * - Thông tin phân công và tiến trình kiểm duyệt đa quản trị viên;
 * - Danh sách sự kiện kiểm toán quy trình bất biến (workflow events).
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
        Long resolvedArticleContentVersion,
        UUID assignedToUserId,
        Instant assignedAt,
        UUID reviewStartedByUserId,
        Instant reviewStartedAt,
        Long reviewStartedArticleContentVersion,
        WikiContributionResolutionOutcome resolutionOutcome,
        List<WikiContributionWorkflowEvent> events
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
        events = events != null ? Collections.unmodifiableList(events) : List.of();
    }

    public WikiContributionAdminDetail(
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
        this(contributionId, articleId, articleTypeSnapshot, articleTitleSnapshot, articleSlugSnapshot,
                articleContentVersion, submittedByUserId, contextType, contributionType, message,
                selectedText, selectedPrefix, selectedSuffix, selectedHeadingAnchor, status, version,
                createdAt, updatedAt, sources, resolutionNote, resolvedByUserId, resolvedAt,
                resolvedArticleContentVersion, null, null, null, null, null, null, List.of());
    }

    public static WikiContributionAdminDetail from(
            WikiContribution contribution,
            List<WikiContributionSource> sources,
            List<WikiContributionWorkflowEvent> events
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
                contribution.getResolvedArticleContentVersion(),
                contribution.getAssignedToUserId(),
                contribution.getAssignedAt(),
                contribution.getReviewStartedByUserId(),
                contribution.getReviewStartedAt(),
                contribution.getReviewStartedArticleContentVersion(),
                contribution.getResolutionOutcome(),
                events
        );
    }

    public static WikiContributionAdminDetail from(
            WikiContribution contribution,
            List<WikiContributionSource> sources
    ) {
        return from(contribution, sources, List.of());
    }
}
