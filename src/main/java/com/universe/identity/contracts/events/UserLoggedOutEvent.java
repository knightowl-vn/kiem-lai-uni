package com.universe.identity.contracts.events;

import java.time.Instant;
import java.util.UUID;

public record UserLoggedOutEvent(
        UUID userId,
        UUID sessionId,
        Instant occurredAt
) {
    public static UserLoggedOutEvent create(UUID userId, UUID sessionId, Instant now) {
        return new UserLoggedOutEvent(userId, sessionId, now);
    }
}