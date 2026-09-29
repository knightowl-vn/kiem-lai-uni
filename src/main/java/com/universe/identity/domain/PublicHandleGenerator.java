package com.universe.identity.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Domain generator for allocating deterministic publicHandle candidates.
 */
public final class PublicHandleGenerator {

    public static final int MAX_COLLISION_ATTEMPTS = 10;

    private PublicHandleGenerator() {
    }

    /**
     * Generates a deterministic publicHandle candidate for a specific attempt sequence.
     *
     * @param displayName raw display name
     * @param userId immutable user UUID
     * @param attempt attempt sequence (0 = initial base candidate, 1..MAX_COLLISION_ATTEMPTS = collision candidate, > MAX_COLLISION_ATTEMPTS = full UUID fallback)
     * @return valid publicHandle candidate matching {@code ^[a-z0-9_]{3,40}$}
     */
    public static String candidateForAttempt(String displayName, UUID userId, int attempt) {
        Objects.requireNonNull(userId, "userId cannot be null");
        String baseCandidate = PublicHandleNormalizer.generateCandidate(displayName, userId);
        if (attempt <= 0) {
            return baseCandidate;
        }
        if (attempt <= MAX_COLLISION_ATTEMPTS) {
            return PublicHandleNormalizer.generateCollisionCandidate(baseCandidate, userId, attempt);
        }
        // Ultimate fallback: u_ + full 32-char hex UUID (length 34, matching ^[a-z0-9_]{3,40}$)
        String fullUuidHex = userId.toString().replace("-", "").toLowerCase(java.util.Locale.ROOT);
        return "u_" + fullUuidHex;
    }
}
