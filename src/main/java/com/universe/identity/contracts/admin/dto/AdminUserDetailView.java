package com.universe.identity.contracts.admin.dto;

import java.time.Instant;

public record AdminUserDetailView(
        String id,
        String displayName,
        String email,
        String avatarUrl,
        String bio,
        String status,
        String role,
        String authProvider,
        boolean hasLocalPassword,
        Instant createdAt,
        Instant updatedAt
) {
}