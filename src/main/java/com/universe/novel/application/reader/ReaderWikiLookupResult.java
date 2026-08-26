package com.universe.novel.application.reader;

import java.util.List;

/**
 * Model kết quả tra cứu Wiki theo ngữ cảnh của Novel module.
 */
public record ReaderWikiLookupResult(
        String query,
        boolean hasExactMatch,
        List<ReaderWikiLookupItem> items
) {
}