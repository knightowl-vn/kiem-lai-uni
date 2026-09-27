package com.universe.wiki.contracts.dto.search;

import com.universe.wiki.domain.article.ArticleType;

import java.util.UUID;

/**
 * DTO mô tả một mục kết quả tìm kiếm điều hướng Wiki.
 *
 * Chỉ chứa thông tin siêu dữ liệu điều hướng cần thiết, không chứa content/body.
 */
public record WikiNavigationalSearchItemDTO(
        UUID id,
        ArticleType articleType,
        String title,
        String slug,
        String canonicalUrl,
        String matchedAlias
) {
}
