package com.universe.interaction.application.query;

import com.universe.interaction.domain.CommentTargetType;

import java.util.Locale;
import java.util.Set;

/**
 * Filter enum for scoping authored comments by supported content context.
 */
public enum UserCommentContextFilter {
    ALL("all", "Tất cả", Set.of(CommentTargetType.NOVEL_CHAPTER, CommentTargetType.WIKI_ARTICLE)),
    NOVEL("novel", "Novel", Set.of(CommentTargetType.NOVEL_CHAPTER)),
    WIKI("wiki", "Wiki", Set.of(CommentTargetType.WIKI_ARTICLE));

    private final String queryParam;
    private final String label;
    private final Set<CommentTargetType> targetTypes;

    UserCommentContextFilter(String queryParam, String label, Set<CommentTargetType> targetTypes) {
        this.queryParam = queryParam;
        this.label = label;
        this.targetTypes = targetTypes;
    }

    public String queryParam() {
        return queryParam;
    }

    public String label() {
        return label;
    }

    public Set<CommentTargetType> targetTypes() {
        return targetTypes;
    }

    public static UserCommentContextFilter fromQueryParam(String param) {
        if (param == null || param.isBlank()) {
            return ALL;
        }
        String normalized = param.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "novel" -> NOVEL;
            case "wiki" -> WIKI;
            default -> ALL;
        };
    }
}
