package com.universe.wiki.infrastructure.throttle;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.universe.wiki.application.ports.WikiContributionSubmissionThrottleDecision;
import com.universe.wiki.application.ports.WikiContributionSubmissionThrottlePort;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.UUID;

/**
 * Adapter điều tiết tần suất gửi đóng góp Wiki sử dụng Caffeine Cache (Node-Local).
 *
 * <p><strong>Lưu ý kiến trúc:</strong>
 * Giới hạn này được lưu trữ cục bộ trong bộ nhớ tiến trình JVM (Node-Local).
 * Cấu trúc dạng sliding window đảm bảo an toàn đa luồng (thread-safe) và tự động giải phóng
 * bộ nhớ sau thời gian không hoạt động (expireAfterAccess).</p>
 *
 * <p>Chính sách: Tối đa 10 lượt gửi trong cửa sổ 5 phút (300 giây).</p>
 */
@Component
public class CaffeineWikiContributionSubmissionThrottleAdapter implements WikiContributionSubmissionThrottlePort {

    public static final int MAX_SUBMISSION_ATTEMPTS = 10;
    public static final Duration WINDOW_DURATION = Duration.ofMinutes(5);
    private static final long MAX_CACHE_SIZE = 10_000L;

    private final Cache<UUID, UserAttemptsWindow> attemptsCache;

    public CaffeineWikiContributionSubmissionThrottleAdapter() {
        this.attemptsCache = Caffeine.newBuilder()
                .maximumSize(MAX_CACHE_SIZE)
                .expireAfterAccess(Duration.ofMinutes(10))
                .build();
    }

    @Override
    public WikiContributionSubmissionThrottleDecision tryAcquire(UUID userId, Instant now) {
        Objects.requireNonNull(userId, "ID người dùng không được để trống.");
        Objects.requireNonNull(now, "Thời gian hiện tại không được để trống.");

        Instant cutoff = now.minus(WINDOW_DURATION);

        UserAttemptsWindow window = attemptsCache.get(userId, k -> new UserAttemptsWindow());
        return window.recordAttempt(now, cutoff);
    }

    private static final class UserAttemptsWindow {
        private final ArrayDeque<Instant> timestamps = new ArrayDeque<>(MAX_SUBMISSION_ATTEMPTS);

        synchronized WikiContributionSubmissionThrottleDecision recordAttempt(Instant now, Instant cutoff) {
            while (!timestamps.isEmpty() && !timestamps.peekFirst().isAfter(cutoff)) {
                timestamps.pollFirst();
            }

            if (timestamps.size() < MAX_SUBMISSION_ATTEMPTS) {
                timestamps.addLast(now);
                return WikiContributionSubmissionThrottleDecision.allow();
            }

            Instant oldest = timestamps.peekFirst();
            Duration remaining = Duration.between(now, oldest.plus(WINDOW_DURATION));
            long retryAfterSeconds = Math.max(1L, remaining.toSeconds());
            if (remaining.toMillis() % 1000 > 0) {
                retryAfterSeconds += 1;
            }
            return WikiContributionSubmissionThrottleDecision.reject(retryAfterSeconds);
        }
    }
}
