package com.universe.wiki.contracts.dto;

import java.util.UUID;

/**
 * DTO dữ liệu rút gọn bài viết Wiki cho tính năng tra cứu ngữ cảnh.
 */
public record WikiContextualLookupItemDTO(
        UUID id,
        String title,
        String articleType,
        String slug,
        String summary
) {
}
