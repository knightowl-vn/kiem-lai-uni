package com.universe.admin.contracts.dto;

public record AdminDashboardStats(
        long totalUsers,
        long activeUsers,
        long blockedUsers,
        long unverifiedUsers,
        long adminUsers,
        long localUsers,
        long googleUsers,
        long newUsersLast7Days
) {
}