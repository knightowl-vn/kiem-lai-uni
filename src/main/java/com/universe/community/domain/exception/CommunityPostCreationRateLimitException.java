package com.universe.community.domain.exception;

import java.util.Objects;

/**
 * Domain/application exception thrown when an author exceeds Community post creation rate limits
 * (cooldown, hourly limit, daily limit, or duplicate caption within 24 hours).
 */
public class CommunityPostCreationRateLimitException extends RuntimeException {

    public enum Reason {
        COOLDOWN("Bạn đang đăng bài quá nhanh. Vui lòng thử lại sau."),
        HOURLY_LIMIT("Bạn đã đạt giới hạn đăng bài trong giờ này."),
        DAILY_LIMIT("Bạn đã đạt giới hạn đăng bài trong 24 giờ."),
        DUPLICATE_CAPTION("Bạn đã đăng nội dung tương tự trong 24 giờ qua.");

        private final String defaultUserMessage;

        Reason(String defaultUserMessage) {
            this.defaultUserMessage = defaultUserMessage;
        }

        public String getDefaultUserMessage() {
            return defaultUserMessage;
        }
    }

    private final Reason reason;
    private final long retryAfterSeconds;
    private final String userMessage;

    public CommunityPostCreationRateLimitException(Reason reason, long retryAfterSeconds) {
        this(
                reason,
                retryAfterSeconds,
                Objects.requireNonNull(reason, "Reason cannot be null.").getDefaultUserMessage()
        );
    }

    public CommunityPostCreationRateLimitException(Reason reason, long retryAfterSeconds, String userMessage) {
        super(userMessage != null ? userMessage : reason.getDefaultUserMessage());
        this.reason = Objects.requireNonNull(reason, "Reason cannot be null.");
        this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
        this.userMessage = userMessage != null ? userMessage : reason.getDefaultUserMessage();
    }

    public Reason getReason() {
        return reason;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public String getUserMessage() {
        return userMessage;
    }
}
