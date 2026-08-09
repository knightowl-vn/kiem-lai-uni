package com.universe.identity.contracts.admin.dto;

import java.util.List;

public record IdentityDashboardSnapshot(
        long totalUsers,
        long activeUsers,
        long blockedUsers,
        long unverifiedUsers,
        long adminUsers,
        long localUsers,
        long googleUsers,
        long newUsersSince,
        List<AdminUserView> recentUsers
) {
    public IdentityDashboardSnapshot {
        recentUsers =
                recentUsers == null
                        ? List.of()
                        : List.copyOf(recentUsers);
    }
}