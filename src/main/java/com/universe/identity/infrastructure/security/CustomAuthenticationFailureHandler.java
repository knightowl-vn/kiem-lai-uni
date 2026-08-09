package com.universe.identity.infrastructure.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class CustomAuthenticationFailureHandler
        implements AuthenticationFailureHandler {

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException, ServletException {

        String redirectUrl;

        if (exception instanceof LockedException) {
            redirectUrl =
                    request.getContextPath()
                            + "/login?blocked";

        } else if (exception instanceof DisabledException) {
            redirectUrl =
                    request.getContextPath()
                            + "/login?disabled";

        } else if (exception instanceof BadCredentialsException) {
            redirectUrl =
                    request.getContextPath()
                            + "/login?error";

        } else {
            redirectUrl =
                    request.getContextPath()
                            + "/login?error";
        }

        response.sendRedirect(redirectUrl);
    }
}