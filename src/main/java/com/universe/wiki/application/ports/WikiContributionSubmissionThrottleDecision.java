package com.universe.wiki.application.ports;

/**
 * Quyết định điều tiết tần suất gửi đóng góp Wiki từ người dùng.
 *
 * @param allowed true nếu yêu cầu được phép tiếp tục; false nếu bị giới hạn
 * @param retryAfterSeconds số giây cần chờ trước khi có thể thử lại (khi bị giới hạn)
 */
public record WikiContributionSubmissionThrottleDecision(
        boolean allowed,
        long retryAfterSeconds
) {
    public static WikiContributionSubmissionThrottleDecision allow() {
        return new WikiContributionSubmissionThrottleDecision(true, 0L);
    }

    public static WikiContributionSubmissionThrottleDecision reject(long retryAfterSeconds) {
        return new WikiContributionSubmissionThrottleDecision(false, Math.max(1L, retryAfterSeconds));
    }
}
