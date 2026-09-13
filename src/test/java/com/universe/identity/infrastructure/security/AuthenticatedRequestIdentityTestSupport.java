package com.universe.identity.infrastructure.security;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;

import jakarta.servlet.http.HttpServletRequest;

public final class AuthenticatedRequestIdentityTestSupport {

    private AuthenticatedRequestIdentityTestSupport() {
    }

    public static void attach(
            HttpServletRequest request,
            AuthenticatedRequestIdentity identity
    ) {
        AuthenticatedRequestIdentityAccessor.attach(request, identity);
    }
}
