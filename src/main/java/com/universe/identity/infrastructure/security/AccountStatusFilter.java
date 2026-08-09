package com.universe.identity.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.universe.identity.infrastructure.persistence.SpringDataUserJpaRepository;
import com.universe.identity.infrastructure.persistence.UserJpaEntity;

import java.io.IOException;
import java.util.Locale;
import java.util.Optional;

@Component
public class AccountStatusFilter
        extends OncePerRequestFilter {

    private final SpringDataUserJpaRepository userRepository;

    public AccountStatusFilter(
            SpringDataUserJpaRepository userRepository
    ) {
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        Authentication authentication =
                SecurityContextHolder
                        .getContext()
                        .getAuthentication();

        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication
                    instanceof AnonymousAuthenticationToken) {

            filterChain.doFilter(request, response);
            return;
        }

        String email =
                resolveEmail(authentication);

        if (email == null || email.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        Optional<UserJpaEntity> userOptional =
                userRepository.findByEmail(
                        email.trim()
                                .toLowerCase(Locale.ROOT)
                );

        if (userOptional.isEmpty()) {
            invalidateAuthentication(
                    request,
                    response
            );

            response.sendRedirect(
                    request.getContextPath()
                            + "/login?accountNotFound"
            );

            return;
        }

        UserJpaEntity user =
                userOptional.get();

        String status =
                user.getStatus();

        if (!"ACTIVE".equalsIgnoreCase(status)) {
            invalidateAuthentication(
                    request,
                    response
            );

            String loginParameter =
                    "BLOCKED".equalsIgnoreCase(status)
                            ? "blocked"
                            : "disabled";

            response.sendRedirect(
                    request.getContextPath()
                            + "/login?"
                            + loginParameter
            );

            return;
        }
        
        String expectedAuthority =
                "ROLE_"
                        + user.getRole()
                                .name();

        boolean authorityMatches =
                authentication
                        .getAuthorities()
                        .stream()
                        .anyMatch(authority ->
                                expectedAuthority.equals(
                                        authority.getAuthority()
                                )
                        );

        if (!authorityMatches) {
            invalidateAuthentication(
                    request,
                    response
            );

            response.sendRedirect(
                    request.getContextPath()
                            + "/login?permissionsChanged"
            );

            return;
        }

        filterChain.doFilter(request, response);
    }

    private String resolveEmail(
            Authentication authentication
    ) {
        Object principal =
                authentication.getPrincipal();

        if (principal instanceof OAuth2User oauth2User) {
            return oauth2User.getAttribute("email");
        }

        return authentication.getName();
    }

    private void invalidateAuthentication(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        SecurityContextHolder.clearContext();

        HttpSession session =
                request.getSession(false);

        if (session != null) {
            session.invalidate();
        }

        expireCookie(
                response,
                "JSESSIONID",
                request.getContextPath()
        );

        expireCookie(
                response,
                "remember-me",
                request.getContextPath()
        );
    }

    private void expireCookie(
            HttpServletResponse response,
            String cookieName,
            String contextPath
    ) {
        Cookie cookie =
                new Cookie(cookieName, "");

        cookie.setMaxAge(0);
        cookie.setHttpOnly(true);

        cookie.setPath(
                contextPath == null
                        || contextPath.isBlank()
                        ? "/"
                        : contextPath
        );

        response.addCookie(cookie);
    }

    @Override
    protected boolean shouldNotFilter(
            HttpServletRequest request
    ) {
        String path =
                request.getRequestURI()
                        .substring(
                                request
                                        .getContextPath()
                                        .length()
                        );

        return path.equals("/login")
                || path.equals("/register")
                || path.equals("/forgot-password")
                || path.equals("/reset-password")
                || path.equals("/access-denied")
                || path.equals("/error")
                || path.startsWith("/css/")
                || path.startsWith("/js/")
                || path.startsWith("/images/")
                || path.startsWith("/oauth2/")
                || path.startsWith("/login/oauth2/");
    }
}