package com.universe.identity.infrastructure.persistence;

import com.universe.identity.application.ports.IdentityAdminQueryPort;
import com.universe.identity.contracts.admin.dto.AdminUserDetailView;
import com.universe.identity.contracts.admin.dto.AdminUserView;
import com.universe.identity.domain.UserRole;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class IdentityAdminQueryAdapter
        implements IdentityAdminQueryPort {

    private final SpringDataUserJpaRepository
            userRepository;

    public IdentityAdminQueryAdapter(
            SpringDataUserJpaRepository userRepository
    ) {
        this.userRepository = userRepository;
    }

    @Override
    public Page<AdminUserView> searchUsers(
            String keyword,
            String status,
            UserRole role,
            String authProvider,
            Pageable pageable
    ) {
        return userRepository
                .searchAdminUsers(
                        keyword,
                        status,
                        role,
                        authProvider,
                        pageable
                )
                .map(this::toView);
    }

    @Override
    public Optional<AdminUserDetailView> findUserDetail(
            UUID userId
    ) {
        if (userId == null) {
            return Optional.empty();
        }

        return userRepository
                .findById(userId.toString())
                .map(this::toDetailView);
    }

    @Override
    public long countUsersByRole(
            UserRole role
    ) {
        if (role == null) {
            throw new IllegalArgumentException(
                    "Vai trò người dùng không được để trống."
            );
        }

        return userRepository.countByRole(role);
    }

    @Override
    public long countUsersByRoleAndStatus(
            UserRole role,
            String status
    ) {
        if (role == null) {
            throw new IllegalArgumentException(
                    "Vai trò người dùng không được để trống."
            );
        }

        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException(
                    "Trạng thái người dùng không được để trống."
            );
        }

        return userRepository
                .countByRoleAndStatusIgnoreCase(
                        role,
                        status.trim()
                );
    }

    private AdminUserView toView(
            UserJpaEntity entity
    ) {
        UserRole role =
                entity.getRole() == null
                        ? UserRole.USER
                        : entity.getRole();

        return new AdminUserView(
                entity.getId(),
                entity.getDisplayName(),
                entity.getEmail(),
                entity.getAvatarUrl(),
                entity.getStatus(),
                role.name(),
                entity.getAuthProvider(),
                entity.getCreatedAt()
        );
    }

    private AdminUserDetailView toDetailView(
            UserJpaEntity entity
    ) {
        UserRole role =
                entity.getRole() == null
                        ? UserRole.USER
                        : entity.getRole();

        boolean hasLocalPassword =
                entity.getPasswordHash() != null
                        && !entity.getPasswordHash().isBlank();

        return new AdminUserDetailView(
                entity.getId(),
                entity.getDisplayName(),
                entity.getEmail(),
                entity.getAvatarUrl(),
                entity.getBio(),
                entity.getStatus(),
                role.name(),
                entity.getAuthProvider(),
                hasLocalPassword,
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}