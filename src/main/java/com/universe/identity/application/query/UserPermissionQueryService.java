package com.universe.identity.application.query;

import com.universe.identity.application.ports.UserRepositoryPort;
import com.universe.identity.contracts.interfaces.UserPermissionContract;
import com.universe.identity.domain.UserRole;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class UserPermissionQueryService
        implements UserPermissionContract {

    private final UserRepositoryPort
            userRepositoryPort;

    public UserPermissionQueryService(
            UserRepositoryPort userRepositoryPort
    ) {
        this.userRepositoryPort =
                Objects.requireNonNull(
                        userRepositoryPort,
                        "User repository port không được để trống."
                );
    }

    @Override
    public Optional<String> findRoleByUserId(
            UUID userId
    ) {
        if (userId == null) {
            return Optional.empty();
        }

        return userRepositoryPort
                .findById(userId)
                .map(user ->
                        user.getRole().name()
                );
    }

    @Override
    public boolean hasRole(
            UUID userId,
            String requiredRole
    ) {
        if (userId == null
                || requiredRole == null
                || requiredRole.isBlank()) {

            return false;
        }

        String normalizedRequiredRole =
                normalizeRole(requiredRole);

        return findRoleByUserId(userId)
                .map(currentRole ->
                        satisfiesRole(
                                currentRole,
                                normalizedRequiredRole
                        )
                )
                .orElse(false);
    }

    @Override
    public boolean hasAnyRole(
            UUID userId,
            String... requiredRoles
    ) {
        if (userId == null
                || requiredRoles == null
                || requiredRoles.length == 0) {

            return false;
        }

        return Arrays.stream(requiredRoles)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(role ->
                        !role.isBlank()
                )
                .anyMatch(role ->
                        hasRole(
                                userId,
                                role
                        )
                );
    }

    private boolean satisfiesRole(
            String currentRole,
            String requiredRole
    ) {
        return switch (requiredRole) {
            case "USER" ->
                    UserRole.USER.name()
                            .equals(currentRole)
                    || UserRole.ADMIN.name()
                            .equals(currentRole)
                    || UserRole.SUPER_ADMIN.name()
                            .equals(currentRole);

            case "ADMIN" ->
                    UserRole.ADMIN.name()
                            .equals(currentRole)
                    || UserRole.SUPER_ADMIN.name()
                            .equals(currentRole);

            case "SUPER_ADMIN" ->
                    UserRole.SUPER_ADMIN.name()
                            .equals(currentRole);

            default ->
                    false;
        };
    }

    private String normalizeRole(
            String role
    ) {
        return role.trim()
                .toUpperCase(Locale.ROOT);
    }
}