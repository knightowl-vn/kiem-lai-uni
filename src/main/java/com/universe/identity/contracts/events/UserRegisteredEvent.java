package com.universe.identity.contracts.events;

import com.universe.shared.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

public record UserRegisteredEvent(
        UUID eventId,
        String eventType,
        UUID aggregateId,
        String email,
        String displayName,
        Instant occurredAt
) implements DomainEvent {

    public static UserRegisteredEvent create(
            UUID userId,
            String email,
            String displayName,
            Instant occurredAt
    ) {
        return new UserRegisteredEvent(
                UUID.randomUUID(),
                "UserRegisteredEvent",
                userId,
                email,
                displayName,
                occurredAt
        );
    }
}