package com.universe.novel.application.wiki.lookup;

import java.util.List;

/**
 * Model trung lập của Novel application đại diện cho kết quả tra cứu bài viết Wiki theo ngữ cảnh.
 */
public record WikiContextualLookupResult(
        String query,
        boolean hasExactMatch,
        List<WikiContextualLookupItem> items
) {
    public WikiContextualLookupResult {
        if (items == null) {
            items = List.of();
        }
    }

    public static WikiContextualLookupResult empty(String query) {
        return new WikiContextualLookupResult(query != null ? query : "", false, List.of());
    }
}
