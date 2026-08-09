package com.universe.identity.contracts.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record SessionDTO(
        UUID sessionId,
        String deviceInfo,
        String ipAddress,
        LocalDateTime lastActiveAt,
        Boolean isRevoked
) {}