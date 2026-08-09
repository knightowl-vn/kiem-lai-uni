package com.universe.identity.application.oauth;

public record GoogleUserInfo(
        String subject,
        String email,
        String displayName,
        String avatarUrl,
        boolean emailVerified
) {
}