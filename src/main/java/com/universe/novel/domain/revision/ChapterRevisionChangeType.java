package com.universe.novel.domain.revision;

/**
 * Loại thay đổi tạo ra một snapshot revision của Chapter.
 *
 * Khớp chính xác với CHECK constraint chk_novel_chapter_revisions_change_type trong database.
 */
public enum ChapterRevisionChangeType {

    /**
     * Snapshot baseline được khởi tạo cho các Chapter đã tồn tại trước khi có tính năng revision.
     */
    BASELINE,

    /**
     * Tạo chương mới dưới dạng bản nháp.
     */
    CREATE_DRAFT,

    /**
     * Cập nhật thông tin hoặc nội dung chương khi ở trạng thái DRAFT.
     */
    UPDATE_DRAFT,

    /**
     * Di chuyển chương sang Volume khác khi ở trạng thái DRAFT.
     */
    MOVE_VOLUME,

    /**
     * Xuất bản chương (DRAFT -> PUBLISHED).
     */
    PUBLISH,

    /**
     * Gỡ xuất bản chương về bản nháp (PUBLISHED -> DRAFT).
     */
    UNPUBLISH,

    /**
     * Lưu trữ chương (DRAFT / PUBLISHED -> ARCHIVED).
     */
    ARCHIVE,

    /**
     * Khôi phục chương đã lưu trữ về bản nháp (ARCHIVED -> DRAFT).
     */
    RESTORE_TO_DRAFT,

    /**
     * Khôi phục nội dung biên tập từ một revision cũ khi chương đang ở DRAFT.
     */
    RESTORE_REVISION
}
