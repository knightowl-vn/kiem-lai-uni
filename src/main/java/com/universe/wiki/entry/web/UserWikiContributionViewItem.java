package com.universe.wiki.entry.web;

import com.universe.wiki.contracts.dto.WikiArticleListItemDTO;
import com.universe.wiki.contracts.dto.contribution.UserWikiContributionItemDTO;
import com.universe.wiki.domain.article.ArticleStatus;
import com.universe.wiki.domain.article.ArticleType;
import com.universe.wiki.contracts.path.ArticleTypePathMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * View model cho từng đóng góp Wiki cá nhân trong danh sách hiển thị.
 *
 * Nguyên tắc bất biến:
 * 1. Lịch sử đóng góp bảo tồn đầy đủ dữ liệu snapshot gốc (tiêu đề, slug, loại bài, nội dung, phản hồi, thời gian);
 * 2. Điều hướng bài viết (link) CHỈ được kích hoạt khi bài viết hiện tại tồn tại và ở trạng thái PUBLISHED hợp lệ;
 * 3. Khi điều hướng hợp lệ, URL sử dụng slug và loại bài trực tiếp (live) từ bài viết hiện tại;
 * 4. Không để rò rỉ logic xuất bản (publication policy) ra ngoài template Thymeleaf.
 */
public record UserWikiContributionViewItem(
        UUID id,
        UUID articleId,
        String articleTitleSnapshot,
        String articleSlugSnapshot,
        String articleTypeSnapshot,
        String contextType,
        String contributionType,
        String contributionTypeLabel,
        String message,
        String status,
        String statusLabel,
        String statusBadgeClass,
        Instant createdAt,
        String resolutionNote,
        Instant resolvedAt,
        boolean articleLinkAvailable,
        String liveArticleSlug,
        String liveArticleTypePath
) {

    public boolean hasArticleLink() {
        return articleLinkAvailable;
    }

    public static UserWikiContributionViewItem from(
            UserWikiContributionItemDTO dto,
            WikiArticleListItemDTO liveArticle,
            ArticleTypePathMapper articleTypePathMapper
    ) {
        if (dto == null) {
            return null;
        }

        boolean linkAvailable = false;
        String liveSlug = null;
        String liveTypePath = null;

        if (liveArticle != null && articleTypePathMapper != null) {
            boolean isPublished = ArticleStatus.PUBLISHED.name().equals(liveArticle.status());
            boolean hasValidSlug = liveArticle.slug() != null && !liveArticle.slug().isBlank();

            if (isPublished && hasValidSlug && liveArticle.articleType() != null) {
                try {
                    ArticleType type = ArticleType.valueOf(liveArticle.articleType());
                    liveTypePath = articleTypePathMapper.toPath(type);
                    liveSlug = liveArticle.slug().trim();
                    linkAvailable = liveTypePath != null && !liveSlug.isEmpty();
                } catch (IllegalArgumentException | NullPointerException ignored) {
                    linkAvailable = false;
                    liveTypePath = null;
                    liveSlug = null;
                }
            }
        }

        String statusLabel = resolveStatusLabel(dto.status());
        String statusBadgeClass = resolveStatusBadgeClass(dto.status());
        String contributionTypeLabel = resolveContributionTypeLabel(dto.contributionType());

        return new UserWikiContributionViewItem(
                dto.id(),
                dto.articleId(),
                dto.articleTitleSnapshot(),
                dto.articleSlugSnapshot(),
                dto.articleTypeSnapshot(),
                dto.contextType(),
                dto.contributionType(),
                contributionTypeLabel,
                dto.message(),
                dto.status(),
                statusLabel,
                statusBadgeClass,
                dto.createdAt(),
                dto.resolutionNote(),
                dto.resolvedAt(),
                linkAvailable,
                liveSlug,
                liveTypePath
        );
    }

    private static String resolveStatusLabel(String status) {
        if (status == null) {
            return "Không xác định";
        }
        return switch (status) {
            case "NEW" -> "Đang chờ duyệt";
            case "REVIEWING" -> "Đang kiểm duyệt";
            case "RESOLVED" -> "Đã duyệt";
            case "REJECTED" -> "Bị từ chối";
            default -> status;
        };
    }

    private static String resolveStatusBadgeClass(String status) {
        if (status == null) {
            return "wiki-contribution-badge--neutral";
        }
        return switch (status) {
            case "NEW" -> "wiki-contribution-badge--pending";
            case "REVIEWING" -> "wiki-contribution-badge--reviewing";
            case "RESOLVED" -> "wiki-contribution-badge--resolved";
            case "REJECTED" -> "wiki-contribution-badge--rejected";
            default -> "wiki-contribution-badge--neutral";
        };
    }

    private static String resolveContributionTypeLabel(String type) {
        if (type == null) {
            return "Đóng góp";
        }
        return switch (type) {
            case "INCORRECT_INFORMATION" -> "Thông tin chưa chính xác";
            case "MISSING_INFORMATION" -> "Bổ sung thông tin";
            case "OUTDATED_INFORMATION" -> "Thông tin đã cũ";
            case "WORDING" -> "Diễn đạt / Chính tả";
            case "SOURCE_REFERENCE" -> "Nguồn tham khảo";
            case "OTHER" -> "Đóng góp khác";
            default -> type;
        };
    }
}
