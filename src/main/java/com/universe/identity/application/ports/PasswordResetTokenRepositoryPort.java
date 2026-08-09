package com.universe.identity.application.ports;

import com.universe.identity.domain.PasswordResetToken;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PasswordResetTokenRepositoryPort {

    Optional<PasswordResetToken>
    findActiveByTokenHash(
            String tokenHash
    );

    void save(
            PasswordResetToken token
    );

    void revokeAllActiveByUserId(
            UUID userId,
            Instant revokedAt
    );
}