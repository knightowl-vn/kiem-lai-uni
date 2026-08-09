package com.universe.identity.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SpringDataPasswordResetTokenJpaRepository
        extends JpaRepository<PasswordResetTokenJpaEntity, String> {

    /**
     * Tìm token theo token hash.
     *
     * Method này chưa kiểm tra:
     * - usedAt
     * - revokedAt
     * - expiresAt
     */
    Optional<PasswordResetTokenJpaEntity> findByTokenHash(
            String tokenHash
    );

    /**
     * Tìm token chưa được sử dụng và chưa bị thu hồi.
     *
     * Thời hạn expiresAt vẫn nên được kiểm tra thêm
     * trong adapter hoặc application service.
     */
    Optional<PasswordResetTokenJpaEntity>
    findByTokenHashAndUsedAtIsNullAndRevokedAtIsNull(
            String tokenHash
    );

    /**
     * Lấy lịch sử token của một user,
     * mới nhất đứng trước.
     */
    List<PasswordResetTokenJpaEntity>
    findAllByUserIdOrderByCreatedAtDesc(
            String userId
    );

    /**
     * Lấy toàn bộ token chưa dùng và chưa bị thu hồi
     * của một user.
     */
    List<PasswordResetTokenJpaEntity>
    findAllByUserIdAndUsedAtIsNullAndRevokedAtIsNull(
            String userId
    );

    /**
     * Thu hồi toàn bộ token còn hiệu lực của một user.
     *
     * Chỉ thu hồi token:
     * - chưa sử dụng;
     * - chưa bị thu hồi;
     * - chưa hết hạn tại thời điểm revokedAt.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PasswordResetTokenJpaEntity token
               set token.revokedAt = :revokedAt
             where token.userId = :userId
               and token.usedAt is null
               and token.revokedAt is null
               and token.expiresAt > :revokedAt
            """)
    int revokeAllActiveByUserId(
            @Param("userId") String userId,
            @Param("revokedAt") Instant revokedAt
    );

    /**
     * Xóa token đã hết hạn, đã dùng hoặc đã bị thu hồi.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from PasswordResetTokenJpaEntity token
             where token.expiresAt < :cutoff
                or token.usedAt is not null
                or token.revokedAt is not null
            """)
    int deleteExpiredOrInactiveTokens(
            @Param("cutoff") Instant cutoff
    );
}