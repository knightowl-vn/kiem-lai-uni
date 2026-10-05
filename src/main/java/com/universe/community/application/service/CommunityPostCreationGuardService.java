package com.universe.community.application.service;

import com.universe.community.application.port.out.CommunityPostCreationGuardRepositoryPort;
import com.universe.community.domain.CommunityPost;
import com.universe.community.domain.exception.CommunityPostCreationRateLimitException;
import com.universe.community.domain.exception.CommunityPostCreationRateLimitException.Reason;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Service implementing the Community post creation anti-spam / rate-limiting guard policy.
 * Enforces cooldown (60s), hourly limit (10), daily limit (30), and duplicate caption barrier (24h).
 */
@Service
public class CommunityPostCreationGuardService {

    public static final long COOLDOWN_SECONDS = 60L;
    public static final int HOURLY_MAX_POSTS = 10;
    public static final int DAILY_MAX_POSTS = 30;
    public static final long HOURLY_WINDOW_HOURS = 1L;
    public static final long DAILY_WINDOW_HOURS = 24L;
    public static final long DUPLICATE_WINDOW_HOURS = 24L;

    private final CommunityPostCreationGuardRepositoryPort guardRepositoryPort;

    public CommunityPostCreationGuardService(CommunityPostCreationGuardRepositoryPort guardRepositoryPort) {
        this.guardRepositoryPort = Objects.requireNonNull(guardRepositoryPort, "CommunityPostCreationGuardRepositoryPort cannot be null.");
    }

    /**
     * Acquires the multi-instance per-author database write lock.
     * Must be called within an active transaction prior to evaluation.
     *
     * @param authorUserId the author's UUID
     */
    public void acquireAuthorLock(UUID authorUserId) {
        guardRepositoryPort.acquireAuthorLock(authorUserId);
    }

    /**
     * Evaluates creation rate limits for the author and caption at authoritative timestamp {@code now}.
     * Throws {@link CommunityPostCreationRateLimitException} if any rule is violated.
     *
     * @param authorUserId the author's UUID
     * @param normalizedCaption pre-validated, trimmed caption text
     * @param now the authoritative current instant
     * @return the 64-character lowercase SHA-256 hash of the normalized caption
     */
    public String evaluateEligibility(UUID authorUserId, String normalizedCaption, Instant now) {
        Objects.requireNonNull(authorUserId, "Author user ID cannot be null.");
        Objects.requireNonNull(normalizedCaption, "Normalized caption cannot be null.");
        Objects.requireNonNull(now, "Current timestamp (now) cannot be null.");

        String captionHash = computeCaptionHash(normalizedCaption);

        List<Violation> violations = new ArrayList<>(4);

        // 1. Cooldown rule (60 seconds)
        Optional<Instant> latestCreationOpt = guardRepositoryPort.findLatestCreationTimestamp(authorUserId);
        if (latestCreationOpt.isPresent()) {
            Instant latestCreated = latestCreationOpt.get();
            Instant cooldownExpiry = latestCreated.plusSeconds(COOLDOWN_SECONDS);
            if (cooldownExpiry.isAfter(now)) {
                long retryAfter = computeRetryAfterSeconds(now, cooldownExpiry);
                violations.add(new Violation(Reason.COOLDOWN, retryAfter));
            }
        }

        // 2. Hourly rule (max 10 posts in rolling 1 hour)
        Instant hourlyCutoff = now.minus(HOURLY_WINDOW_HOURS, ChronoUnit.HOURS);
        long hourlyCount = guardRepositoryPort.countCreationsAfter(authorUserId, hourlyCutoff);
        if (hourlyCount >= HOURLY_MAX_POSTS) {
            Optional<Instant> oldestHourlyOpt = guardRepositoryPort.findOldestCreationTimestampAfter(authorUserId, hourlyCutoff);
            if (oldestHourlyOpt.isPresent()) {
                Instant hourlyExpiry = oldestHourlyOpt.get().plus(HOURLY_WINDOW_HOURS, ChronoUnit.HOURS);
                long retryAfter = computeRetryAfterSeconds(now, hourlyExpiry);
                violations.add(new Violation(Reason.HOURLY_LIMIT, retryAfter));
            }
        }

        // 3. Daily rule (max 30 posts in rolling 24 hours)
        Instant dailyCutoff = now.minus(DAILY_WINDOW_HOURS, ChronoUnit.HOURS);
        long dailyCount = guardRepositoryPort.countCreationsAfter(authorUserId, dailyCutoff);
        if (dailyCount >= DAILY_MAX_POSTS) {
            Optional<Instant> oldestDailyOpt = guardRepositoryPort.findOldestCreationTimestampAfter(authorUserId, dailyCutoff);
            if (oldestDailyOpt.isPresent()) {
                Instant dailyExpiry = oldestDailyOpt.get().plus(DAILY_WINDOW_HOURS, ChronoUnit.HOURS);
                long retryAfter = computeRetryAfterSeconds(now, dailyExpiry);
                violations.add(new Violation(Reason.DAILY_LIMIT, retryAfter));
            }
        }

        // 4. Duplicate caption rule (forbidden within rolling 24 hours)
        Optional<Instant> duplicateOpt = guardRepositoryPort.findLatestMatchingCaptionCreation(
                authorUserId,
                captionHash,
                dailyCutoff
        );
        if (duplicateOpt.isPresent()) {
            Instant duplicateExpiry = duplicateOpt.get().plus(DUPLICATE_WINDOW_HOURS, ChronoUnit.HOURS);
            if (duplicateExpiry.isAfter(now)) {
                long retryAfter = computeRetryAfterSeconds(now, duplicateExpiry);
                violations.add(new Violation(Reason.DUPLICATE_CAPTION, retryAfter));
            }
        }

        if (!violations.isEmpty()) {
            // Pick violation requiring the maximum wait
            Violation chosen = violations.get(0);
            for (Violation v : violations) {
                if (v.retryAfterSeconds > chosen.retryAfterSeconds) {
                    chosen = v;
                }
            }
            throw new CommunityPostCreationRateLimitException(chosen.reason, chosen.retryAfterSeconds);
        }

        return captionHash;
    }

