package com.universe.wiki.entry.web.dto;

import java.util.List;

/**
 * Payload yêu cầu gửi đóng góp ý kiến / chỉnh sửa bài viết Wiki từ độc giả.
 *
 * Định danh bài viết (articleId) được trích xuất từ URI (@PathVariable),
 * và định danh người dùng (actor) được trích xuất từ phiên đăng nhập máy chủ.
 */
public record SubmitWikiContributionRequest(
        Long articleContentVersion,
        String contextType,
        String contributionType,
        String message,
        String selectedText,
        String selectedPrefix,
        String selectedSuffix,
        String selectedHeadingAnchor,
        List<String> sources
) {
}
