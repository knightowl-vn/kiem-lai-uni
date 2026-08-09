package com.universe.identity.infrastructure.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.universe.identity.domain.UserRole;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SpringDataUserJpaRepository
        extends JpaRepository<UserJpaEntity, String> {

    boolean existsByEmail(String email);

    Optional<UserJpaEntity> findByEmail(String email);

    Optional<UserJpaEntity>
    findByAuthProviderAndProviderSubject(
            String authProvider,
            String providerSubject
    );

    /*
     * Thống kê theo trạng thái:
     * ACTIVE, BLOCKED, UNVERIFIED...
     */
    long countByStatusIgnoreCase(
            String status
    );

    /*
     * Đếm theo quyền USER hoặc ADMIN.
     */
    long countByRole(
            UserRole role
    );

    /*
     * Đếm theo quyền và trạng thái.
     *
     * Ví dụ:
     * ADMIN + ACTIVE
     *
     * Dùng để kiểm tra còn bao nhiêu Admin
     * đang hoạt động trước khi khóa một Admin.
     */
    long countByRoleAndStatusIgnoreCase(
            UserRole role,
            String status
    );

    /*
     * Đếm theo phương thức đăng nhập:
     * LOCAL hoặc GOOGLE.
     */
    long countByAuthProviderIgnoreCase(
            String authProvider
    );

    /*
     * Đếm user được tạo từ một thời điểm trở đi.
     */
    long countByCreatedAtGreaterThanEqual(
            Instant createdAt
    );

    /*
     * Lấy 5 user mới nhất.
     */
    List<UserJpaEntity>
    findTop5ByOrderByCreatedAtDesc();

    /*
     * Danh sách người dùng trong Admin:
     * - tìm theo tên hoặc email
     * - lọc status
     * - lọc role
     * - lọc provider
     * - hỗ trợ phân trang
     */
    @Query("""
            SELECT user
            FROM UserJpaEntity user
            WHERE (
                :keyword IS NULL
                OR LOWER(user.email)
                    LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(user.displayName)
                    LIKE LOWER(CONCAT('%', :keyword, '%'))
            )
            AND (
                :status IS NULL
                OR UPPER(user.status) = UPPER(:status)
            )
            AND (
                :role IS NULL
                OR user.role = :role
            )
            AND (
                :authProvider IS NULL
                OR UPPER(user.authProvider)
                    = UPPER(:authProvider)
            )
            """)
    Page<UserJpaEntity> searchAdminUsers(
            @Param("keyword")
            String keyword,

            @Param("status")
            String status,

            @Param("role")
            UserRole role,

            @Param("authProvider")
            String authProvider,

            Pageable pageable
    );
}