    /**
     * Appends an immutable creation event for a successfully committed CommunityPost.
     *
     * @param eventId the event UUID
     * @param authorUserId the author's UUID
     * @param postId the created post's UUID
     * @param normalizedCaptionHash the 64-char caption hash
     * @param createdAt the creation timestamp
     */
    public void recordCreationEvent(
            UUID eventId,
            UUID authorUserId,
            UUID postId,
            String normalizedCaptionHash,
            Instant createdAt
    ) {
        guardRepositoryPort.appendCreationEvent(eventId, authorUserId, postId, normalizedCaptionHash, createdAt);
    }

    /**
     * Computes the 64-character lowercase hexadecimal SHA-256 hash of UTF-8 normalized caption bytes.
     *
     * @param normalizedCaption canonical normalized caption
     * @return 64-character lowercase hex string
     */
    public static String computeCaptionHash(String normalizedCaption) {
        Objects.requireNonNull(normalizedCaption, "Normalized caption cannot be null.");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(normalizedCaption.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    sb.append('0');
                }
                sb.append(hex);
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable in JVM", e);
        }
    }

    /**
     * Computes the positive integer Retry-After duration in seconds, rounding fractional seconds UP.
     * Minimum returned value is 1.
     *
     * @param now authoritative current time
     * @param expiry boundary instant when the restriction is lifted
     * @return ceil seconds to wait, >= 1
     */
    public static long computeRetryAfterSeconds(Instant now, Instant expiry) {
        Duration duration = Duration.between(now, expiry);
        long millis = duration.toMillis();
        if (millis <= 0) {
            return 1L;
        }
        return (millis + 999L) / 1000L;
    }

    private static class Violation {
        final Reason reason;
        final long retryAfterSeconds;

        Violation(Reason reason, long retryAfterSeconds) {
            this.reason = reason;
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }
}
