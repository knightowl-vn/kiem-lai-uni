package com.universe.wiki.domain.revision;

import com.universe.wiki.domain.article.ArticleStatus;
import com.universe.wiki.domain.article.ArticleType;
import com.universe.wiki.domain.article.Slug;
import com.universe.wiki.domain.article.WikiArticle;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Snapshot bất biến của một WikiArticle tại một thời điểm.
 *
 * Mỗi lần bài viết được lưu chính thức, một snapshot mới
 * sẽ được thêm vào bảng wiki_article_revisions.
 */
public record WikiArticleRevision(
        UUID id,
        UUID articleId,
        long revisionNumber,
        String title,
        Slug slug,
        ArticleType articleType,
        String summary,
        String content,
        ArticleStatus status,
        RevisionChangeType changeType,
        String editSummary,
        UUID editedBy,
        Instant createdAt
) {

    private static final int MAX_EDIT_SUMMARY_LENGTH =
            500;

    public WikiArticleRevision {
        id =
                Objects.requireNonNull(
                        id,
                        "Revision ID không được để trống."
                );

        articleId =
                Objects.requireNonNull(
                        articleId,
                        "Article ID không được để trống."
                );

        if (revisionNumber < 1L) {
            throw new IllegalArgumentException(
                    "Revision number phải lớn hơn hoặc bằng 1."
            );
        }

        title =
                requireText(
                        title,
                        "Tiêu đề revision không được để trống."
                );

        slug =
                Objects.requireNonNull(
                        slug,
                        "Slug revision không được để trống."
                );

        articleType =
                Objects.requireNonNull(
                        articleType,
                        "Article type của revision không được để trống."
                );

        summary =
                summary == null
                        ? ""
                        : summary.trim();

        content =
                content == null
                        ? ""
                        : content.trim();

        status =
                Objects.requireNonNull(
                        status,
                        "Article status của revision không được để trống."
                );

        changeType =
                Objects.requireNonNull(
                        changeType,
                        "Change type không được để trống."
                );

        editSummary =
                normalizeEditSummary(
                        editSummary
                );

        editedBy =
                Objects.requireNonNull(
                        editedBy,
                        "Người chỉnh sửa không được để trống."
                );

        createdAt =
                Objects.requireNonNull(
                        createdAt,
                        "Thời gian tạo revision không được để trống."
                );
    }

    /**
     * Tạo snapshot từ trạng thái mới nhất của Aggregate.
     *
     * revisionNumber dùng aggregateVersion hiện tại.
     * editedBy và createdAt dùng thông tin lần cập nhật gần nhất
     * được WikiArticle quản lý.
     */
    public static WikiArticleRevision createSnapshot(
            UUID revisionId,
            WikiArticle article,
            RevisionChangeType changeType,
            String editSummary
    ) {
        Objects.requireNonNull(
                article,
                "Wiki article không được để trống."
        );

        UUID editedBy =
                Objects.requireNonNull(
                        article.getUpdatedBy(),
                        "Wiki article phải có người cập nhật."
                );

        Instant revisionCreatedAt =
                Objects.requireNonNull(
                        article.getUpdatedAt(),
                        "Wiki article phải có thời gian cập nhật."
                );

        return new WikiArticleRevision(
                revisionId,
                article.getId(),
                article.getAggregateVersion(),
                article.getTitle(),
                article.getSlug(),
                article.getArticleType(),
                article.getSummary(),
                article.getContent(),
                article.getStatus(),
                changeType,
                editSummary,
                editedBy,
                revisionCreatedAt
        );
    }

    private static String requireText(
            String value,
            String errorMessage
    ) {
        if (value == null
                || value.isBlank()) {

            throw new IllegalArgumentException(
                    errorMessage
            );
        }

        return value.trim();
    }

    private static String normalizeEditSummary(
            String editSummary
    ) {
        if (editSummary == null
                || editSummary.isBlank()) {

            return null;
        }

        String normalizedValue =
                editSummary.trim();

        if (normalizedValue.length()
                > MAX_EDIT_SUMMARY_LENGTH) {

            throw new IllegalArgumentException(
                    "Mô tả chỉnh sửa không được vượt quá "
                            + MAX_EDIT_SUMMARY_LENGTH
                            + " ký tự."
            );
        }

        return normalizedValue;
    }
}