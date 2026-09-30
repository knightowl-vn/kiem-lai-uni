package com.universe.identity.infrastructure.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.universe.identity.domain.UserRole;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SpringDataUserJpaRepository
        extends JpaRepository<UserJpaEntity, String> {

    boolean existsByEmail(String email);

    Optional<UserJpaEntity> findByEmail(String email);

    boolean existsByPublicHandle(String publicHandle);

    Optional<UserJpaEntity> findByPublicHandle(String publicHandle);

    @Query("""
            SELECT
                user.id AS userId,
                user.email AS normalizedEmail,
                user.displayName AS displayName,
                user.avatarUrl AS avatarUrl,
                user.status AS status,
                user.role AS role
            FROM UserJpaEntity user
            WHERE user.email = :normalizedEmail
            """)
    Optional<AuthenticatedRequestIdentityProjection> findRequestIdentityByEmail(
            @Param("normalizedEmail") String normalizedEmail
    );

    @Query("""
            SELECT
                user.id AS userId,
                user.displayName AS displayName,
                user.avatarUrl AS avatarUrl,
                user.publicHandle AS publicHandle
            FROM UserJpaEntity user
            WHERE user.id IN :ids
            """)
    List<UserPublicProfileProjection> findPublicProfilesByIdIn(
            @Param("ids") Collection<String> ids
    );

    @Query("""
            SELECT
                user.id AS userId,
                user.displayName AS displayName,
                user.avatarUrl AS avatarUrl,
                user.publicHandle AS publicHandle
            FROM UserJpaEntity user
            WHERE user.publicHandle = :publicHandle
              AND user.status = 'ACTIVE'
            """)
    Optional<UserPublicProfileProjection> findActivePublicProfileByHandle(
            @Param("publicHandle") String publicHandle
    );

    @Query("""
            SELECT
                user.id AS userId,
                user.displayName AS displayName,
                user.avatarUrl AS avatarUrl,
                user.publicHandle AS publicHandle,
                user.bio AS bio
            FROM UserJpaEntity user
            WHERE user.publicHandle = :publicHandle
              AND user.status = 'ACTIVE'
            """)
    Optional<UserPublicProfileDetailsProjection> findActivePublicProfileDetailsByHandle(
            @Param("publicHandle") String publicHandle
    );

    @Query("""
            SELECT
                user.id AS userId,
                user.displayName AS displayName,
                user.avatarUrl AS avatarUrl,
                user.publicHandle AS publicHandle
            FROM UserJpaEntity user
            WHERE user.status = 'ACTIVE'
              AND (
                  user.publicHandle LIKE CONCAT(:handlePrefix, '%') ESCAPE '\\'
                  OR LOWER(user.displayName) LIKE CONCAT('%', :escapedNameContains, '%') ESCAPE '\\'
              )
            ORDER BY
                CASE
                    WHEN user.publicHandle = :exactHandle THEN 1
                    WHEN user.publicHandle LIKE CONCAT(:handlePrefix, '%') ESCAPE '\\' THEN 2
                    WHEN LOWER(user.displayName) = :exactName THEN 3
                    WHEN LOWER(user.displayName) LIKE CONCAT(:namePrefix, '%') ESCAPE '\\' THEN 4
                    ELSE 5
                END ASC,
                user.displayName ASC,
                user.publicHandle ASC,
                user.id ASC
            """)
    List<UserPublicProfileProjection> searchActivePublicUsers(
            @Param("exactHandle") String exactHandle,
            @Param("handlePrefix") String handlePrefix,
            @Param("exactName") String exactName,
            @Param("namePrefix") String namePrefix,
            @Param("escapedNameContains") String escapedNameContains,
            Pageable pageable
    );

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
