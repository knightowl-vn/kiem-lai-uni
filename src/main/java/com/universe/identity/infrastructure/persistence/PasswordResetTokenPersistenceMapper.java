package com.universe.identity.infrastructure.persistence;

import com.universe.identity.domain.PasswordResetToken;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class PasswordResetTokenPersistenceMapper {

    /**
     * Cập nhật dữ liệu từ domain vào JPA entity.
     *
     * Không gán persistenceVersion vì trường đó
     * được Hibernate tự quản lý bằng @Version.
     */
    public void updateJpaEntity(
            PasswordResetToken token,
            PasswordResetTokenJpaEntity entity
    ) {
        if (token == null) {
            throw new IllegalArgumentException(
                    "PasswordResetToken không được null."
            );
        }

        if (entity == null) {
            throw new IllegalArgumentException(
                    "PasswordResetTokenJpaEntity không được null."
            );
        }

        entity.setId(
                token.getId().toString()
        );

        entity.setUserId(
                token.getUserId().toString()
        );

        entity.setTokenHash(
                token.getTokenHash()
        );

        entity.setExpiresAt(
                token.getExpiresAt()
        );

        entity.setCreatedAt(
                token.getCreatedAt()
        );

        entity.setRequestedIp(
                token.getRequestedIp()
        );

        entity.setUserAgent(
                token.getUserAgent()
        );

        entity.setUsedAt(
                token.getUsedAt()
        );

        entity.setRevokedAt(
                token.getRevokedAt()
        );
    }

    /**
     * Dùng khi tạo entity mới.
     */
    public PasswordResetTokenJpaEntity toJpaEntity(
            PasswordResetToken token
    ) {
        PasswordResetTokenJpaEntity entity =
                new PasswordResetTokenJpaEntity();

        updateJpaEntity(
                token,
                entity
        );

        return entity;
    }

    /**
     * Chuyển từ JPA entity về domain.
     */
    public PasswordResetToken toDomain(
            PasswordResetTokenJpaEntity entity
    ) {
        if (entity == null) {
            throw new IllegalArgumentException(
                    "PasswordResetTokenJpaEntity không được null."
            );
        }

        return new PasswordResetToken(
                parseUuid(
                        entity.getId(),
                        "Password reset token ID"
                ),
                parseUuid(
                        entity.getUserId(),
                        "User ID"
                ),
                entity.getTokenHash(),
                entity.getExpiresAt(),
                entity.getCreatedAt(),
                entity.getRequestedIp(),
                entity.getUserAgent(),
                entity.getUsedAt(),
                entity.getRevokedAt()
        );
    }

    private UUID parseUuid(
            String value,
            String fieldName
    ) {
        if (value == null
                || value.isBlank()) {

            throw new IllegalStateException(
                    fieldName
                            + " trong database không hợp lệ."
            );
        }

        try {
            return UUID.fromString(
                    value.trim()
            );

        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    fieldName
                            + " không đúng định dạng UUID: "
                            + value,
                    exception
            );
        }
    }
}