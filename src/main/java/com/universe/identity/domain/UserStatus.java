package com.universe.identity.domain;

/**
 * Trạng thái nghiệp vụ của tài khoản người dùng.
 *
 * Các giá trị phải đồng bộ với:
 * - identity_users.status trong database;
 * - CHECK constraint của Flyway;
 * - AdminUserService;
 * - DatabaseUserDetailsService;
 * - AccountStatusFilter.
 */
public enum UserStatus {

    /**
     * Tài khoản hoạt động bình thường.
     */
    ACTIVE,

    /**
     * Tài khoản bị khóa nhưng có thể được Admin mở lại.
     */
    BLOCKED,

    /**
     * Tài khoản chưa hoàn tất xác minh hoặc kích hoạt.
     */
    UNVERIFIED,

    /**
     * Tài khoản bị cấm vĩnh viễn.
     */
    BANNED;

    /**
     * Tài khoản có được phép sử dụng hệ thống hay không.
     */
    public boolean isActive() {
        return this == ACTIVE;
    }

    /**
     * Tài khoản có đang bị khóa tạm thời hay không.
     */
    public boolean isBlocked() {
        return this == BLOCKED;
    }

    /**
     * Tài khoản có bị cấm vĩnh viễn hay không.
     */
    public boolean isBanned() {
        return this == BANNED;
    }

    /**
     * Tài khoản có cần bị từ chối đăng nhập hay không.
     */
    public boolean cannotLogin() {
        return this != ACTIVE;
    }
}