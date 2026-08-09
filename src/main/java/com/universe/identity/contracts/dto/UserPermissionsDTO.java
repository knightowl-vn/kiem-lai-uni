package com.universe.identity.contracts.dto;

import java.util.List;
import java.util.UUID;

public record UserPermissionsDTO(
        UUID userId,
        String roleName,
        List<String> permissions
) {}