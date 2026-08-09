package com.universe.identity.application.ports;

import com.universe.identity.contracts.admin.dto.AdminUserDetailView;
import com.universe.identity.contracts.admin.dto.AdminUserView;
import com.universe.identity.domain.UserRole;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

public interface IdentityAdminQueryPort {

    Page<AdminUserView> searchUsers(
            String keyword,
            String status,
            UserRole role,
            String authProvider,
            Pageable pageable
    );

    Optional<AdminUserDetailView> findUserDetail(
            UUID userId
    );

    long countUsersByRole(
            UserRole role
    );

    long countUsersByRoleAndStatus(
            UserRole role,
            String status
    );
}