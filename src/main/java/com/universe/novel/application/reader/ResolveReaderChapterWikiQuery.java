package com.universe.novel.application.reader;

import java.util.UUID;

/**
 * Query truyền vào Use Case phân giải Wiki cho người đọc.
 */
public record ResolveReaderChapterWikiQuery(
        UUID chapterId,
        String selectedTerm,
        Integer occurrenceIndex
) {
}
