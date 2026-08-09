package com.universe.identity.contracts.dto;

public record TokenPairDTO(
        String accessToken,
        String refreshToken,
        Long expiresIn
) {}