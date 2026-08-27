package com.universe.novel.application.chapter.reference;

/**
 * Trạng thái hiệu lực của liên kết tham chiếu Wiki trong Chapter.
 */
public enum ChapterWikiReferenceStatus {

    /**
     * Liên kết đang hiệu lực (CHAPTER_WIDE luôn ACTIVE, hoặc OCCURRENCE_SPECIFIC khớp contentVersion hiện tại).
     */
    ACTIVE,

    /**
     * Liên kết có thể đã bị trôi dạt do nội dung chương đã được chỉnh sửa (contentVersion thay đổi).
     */
    STALE
}
