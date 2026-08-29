package com.universe.novel.application.chapter.reference;

import java.util.List;

/**
 * DTO bao gói danh sách kết quả bài viết Wiki mục tiêu phục vụ giao diện gán liên kết Wiki phía Admin (MS-02.8.1 Step 6D1).
 */
public record TargetWikiArticleSearchResultDTO(
        String query,
        List<TargetWikiArticleSearchItemDTO> items
) {
    public TargetWikiArticleSearchResultDTO {
        if (items == null) {
            items = List.of();
        }
    }

    public static TargetWikiArticleSearchResultDTO empty(String query) {
        return new TargetWikiArticleSearchResultDTO(query != null ? query : "", List.of());
    }
}
