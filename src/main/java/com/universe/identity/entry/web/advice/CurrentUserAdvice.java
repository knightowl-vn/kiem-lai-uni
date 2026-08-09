package com.universe.identity.entry.web.advice;

import com.universe.identity.application.ports.CurrentUserQueryPort;
import com.universe.identity.contracts.currentuser.CurrentUserView;
import com.universe.shared.security.AuthenticatedEmailResolver;

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
            Authentication authentication
    ) {
        return authenticatedEmailResolver
                .resolve(authentication)
                .flatMap(
                        currentUserQueryPort::findByEmail
                )
                .orElse(null);
    }
}