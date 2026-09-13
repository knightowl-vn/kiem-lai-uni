package com.universe.identity.infrastructure.security;

import com.universe.identity.application.ports.AuthenticatedRequestIdentityQueryPort;
import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.domain.UserStatus;
import com.universe.identity.infrastructure.persistence.SpringDataUserJpaRepository;
import com.universe.identity.infrastructure.persistence.UserJpaEntity;

import java.util.UUID;

public final class AccountStatusFilterTestSupport {

    private AccountStatusFilterTestSupport() {
    }

    public static AuthenticatedRequestIdentityQueryPort queryPort(
            SpringDataUserJpaRepository repository
    ) {
        return email -> repository.findByEmail(email)
                .map(AccountStatusFilterTestSupport::toIdentity);
    }

    private static AuthenticatedRequestIdentity toIdentity(
            UserJpaEntity user
    ) {
        return new AuthenticatedRequestIdentity(
                UUID.fromString(user.getId()),
                user.getEmail(),
                user.getDisplayName() == null ? "Test User" : user.getDisplayName(),
                user.getAvatarUrl(),
                UserStatus.valueOf(user.getStatus()),
                user.getRole()
        );
    }
}
