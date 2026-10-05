package com.universe.identity.application.oauth;

import com.universe.identity.application.ports.UserRepositoryPort;
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
 * Isolated attempt executor for creating a new Google OAuth user within a REQUIRES_NEW transaction.
 *
 * <p>Ensures that any database constraint conflict (such as duplicate public_handle) rolls back
 * cleanly without poisoning any outer persistence context, allowing deterministic retry.
 */
@Component
public class GoogleOAuthNewUserAttemptExecutor {

    private static final String AGGREGATE_TYPE = "User";
    private static final String SOURCE_MODULE = "Identity";

    private final UserRepositoryPort userRepository;
    private final OutboxPort outboxPort;

    public GoogleOAuthNewUserAttemptExecutor(
            UserRepositoryPort userRepository,
            OutboxPort outboxPort
    ) {
        this.userRepository = Objects.requireNonNull(
                userRepository,
                "UserRepositoryPort cannot be null"
        );
        this.outboxPort = Objects.requireNonNull(
                outboxPort,
                "OutboxPort cannot be null"
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public User executeAttempt(
            UUID userId,
            Email email,
            String displayName,
            String avatarUrl,
            String providerSubject,
            String publicHandle,
            Instant createdAt
    ) {
        Objects.requireNonNull(userId, "userId cannot be null");
        Objects.requireNonNull(email, "email cannot be null");
        Objects.requireNonNull(displayName, "displayName cannot be null");
        Objects.requireNonNull(providerSubject, "providerSubject cannot be null");
        Objects.requireNonNull(publicHandle, "publicHandle cannot be null");
        Objects.requireNonNull(createdAt, "createdAt cannot be null");

        User newUser = User.createGoogle(
                userId,
                email,
                displayName,
                avatarUrl,
                providerSubject,
                publicHandle,
                createdAt
        );

        userRepository.save(newUser);

        newUser.domainEventsSnapshot()
                .forEach(event ->
                        outboxPort.saveEvent(
                                event,
                                AGGREGATE_TYPE,
                                newUser.getAggregateVersion(),
                                SOURCE_MODULE
                        )
                );

        newUser.clearDomainEvents();

        return newUser;
    }
}
