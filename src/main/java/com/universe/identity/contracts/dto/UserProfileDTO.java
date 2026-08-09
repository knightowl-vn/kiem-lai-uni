package com.universe.identity.contracts.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record UserProfileDTO(
        UUID id,
        String displayName,
        String avatarUrl,
        String bio,
        LocalDateTime joinedAt
) {}