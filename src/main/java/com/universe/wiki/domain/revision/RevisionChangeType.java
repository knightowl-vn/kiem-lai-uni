package com.universe.wiki.domain.revision;

/**
 * Loại thay đổi đã tạo ra một revision của bài viết Wiki.
 *
 * Các giá trị phải khớp với constraint
 * chk_wiki_article_revisions_change_type trong database.
 */
public enum RevisionChangeType {

    /**
     * Tạo bài viết dưới dạng bản nháp.
     */
    CREATE_DRAFT,

    /**
     * Cập nhật bài viết khi vẫn còn là bản nháp.
     */
    UPDATE_DRAFT,
    /**
     * Cập nhật bản nháp và xuất bản trong cùng một hành động.
     */
    UPDATE_AND_PUBLISH,
    /**
     * Tạo và xuất bản trong cùng một hành động. 
     */
    
    CREATE_AND_PUBLISH,

    /**
     * Xuất bản bài viết lần đầu.
     */
    PUBLISH,
    
    /**
     * Gỡ xuất bản bài viết.
     */
    
    UNPUBLISH,

    /**
     * Cập nhật nội dung của bài viết đã xuất bản.
     */
    UPDATE_PUBLISHED,

    /**
     * Lưu trữ bài viết.
     */
    ARCHIVE,

    /**
     * Khôi phục nội dung từ một revision trước đó.
     */
    RESTORE
}	