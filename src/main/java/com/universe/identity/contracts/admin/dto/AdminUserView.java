package com.universe.identity.contracts.admin.dto;

import java.time.Instant;

public record AdminUserView(
        String id,
        String displayName,
        String email,
        String avatarUrl,
        String status,
        String role,
        String authProvider,
        Instant createdAt
) {
}