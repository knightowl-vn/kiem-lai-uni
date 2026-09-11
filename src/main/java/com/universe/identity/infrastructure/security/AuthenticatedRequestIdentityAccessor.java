package com.universe.identity.infrastructure.security;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Objects;
import java.util.Optional;

public final class AuthenticatedRequestIdentityAccessor {

    private static final String ATTRIBUTE_KEY =
            AuthenticatedRequestIdentityAccessor.class.getName() + ".identity";

    private AuthenticatedRequestIdentityAccessor() {
    }

    static void attach(
            HttpServletRequest request,
            AuthenticatedRequestIdentity identity
    ) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(identity, "identity must not be null");
        request.setAttribute(ATTRIBUTE_KEY, identity);
    }

    public static Optional<AuthenticatedRequestIdentity> find(
            HttpServletRequest request
    ) {
        if (request == null) {
            return Optional.empty();
        }

        Object value = request.getAttribute(ATTRIBUTE_KEY);
        return value instanceof AuthenticatedRequestIdentity identity
                ? Optional.of(identity)
                : Optional.empty();
    }
}
