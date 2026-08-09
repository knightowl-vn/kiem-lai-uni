package com.universe.identity.infrastructure.persistence;

import com.universe.identity.application.ports.CurrentUserQueryPort;
import com.universe.identity.contracts.currentuser.CurrentUserView;
import com.universe.identity.domain.UserRole;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

@Component
public class CurrentUserQueryAdapter
        implements CurrentUserQueryPort {

    private final SpringDataUserJpaRepository
            userRepository;

    public CurrentUserQueryAdapter(
            SpringDataUserJpaRepository userRepository
    ) {
        this.userRepository =
                userRepository;
    }

    @Override
    public Optional<CurrentUserView> findByEmail(
            String email
    ) {
        if (email == null
                || email.isBlank()) {

            return Optional.empty();
        }

        String normalizedEmail =
                email.trim()
                        .toLowerCase(
                                Locale.ROOT
                        );

        return userRepository
                .findByEmail(normalizedEmail)
                .map(this::toView);
    }

    private CurrentUserView toView(
            UserJpaEntity entity
    ) {
        boolean hasLocalPassword =
                entity.getPasswordHash() != null
                        && !entity.getPasswordHash()
                        .isBlank();

        String role =
                entity.getRole() == null
                        ? UserRole.USER.name()
                        : entity.getRole().name();

        return new CurrentUserView(
                entity.getId(),
                entity.getEmail(),
                entity.getDisplayName(),
                entity.getAvatarUrl(),
                entity.getBio(),
                entity.getStatus(),
                role,
                entity.getAuthProvider(),
                entity.getCreatedAt(),
                hasLocalPassword
        );
    }
}