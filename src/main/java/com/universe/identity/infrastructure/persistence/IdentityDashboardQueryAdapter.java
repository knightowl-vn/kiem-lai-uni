package com.universe.identity.infrastructure.persistence;

import com.universe.identity.application.ports.IdentityDashboardQueryPort;
import com.universe.identity.contracts.admin.dto.AdminUserView;
import com.universe.identity.domain.AuthProvider;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Repository
public class IdentityDashboardQueryAdapter
        implements IdentityDashboardQueryPort {

    private final SpringDataUserJpaRepository
            userRepository;

    public IdentityDashboardQueryAdapter(
            SpringDataUserJpaRepository userRepository
    ) {
        this.userRepository =
                Objects.requireNonNull(
                        userRepository,
                        "User repository không được để trống."
                );
    }

    @Override
    public long countAllUsers() {
        return userRepository.count();
    }

    @Override
    public long countUsersByStatus(
            UserStatus status
    ) {
        if (status == null) {
            throw new IllegalArgumentException(
                    "Trạng thái người dùng không được để trống."
            );
        }

        return userRepository
                .countByStatusIgnoreCase(
                        status.name()
                );
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
    public long countUsersByAuthProvider(
            AuthProvider authProvider
    ) {
        if (authProvider == null) {
            throw new IllegalArgumentException(
                    "Phương thức xác thực không được để trống."
            );
        }

        return userRepository
                .countByAuthProviderIgnoreCase(
                        authProvider.name()
                );
    }

    @Override
    public long countUsersCreatedSince(
            Instant createdSince
    ) {
        if (createdSince == null) {
            throw new IllegalArgumentException(
                    "Thời điểm thống kê không được để trống."
            );
        }

        return userRepository
                .countByCreatedAtGreaterThanEqual(
                        createdSince
                );
    }

    @Override
    public List<AdminUserView> findFiveRecentUsers() {
        return userRepository
                .findTop5ByOrderByCreatedAtDesc()
                .stream()
                .map(this::toView)
                .toList();
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
}