package com.universe.identity.contracts.events;

import java.time.Instant;
import java.util.UUID;

public record UserProfileUpdatedEvent(
        UUID userId,
        String displayName,
        String avatarUrl,
        String bio,
        Instant occurredAt
) {
    public static UserProfileUpdatedEvent create(
            UUID userId,
            String displayName,
            String avatarUrl,
            String bio,
            Instant now
    ) {
        return new UserProfileUpdatedEvent(
                userId,
                displayName,
                avatarUrl,
                bio,
                now
        );
    }
}