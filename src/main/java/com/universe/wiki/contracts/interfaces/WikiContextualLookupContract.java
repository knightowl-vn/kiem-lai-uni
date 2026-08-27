package com.universe.wiki.contracts.interfaces;

import com.universe.wiki.contracts.dto.WikiContextualLookupResultDTO;

/**
 * Public Contract cho tra cứu Wiki theo ngữ cảnh từ văn bản được chọn.
 *
 * Cho phép Novel hoặc các module khác tra cứu bài viết Wiki đã xuất bản
 * mà không cần phụ thuộc trực tiếp vào Domain, Repository hay JPA Entity của Wiki.
 */
public interface WikiContextualLookupContract {

    /**
     * Tra cứu bài viết Wiki đã xuất bản theo tiêu đề phù hợp với văn bản được chọn.
     *
     * Giới hạn tối đa 5 kết quả và chỉ áp dụng cho bài viết có trạng thái PUBLISHED.
     *
     * @param query từ khóa / văn bản cần tra cứu
     * @return kết quả tra cứu chứa danh sách bài viết phù hợp (tối đa 5 bài, chỉ PUBLISHED)
     */
    WikiContextualLookupResultDTO lookupByTitle(String query);
}
