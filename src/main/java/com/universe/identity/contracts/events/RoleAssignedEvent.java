package com.universe.identity.contracts.events;


import java.time.Instant;
import java.util.UUID;

public record RoleAssignedEvent(
        UUID userId,
        String newRoleId,
        Instant occurredAt
) {
    public static RoleAssignedEvent create(UUID userId, String newRoleId, Instant now) {
        return new RoleAssignedEvent(userId, newRoleId, now);
    }
}