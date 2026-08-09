package com.universe.identity.contracts.currentuser;

import java.time.Instant;

public record CurrentUserView(
        String id,
        String email,
        String displayName,
        String avatarUrl,
        String bio,
        String status,
        String role,
        String authProvider,
        Instant createdAt,
        boolean hasLocalPassword
) {
}