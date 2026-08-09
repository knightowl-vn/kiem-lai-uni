package com.universe.wiki.domain.world;

/**
 * Phân loại các không gian và thế giới trong Wiki.
 *
 * ArticleType.WORLD xác định đây là một bài viết về thế giới.
 * WorldType xác định thế giới đó thuộc loại nào.
 */
public enum WorldType {

    /**
     * Tòa thiên hạ hoặc thế giới lớn hoàn chỉnh.
     */
    GREAT_WORLD,

    /**
     * Tiểu thế giới tồn tại độc lập hoặc phụ thuộc
     * vào một thế giới lớn hơn.
     */
    SMALL_WORLD,

    /**
     * Động thiên.
     */
    CAVE_HEAVEN,

    /**
     * Phúc địa.
     */
    BLESSED_LAND,

    /**
     * Bí cảnh hoặc không gian biệt lập.
     */
    SECRET_REALM,

    /**
     * Cõi đặc biệt chưa thuộc các nhóm trên.
     */
    OTHER_REALM
}