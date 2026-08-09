package com.universe.identity.contracts.dto;

import java.time.Instant;
import java.util.UUID;

public record UserDTO(
        UUID id,
        String email,
        String displayName,
        String avatarUrl,
        String status,
        String role,
        Instant createdAt
) {
}