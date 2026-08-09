package com.universe.shared.security;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

@Component
public class AuthenticatedEmailResolver {

    public Optional<String> resolve(
            Authentication authentication
    ) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {

            return Optional.empty();
        }

        Object principal =
                authentication.getPrincipal();

        /*
         * Đăng nhập bằng Google OAuth.
         *
         * authentication.getName() có thể là Google subject,
         * nên cần lấy email từ attribute của OAuth2User.
         */
        if (principal instanceof OAuth2User oauth2User) {
            String oauthEmail =
                    oauth2User.getAttribute(
                            "email"
                    );

            return normalize(
                    oauthEmail
            );
        }

        /*
         * Đăng nhập local hoặc remember-me.
         */
        return normalize(
                authentication.getName()
        );
    }

    public String require(
            Authentication authentication
    ) {
        return resolve(authentication)
                .orElseThrow(
                        () -> new IllegalStateException(
                                "Không xác định được người dùng đang đăng nhập."
                        )
                );
    }

    private Optional<String> normalize(
            String email
    ) {
        if (email == null
                || email.isBlank()) {

            return Optional.empty();
        }

        return Optional.of(
                email.trim()
                        .toLowerCase(
                                Locale.ROOT
                        )
        );
    }
}