package com.universe.identity.application.query;

import com.universe.identity.application.ports.IdentityDashboardQueryPort;
import com.universe.identity.contracts.admin.dto.IdentityDashboardSnapshot;
import com.universe.identity.contracts.interfaces.IdentityDashboardContract;
import com.universe.identity.domain.AuthProvider;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

@Service
@Transactional(readOnly = true)
public class IdentityDashboardQueryService
        implements IdentityDashboardContract {

    private final IdentityDashboardQueryPort
            dashboardQueryPort;

    public IdentityDashboardQueryService(
            IdentityDashboardQueryPort dashboardQueryPort
    ) {
        this.dashboardQueryPort =
                Objects.requireNonNull(
                        dashboardQueryPort,
                        "Dashboard query port không được để trống."
                );
    }

    @Override
    public IdentityDashboardSnapshot getSnapshot(
            Instant createdSince
    ) {
        Objects.requireNonNull(
                createdSince,
                "Thời điểm thống kê không được để trống."
        );

        long adminUsers =
                dashboardQueryPort.countUsersByRole(
                        UserRole.ADMIN
                )
                + dashboardQueryPort.countUsersByRole(
                        UserRole.SUPER_ADMIN
                );

        return new IdentityDashboardSnapshot(
                dashboardQueryPort.countAllUsers(),

                dashboardQueryPort.countUsersByStatus(
                        UserStatus.ACTIVE
                ),

                dashboardQueryPort.countUsersByStatus(
                        UserStatus.BLOCKED
                ),

                dashboardQueryPort.countUsersByStatus(
                        UserStatus.UNVERIFIED
                ),

                adminUsers,

                dashboardQueryPort.countUsersByAuthProvider(
                        AuthProvider.LOCAL
                ),

                dashboardQueryPort.countUsersByAuthProvider(
                        AuthProvider.GOOGLE
                ),

                dashboardQueryPort.countUsersCreatedSince(
                        createdSince
                ),

                dashboardQueryPort.findFiveRecentUsers()
        );
    }
}