package com.universe.identity.contracts.events;

import java.time.Instant;
import java.util.UUID;

public record UserBannedEvent(
        UUID userId,
        String reason,
        UUID bannedBy,
        Instant occurredAt
) {
    public static UserBannedEvent create(UUID userId, String reason, UUID bannedBy, Instant now) {
        return new UserBannedEvent(userId, reason, bannedBy, now);
    }
}