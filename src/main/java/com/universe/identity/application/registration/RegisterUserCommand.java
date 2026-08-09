package com.universe.identity.application.registration;

public record RegisterUserCommand(
        String email,
        String password,
        String displayName
) {}