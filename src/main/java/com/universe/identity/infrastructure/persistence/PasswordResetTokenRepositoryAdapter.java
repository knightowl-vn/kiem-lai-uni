package com.universe.identity.infrastructure.persistence;

import com.universe.identity.application.ports.PasswordResetTokenRepositoryPort;
import com.universe.identity.domain.PasswordResetToken;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PasswordResetTokenRepositoryAdapter
        implements PasswordResetTokenRepositoryPort {

    private final SpringDataPasswordResetTokenJpaRepository
            jpaRepository;

    private final PasswordResetTokenPersistenceMapper
            mapper;

    public PasswordResetTokenRepositoryAdapter(
            SpringDataPasswordResetTokenJpaRepository jpaRepository,
            PasswordResetTokenPersistenceMapper mapper
    ) {
        this.jpaRepository =
                jpaRepository;

        this.mapper =
                mapper;
    }

    @Override
    public Optional<PasswordResetToken>
    findActiveByTokenHash(
            String tokenHash
    ) {
        if (tokenHash == null
                || tokenHash.isBlank()) {

            return Optional.empty();
        }

        Instant now =
                Instant.now();

        return jpaRepository
                .findByTokenHash(
                        tokenHash.trim()
                )
                .filter(entity ->
                        entity.getUsedAt() == null
                                && entity.getRevokedAt() == null
                                && entity.getExpiresAt() != null
                                && entity.getExpiresAt()
                                        .isAfter(now)
                )
                .map(mapper::toDomain);
    }

    @Override
    public void save(
            PasswordResetToken token
    ) {
        if (token == null) {
            throw new IllegalArgumentException(
                    "PasswordResetToken không được null."
            );
        }

        String tokenId =
                token.getId().toString();

        PasswordResetTokenJpaEntity entity =
                jpaRepository
                        .findById(tokenId)
                        .orElseGet(
                                PasswordResetTokenJpaEntity::new
                        );

        mapper.updateJpaEntity(
                token,
                entity
        );

        jpaRepository.save(entity);
    }

    @Override
    public void revokeAllActiveByUserId(
            UUID userId,
            Instant revokedAt
    ) {
        if (userId == null) {
            throw new IllegalArgumentException(
                    "User ID không được để trống."
            );
        }

        if (revokedAt == null) {
            throw new IllegalArgumentException(
                    "Thời điểm thu hồi token không được để trống."
            );
        }

        jpaRepository.revokeAllActiveByUserId(
                userId.toString(),
                revokedAt
        );
    }
}