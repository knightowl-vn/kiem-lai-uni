package com.universe.identity.entry.web.advice;

import com.universe.identity.application.ports.CurrentUserQueryPort;
import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.contracts.currentuser.CurrentUserView;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityAccessor;
import com.universe.shared.security.AuthenticatedEmailResolver;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class CurrentUserAdvice {

    private final CurrentUserQueryPort
            currentUserQueryPort;

    private final AuthenticatedEmailResolver
            authenticatedEmailResolver;

    public CurrentUserAdvice(
            CurrentUserQueryPort currentUserQueryPort,
            AuthenticatedEmailResolver authenticatedEmailResolver
    ) {
        this.currentUserQueryPort =
                currentUserQueryPort;

        this.authenticatedEmailResolver =
                authenticatedEmailResolver;
    }

    @ModelAttribute("currentUser")
    public CurrentUserView currentUser(
            HttpServletRequest request,
            Authentication authentication
    ) {
        return AuthenticatedRequestIdentityAccessor.find(request)
                .map(this::toCurrentUserView)
                .orElseGet(() -> authenticatedEmailResolver
                        .resolve(authentication)
                        .flatMap(
                                currentUserQueryPort::findByEmail
                        )
                        .orElse(null));
    }

    private CurrentUserView toCurrentUserView(
            AuthenticatedRequestIdentity identity
    ) {
        return new CurrentUserView(
                identity.userId().toString(),
                identity.normalizedEmail(),
                identity.displayName(),
                identity.avatarUrl(),
                null,
                identity.status().name(),
                identity.role().name(),
                null,
                null,
                false
        );
    }
}
