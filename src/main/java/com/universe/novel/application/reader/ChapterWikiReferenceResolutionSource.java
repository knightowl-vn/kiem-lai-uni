package com.universe.novel.application.reader;

/**
 * Nguồn phân giải kết quả tra cứu Wiki cho người đọc theo thứ tự ưu tiên 3 cấp độ.
 */
public enum ChapterWikiReferenceResolutionSource {

    /**
     * Khớp liên kết chính xác theo vị trí xuất hiện trong chương (Priority 1).
     */
    OCCURRENCE_BINDING,

    /**
     * Khớp liên kết theo phạm vi toàn chương (Priority 2).
     */
    CHAPTER_WIDE_BINDING,

    /**
     * Tra cứu theo từ điển ngữ cảnh Wiki toàn cục (Priority 3).
     */
    GLOBAL_LOOKUP
}
