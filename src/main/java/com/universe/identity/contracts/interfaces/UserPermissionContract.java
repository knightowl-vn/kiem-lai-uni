package com.universe.identity.contracts.interfaces;

import java.util.Optional;
import java.util.UUID;

/**
 * Public Contract cho việc kiểm tra vai trò người dùng.
 *
 * Hiện tại hệ thống sử dụng mô hình một role trên mỗi user:
 *
 * USER
 * ADMIN
 * SUPER_ADMIN
 *
 * Contract này có thể được mở rộng sang permission-based
 * authorization khi hệ thống triển khai RBAC động.
 */
public interface UserPermissionContract {

    /**
     * Lấy role hiện tại của người dùng.
     *
     * @param userId ID người dùng
     * @return tên role nếu người dùng tồn tại
     */
    Optional<String> findRoleByUserId(UUID userId);

    /**
     * Kiểm tra người dùng có đúng role yêu cầu hay không.
     *
     * @param userId ID người dùng
     * @param requiredRole role yêu cầu
     * @return true nếu role trùng khớp
     */
    boolean hasRole(
            UUID userId,
            String requiredRole
    );

    /**
     * Kiểm tra người dùng có ít nhất một trong các role yêu cầu.
     *
     * @param userId ID người dùng
     * @param requiredRoles danh sách role được chấp nhận
     * @return true nếu người dùng có một role phù hợp
     */
    boolean hasAnyRole(
            UUID userId,
            String... requiredRoles
    );
}