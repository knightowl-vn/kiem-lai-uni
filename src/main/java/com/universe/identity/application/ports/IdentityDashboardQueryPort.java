package com.universe.identity.application.ports;

import com.universe.identity.contracts.admin.dto.AdminUserView;
import com.universe.identity.domain.AuthProvider;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;

import java.time.Instant;
import java.util.List;

public interface IdentityDashboardQueryPort {

    long countAllUsers();

    long countUsersByStatus(
            UserStatus status
    );

    long countUsersByRole(
            UserRole role
    );

    long countUsersByAuthProvider(
            AuthProvider authProvider
    );

    long countUsersCreatedSince(
            Instant createdSince
    );

    List<AdminUserView> findFiveRecentUsers();
}