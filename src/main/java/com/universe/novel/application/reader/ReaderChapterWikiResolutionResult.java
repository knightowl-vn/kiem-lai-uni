package com.universe.novel.application.reader;

import java.util.List;

/**
 * Kết quả phân giải Wiki dành cho Reader sau khi áp dụng thứ tự ưu tiên 3 cấp độ.
 */
public record ReaderChapterWikiResolutionResult(
        String query,
        ChapterWikiReferenceResolutionSource source,
        boolean hasExactMatch,
        List<ReaderWikiLookupItem> items,
        Integer occurrenceIndex
) {
    public ReaderChapterWikiResolutionResult {
        if (items == null) {
            items = List.of();
        }
    }
}
