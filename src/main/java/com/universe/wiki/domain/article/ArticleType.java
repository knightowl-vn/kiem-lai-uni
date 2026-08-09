package com.universe.wiki.domain.article;

/**
 * Loại nội dung cấp cao được quản lý bởi Wiki Module.
 *
 * Loại bài viết trở thành bất biến sau khi bài đã được xuất bản.
 */
public enum ArticleType {

    /**
     * Nhân vật.
     */
    CHARACTER,

    /**
     * Cảnh giới tu luyện cụ thể.
     */
    REALM,

    /**
     * Con đường hoặc hệ thống tu hành.
     */
    CULTIVATION_PATH,

    /**
     * Thế lực, tông môn, gia tộc hoặc tổ chức.
     */
    FACTION,

    /**
     * Vật phẩm, pháp bảo, binh khí hoặc vật thể quan trọng.
     */
    ITEM,

    /**
     * Công pháp, kiếm pháp, quyền pháp hoặc thần thông.
     */
    TECHNIQUE,

    /**
     * Địa điểm nằm trong một thế giới.
     */
    LOCATION,

    /**
     * Tòa thiên hạ, tiểu thế giới, động thiên,
     * phúc địa hoặc cõi độc lập.
     */
    WORLD,

    /**
     * Sự kiện thuộc dòng thời gian.
     */
    TIMELINE_EVENT
}