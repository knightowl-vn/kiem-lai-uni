package com.universe.wiki.application.exceptions;

/**
 * Ngoại lệ ném ra khi người dùng gửi đóng góp vượt quá tần suất cho phép (10 lần / 5 phút).
 */
public class WikiContributionSubmissionRateLimitedException extends RuntimeException {

    private final long retryAfterSeconds;

    public WikiContributionSubmissionRateLimitedException(long retryAfterSeconds) {
        super(String.format("Bạn đang gửi đóng góp quá nhanh. Vui lòng thử lại sau %d giây.", Math.max(1L, retryAfterSeconds)));
        this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
