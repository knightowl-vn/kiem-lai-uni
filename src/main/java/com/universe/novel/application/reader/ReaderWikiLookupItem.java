package com.universe.novel.application.reader;

import java.util.UUID;

/**
 * Model biểu diễn bài viết Wiki cho tính năng tra cứu từ ngữ cảnh đọc truyện.
 * Thuộc sở hữu của Novel module.
 */
public record ReaderWikiLookupItem(
        UUID id,
        String title,
        String articleType,
        String slug,
        String summary
) {
}