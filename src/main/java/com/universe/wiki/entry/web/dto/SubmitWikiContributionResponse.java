package com.universe.wiki.entry.web.dto;

import java.util.UUID;

/**
 * Payload phản hồi sau khi gửi đóng góp bài viết Wiki.
 *
 * @param contributionId định danh duy nhất của đóng góp
 * @param status trạng thái kiểm duyệt hiện tại của đóng góp
 * @param alreadySubmitted true nếu phát hiện đóng góp trùng lặp trong cửa sổ 60s; false nếu tạo mới
 * @param message thông điệp phản hồi thân thiện cho độc giả
 */
public record SubmitWikiContributionResponse(
        UUID contributionId,
        String status,
        boolean alreadySubmitted,
        String message
) {
}
