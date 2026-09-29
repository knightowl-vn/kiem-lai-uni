package com.universe.identity.application.registration;

import com.universe.identity.application.ports.UserRepositoryPort;
import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.domain.Email;
import com.universe.identity.domain.User;
import com.universe.shared.messaging.OutboxPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Isolated attempt executor for registering a new user within a REQUIRES_NEW transaction.
 *
 * <p>Ensures that any database constraint conflict (such as duplicate public_handle) rolls back
 * cleanly without poisoning any outer persistence context, allowing deterministic retry.
 */
@Component
public class RegisterUserAttemptExecutor {

    private static final String AGGREGATE_TYPE = "User";
    private static final String SOURCE_MODULE = "Identity";

    private final UserRepositoryPort userRepositoryPort;
    private final OutboxPort outboxPort;

    public RegisterUserAttemptExecutor(
            UserRepositoryPort userRepositoryPort,
            OutboxPort outboxPort
    ) {
        this.userRepositoryPort = Objects.requireNonNull(
                userRepositoryPort,
                "UserRepositoryPort cannot be null"
        );
        this.outboxPort = Objects.requireNonNull(
                outboxPort,
                "OutboxPort cannot be null"
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UserDTO executeAttempt(
            UUID userId,
            Email email,
            String passwordHash,
            String displayName,
            String publicHandle,
            Instant createdAt
    ) {
        Objects.requireNonNull(userId, "userId cannot be null");
        Objects.requireNonNull(email, "email cannot be null");
        Objects.requireNonNull(passwordHash, "passwordHash cannot be null");
        Objects.requireNonNull(displayName, "displayName cannot be null");
        Objects.requireNonNull(publicHandle, "publicHandle cannot be null");
        Objects.requireNonNull(createdAt, "createdAt cannot be null");

        User user = User.createLocal(
                userId,
                email,
                passwordHash,
                displayName,
                publicHandle,
                createdAt
        );

        userRepositoryPort.save(user);

        user.domainEventsSnapshot()
                .forEach(event ->
                        outboxPort.saveEvent(
                                event,
                                AGGREGATE_TYPE,
                                user.getAggregateVersion(),
                                SOURCE_MODULE
                        )
                );

        user.clearDomainEvents();

        return toDto(user);
    }

    private UserDTO toDto(User user) {
        return new UserDTO(
                user.getId(),
                user.getEmail().value(),
                user.getDisplayName(),
                user.getAvatarUrl(),
                user.getPublicHandle(),
                user.getStatus().name(),
                user.getRole().name(),
                user.getCreatedAt()
        );
    }
}
