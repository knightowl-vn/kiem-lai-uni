package com.universe.novel.domain.reference;

/**
 * Phạm vi áp dụng của liên kết tham chiếu Wiki trong Chapter.
 */
public enum ChapterWikiReferenceScope {

    /**
     * Áp dụng cho toàn bộ chương (mặc định cho mọi lần xuất hiện của thuật ngữ).
     * Yêu cầu occurrenceIndex = 0 và boundContentVersion = null.
     */
    CHAPTER_WIDE,

    /**
     * Áp dụng riêng cho một lần xuất hiện cụ thể của thuật ngữ trong chương.
     * Yêu cầu occurrenceIndex >= 1 và boundContentVersion != null.
     */
    OCCURRENCE_SPECIFIC
}
