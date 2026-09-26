package com.universe.wiki.application.ports;

import java.time.Instant;
import java.util.UUID;

/**
 * Cổng điều tiết tần suất gửi đóng góp bài viết Wiki từ người dùng đã xác thực.
 *
 * Chính sách: Tối đa 10 lượt gửi đóng góp trong cửa sổ 5 phút cho mỗi người dùng.
 * Giới hạn độc lập cho từng tài khoản và được thực thi trên máy chủ trước khi lưu trữ.
 */
public interface WikiContributionSubmissionThrottlePort {

    /**
     * Thử yêu cầu cấp phép thực hiện gửi đóng góp.
     *
     * @param userId định danh người dùng gửi đóng góp
     * @param now thời điểm thực hiện yêu cầu
     * @return quyết định cho phép hoặc từ chối kèm thời gian chờ
     */
    WikiContributionSubmissionThrottleDecision tryAcquire(UUID userId, Instant now);
}
