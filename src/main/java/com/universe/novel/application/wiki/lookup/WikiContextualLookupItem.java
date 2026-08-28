package com.universe.novel.application.wiki.lookup;

import java.util.UUID;

/**
 * Model trung lập của Novel application đại diện cho một bài viết Wiki trong kết quả tra cứu ngữ cảnh.
 */
public record WikiContextualLookupItem(
        UUID id,
        String title,
        String articleType,
        String slug,
        String summary,
        String matchedAlias
) {
    public WikiContextualLookupItem(
            UUID id,
            String title,
            String articleType,
            String slug,
            String summary
    ) {
        this(id, title, articleType, slug, summary, null);
    }
}
