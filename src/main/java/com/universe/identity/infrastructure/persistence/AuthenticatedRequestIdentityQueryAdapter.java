package com.universe.identity.infrastructure.persistence;

import com.universe.identity.application.ports.AuthenticatedRequestIdentityQueryPort;
import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.domain.UserStatus;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Component
public class AuthenticatedRequestIdentityQueryAdapter
        implements AuthenticatedRequestIdentityQueryPort {

    private final SpringDataUserJpaRepository userRepository;

    public AuthenticatedRequestIdentityQueryAdapter(
            SpringDataUserJpaRepository userRepository
    ) {
        this.userRepository = userRepository;
    }

    @Override
    public Optional<AuthenticatedRequestIdentity> findByEmail(
            String email
    ) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }

        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        return userRepository.findRequestIdentityByEmail(normalizedEmail)
                .map(this::toIdentity);
    }

    private AuthenticatedRequestIdentity toIdentity(
            AuthenticatedRequestIdentityProjection projection
    ) {
        return new AuthenticatedRequestIdentity(
                UUID.fromString(projection.getUserId()),
                projection.getNormalizedEmail(),
                projection.getDisplayName(),
                projection.getAvatarUrl(),
                UserStatus.valueOf(projection.getStatus().toUpperCase(Locale.ROOT)),
                projection.getRole()
        );
    }
}
