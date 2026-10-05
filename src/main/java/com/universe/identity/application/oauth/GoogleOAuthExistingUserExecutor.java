package com.universe.identity.application.oauth;

import com.universe.identity.application.ports.UserRepositoryPort;
import com.universe.identity.domain.User;
import com.universe.shared.messaging.OutboxPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Dedicated executor for updating or linking existing users during Google OAuth within a @Transactional boundary.
 */
@Component
public class GoogleOAuthExistingUserExecutor {

    private static final String AGGREGATE_TYPE = "User";
    private static final String SOURCE_MODULE = "Identity";

    private final UserRepositoryPort userRepository;
    private final OutboxPort outboxPort;

    public GoogleOAuthExistingUserExecutor(
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

    /**
     * Updates an existing Google user (found by provider subject) with fresh profile data if missing.
     */
    @Transactional
    public User updateByProviderSubject(
            User existingUser,
            String displayName,
            String avatarUrl
    ) {
        Objects.requireNonNull(existingUser, "existingUser cannot be null");

        existingUser.updateOAuthProfileIfMissing(
                displayName,
                avatarUrl
        );

        saveAndPublishEvents(existingUser);

        return existingUser;
    }

    /**
     * Links an existing local email user to a Google account and updates profile data if missing.
     */
    @Transactional
    public User linkAndProfileUpdate(
            User existingUser,
            String providerSubject,
            String displayName,
            String avatarUrl
    ) {
        Objects.requireNonNull(existingUser, "existingUser cannot be null");
        Objects.requireNonNull(providerSubject, "providerSubject cannot be null");

        existingUser.linkGoogleAccount(providerSubject);

        existingUser.updateOAuthProfileIfMissing(
                displayName,
                avatarUrl
        );

        saveAndPublishEvents(existingUser);

        return existingUser;
    }

    private void saveAndPublishEvents(User user) {
        userRepository.save(user);

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
    }
}
