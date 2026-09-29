package com.universe.wiki.contracts.interfaces;

import com.universe.wiki.contracts.dto.search.WikiNavigationalSearchResultDTO;

/**
 * Public Contract cho việc tìm kiếm điều hướng (Navigational Search) Wiki.
 *
 * Cho phép tìm kiếm bài viết Wiki đã xuất bản (PUBLISHED) theo tiêu đề chính thức
 * và danh xưng/biệt danh (aliases), không tìm kiếm thân bài (body).
 */
public interface WikiNavigationalSearchContract {

    int DEFAULT_LIMIT = 20;
    int MAX_LIMIT = 20;

    /**
     * Tìm kiếm điều hướng bài viết Wiki đã xuất bản với số lượng kết quả tối đa tùy chọn.
     *
     * @param query từ khóa tìm kiếm người dùng nhập
     * @param limit số lượng kết quả tối đa mong muốn (tối đa 20)
     * @return danh sách kết quả điều hướng đã được xếp hạng và loại trừ trùng lặp
     */
    WikiNavigationalSearchResultDTO search(String query, int limit);

    /**
     * Tìm kiếm điều hướng bài viết Wiki đã xuất bản với giới hạn mặc định (20 kết quả).
     *
     * @param query từ khóa tìm kiếm người dùng nhập
     * @return danh sách kết quả điều hướng đã được xếp hạng và loại trừ trùng lặp
     */
    default WikiNavigationalSearchResultDTO search(String query) {
        return search(query, DEFAULT_LIMIT);
    }
}
