package com.universe.identity.application.security;

import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable identity data authorized for reuse during one servlet request.
 */
public record AuthenticatedRequestIdentity(
        UUID userId,
        String normalizedEmail,
        String displayName,
        String avatarUrl,
        UserStatus status,
        UserRole role
) {

    public AuthenticatedRequestIdentity {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(normalizedEmail, "normalizedEmail must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(role, "role must not be null");

        normalizedEmail = normalizedEmail.trim().toLowerCase(Locale.ROOT);
        if (normalizedEmail.isEmpty()) {
            throw new IllegalArgumentException("normalizedEmail must not be blank");
        }
    }
}
