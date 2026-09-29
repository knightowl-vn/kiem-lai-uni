package com.universe.identity.contracts.dto;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record UserDTO(
        UUID id,
        String email,
        String displayName,
        String avatarUrl,
        String publicHandle,
        String status,
        String role,
        Instant createdAt
) {
    public UserDTO {
        Objects.requireNonNull(id, "id cannot be null");
        Objects.requireNonNull(email, "email cannot be null");
    }
}