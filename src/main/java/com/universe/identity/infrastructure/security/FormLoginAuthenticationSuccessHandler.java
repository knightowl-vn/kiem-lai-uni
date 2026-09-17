package com.universe.identity.infrastructure.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.RequestCache;

import java.io.IOException;
import java.util.Objects;

/**
 * Custom form-login authentication success handler supporting validated returnTo redirection
 * with fallback to hardened SavedRequest or the default /home destination.
 */
public class FormLoginAuthenticationSuccessHandler
        extends SavedRequestAwareAuthenticationSuccessHandler {

    private final RequestCache requestCache;
    private final SafeReturnToValidator returnToValidator;

    public FormLoginAuthenticationSuccessHandler(
            RequestCache requestCache,
            SafeReturnToValidator returnToValidator
    ) {
        this.requestCache = Objects.requireNonNull(requestCache, "RequestCache cannot be null");
        this.returnToValidator = Objects.requireNonNull(returnToValidator, "SafeReturnToValidator cannot be null");
        setRequestCache(requestCache);
        setDefaultTargetUrl("/home");
        setAlwaysUseDefaultTargetUrl(false);
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException, ServletException {
        String returnTo = request.getParameter("returnTo");
        if (returnTo != null && returnToValidator.isValid(returnTo)) {
            requestCache.removeRequest(request, response);
            clearAuthenticationAttributes(request);
            getRedirectStrategy().sendRedirect(request, response, returnTo);
            return;
        }

        super.onAuthenticationSuccess(request, response, authentication);
    }
}
