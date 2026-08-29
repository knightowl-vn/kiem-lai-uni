package com.universe.novel.application.chapter.reference;

import java.util.UUID;

/**
 * DTO đại diện cho một bài viết Wiki mục tiêu được trả về từ kết quả tìm kiếm phía Admin (MS-02.8.1 Step 6D1).
 */
public record TargetWikiArticleSearchItemDTO(
        UUID id,
        String title,
        String articleType,
        String slug,
        String summary,
        String matchedAlias
) {
}
