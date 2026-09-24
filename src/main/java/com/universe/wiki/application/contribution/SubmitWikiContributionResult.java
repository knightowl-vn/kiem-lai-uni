package com.universe.wiki.application.contribution;

import java.util.UUID;

/**
 * Kết quả thực thi Use Case gửi đóng góp bài viết Wiki.
 *
 * @param contributionId định danh đóng góp
 * @param status trạng thái kiểm duyệt hiện tại
 * @param alreadySubmitted true nếu đây là đóng góp đã gửi trong cửa sổ 60s (dedup); false nếu tạo mới
 * @param message thông điệp phản hồi
 */
public record SubmitWikiContributionResult(
        UUID contributionId,
        String status,
        boolean alreadySubmitted,
        String message
) {
}
