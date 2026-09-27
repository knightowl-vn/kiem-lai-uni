package com.universe.identity.infrastructure.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/**
 * Authentication failure handler for OAuth2 login.
 *
 * Consumes any pending returnTo target mapped to the OAuth state and appends it to the
 * error redirect (/login?oauthError&returnTo=<encoded-returnTo>) so that the user can fall back
 * to password login without losing their intended destination.
 */
public class OAuth2AuthenticationFailureHandler implements AuthenticationFailureHandler {

    private final OAuth2ReturnToStore returnToStore;
    private final SafeReturnToValidator returnToValidator;
    private final RedirectStrategy redirectStrategy;

    public OAuth2AuthenticationFailureHandler(
            OAuth2ReturnToStore returnToStore,
            SafeReturnToValidator returnToValidator
    ) {
        this(returnToStore, returnToValidator, new DefaultRedirectStrategy());
    }

    public OAuth2AuthenticationFailureHandler(
            OAuth2ReturnToStore returnToStore,
            SafeReturnToValidator returnToValidator,
            RedirectStrategy redirectStrategy
    ) {
        this.returnToStore = Objects.requireNonNull(returnToStore, "OAuth2ReturnToStore cannot be null");
        this.returnToValidator = Objects.requireNonNull(returnToValidator, "SafeReturnToValidator cannot be null");
        this.redirectStrategy = Objects.requireNonNull(redirectStrategy, "RedirectStrategy cannot be null");
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException, ServletException {
        String state = request.getParameter("state");
        Optional<String> returnToOpt = (state != null) ? returnToStore.consume(request, state) : Optional.empty();

        String redirectUrl = "/login?oauthError";
        if (returnToOpt.isPresent()) {
            String returnTo = returnToOpt.get();
            if (returnToValidator.isValid(returnTo)) {
                redirectUrl = "/login?oauthError&returnTo=" + URLEncoder.encode(returnTo, StandardCharsets.UTF_8);
            }
        }

        redirectStrategy.sendRedirect(request, response, redirectUrl);
    }
}
