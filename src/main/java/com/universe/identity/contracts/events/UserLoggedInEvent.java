package com.universe.identity.contracts.events;

import java.time.Instant;
import java.util.UUID;

public record UserLoggedInEvent(
        UUID userId,
        UUID sessionId,
        String ipAddress,
        String deviceInfo,
        Instant loginAt
) {
    public static UserLoggedInEvent create(UUID userId, UUID sessionId, String ipAddress, String deviceInfo, Instant now) {
        return new UserLoggedInEvent(userId, sessionId, ipAddress, deviceInfo, now);
    }
}