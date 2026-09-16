package com.universe.interaction.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable value object representing a generic comment target.
 *
 * <p>Identifies the target resource using:
 * <ul>
 *   <li>{@link CommentTargetType} type</li>
 *   <li>scalar {@link UUID} targetId</li>
 * </ul>
 */
public record CommentTarget(
        CommentTargetType type,
        UUID targetId
) {

    public CommentTarget {
        Objects.requireNonNull(type, "Comment target type cannot be null.");
        Objects.requireNonNull(targetId, "Comment target ID cannot be null.");
    }

    public static CommentTarget novelChapter(UUID chapterId) {
        return new CommentTarget(CommentTargetType.NOVEL_CHAPTER, chapterId);
    }

    public static CommentTarget wikiArticle(UUID articleId) {
        return new CommentTarget(CommentTargetType.WIKI_ARTICLE, articleId);
    }
}
