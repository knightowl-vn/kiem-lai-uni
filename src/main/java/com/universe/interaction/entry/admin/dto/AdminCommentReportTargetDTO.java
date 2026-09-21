package com.universe.interaction.entry.admin.dto;

import com.universe.interaction.domain.CommentTargetType;
import com.universe.novel.contracts.dto.ChapterListItemDTO;
import com.universe.wiki.contracts.dto.WikiArticleListItemDTO;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable target projection for the Admin comment report queue.
 *
 * <p>Represents the target entity where the reported comment was posted
 * (e.g. Novel chapter or Wiki article). If the target entity could not
 * be resolved from the target bounded context, {@code resolved} is {@code false}
 * and target-specific fields are {@code null}.
 *
 * @param targetType bounded context target type (never null)
 * @param targetId scalar unique identifier of the target (never null)
 * @param resolved whether target display metadata was successfully resolved
 * @param title target title (chapter title or wiki article title, nullable)
 * @param slug target slug (chapter slug or wiki article slug, nullable)
 * @param currentStatus target publication/lifecycle status (nullable)
 * @param chapterNumber chapter sequence number if Novel chapter (nullable)
 * @param articleType wiki article type if Wiki article (nullable)
 */
public record AdminCommentReportTargetDTO(
        CommentTargetType targetType,
        UUID targetId,
        boolean resolved,
        String title,
        String slug,
        String currentStatus,
        Integer chapterNumber,
        String articleType
) {
    public AdminCommentReportTargetDTO {
        Objects.requireNonNull(targetType, "targetType cannot be null");
        Objects.requireNonNull(targetId, "targetId cannot be null");
    }

    public static AdminCommentReportTargetDTO forNovelChapter(UUID targetId, ChapterListItemDTO chapter) {
        Objects.requireNonNull(targetId, "targetId cannot be null");
        if (chapter != null) {
            return new AdminCommentReportTargetDTO(
                    CommentTargetType.NOVEL_CHAPTER,
                    targetId,
                    true,
                    chapter.title(),
                    chapter.slug(),
                    chapter.status(),
                    chapter.chapterNumber(),
                    null
            );
        }
        return unresolved(CommentTargetType.NOVEL_CHAPTER, targetId);
    }

    public static AdminCommentReportTargetDTO forWikiArticle(UUID targetId, WikiArticleListItemDTO article) {
        Objects.requireNonNull(targetId, "targetId cannot be null");
        if (article != null) {
            return new AdminCommentReportTargetDTO(
                    CommentTargetType.WIKI_ARTICLE,
                    targetId,
                    true,
                    article.title(),
                    article.slug(),
                    article.status(),
                    null,
                    article.articleType()
            );
        }
        return unresolved(CommentTargetType.WIKI_ARTICLE, targetId);
    }

    public static AdminCommentReportTargetDTO unresolved(CommentTargetType targetType, UUID targetId) {
        return new AdminCommentReportTargetDTO(
                targetType,
                targetId,
                false,
                null,
                null,
                null,
                null,
                null
        );
    }
}
