package com.universe.identity.infrastructure.security;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticatedRequestIdentityAccessorTest {

    @Test
    void identityIsIsolatedToTheServletRequestThatOwnsIt() {
        MockHttpServletRequest firstRequest = new MockHttpServletRequest();
        MockHttpServletRequest secondRequest = new MockHttpServletRequest();
        AuthenticatedRequestIdentity identity = identity();

        AuthenticatedRequestIdentityAccessor.attach(firstRequest, identity);

        assertThat(AuthenticatedRequestIdentityAccessor.find(firstRequest))
                .contains(identity);
        assertThat(AuthenticatedRequestIdentityAccessor.find(secondRequest))
                .isEmpty();
    }

    @Test
    void wrongAttributeTypeFailsSafely() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        AuthenticatedRequestIdentityAccessor.attach(request, identity());
        String internalKey = Collections.list(request.getAttributeNames()).get(0);
        request.setAttribute(internalKey, "not-an-identity");

        assertThat(AuthenticatedRequestIdentityAccessor.find(request)).isEmpty();
    }

    private AuthenticatedRequestIdentity identity() {
        return new AuthenticatedRequestIdentity(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "reader@universe.local",
                "Reader",
                null,
                UserStatus.ACTIVE,
                UserRole.USER
        );
    }
}
