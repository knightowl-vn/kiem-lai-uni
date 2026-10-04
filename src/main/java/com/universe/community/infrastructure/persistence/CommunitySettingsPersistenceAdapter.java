package com.universe.community.infrastructure.persistence;

import com.universe.community.application.port.out.CommunitySettingsRepositoryPort;
import com.universe.community.domain.CommunityPublicationMode;
import com.universe.community.domain.CommunitySettings;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Persistence adapter implementing {@link CommunitySettingsRepositoryPort} using Spring Data JPA.
 */
@Component
@Transactional(readOnly = true)
public class CommunitySettingsPersistenceAdapter implements CommunitySettingsRepositoryPort {

    private final SpringDataCommunitySettingsJpaRepository settingsRepository;

    public CommunitySettingsPersistenceAdapter(SpringDataCommunitySettingsJpaRepository settingsRepository) {
        this.settingsRepository = Objects.requireNonNull(settingsRepository, "SpringDataCommunitySettingsJpaRepository cannot be null.");
    }

    @Override
    public CommunitySettings getSettings() {
        CommunitySettingsJpaEntity entity = settingsRepository.findById(CommunitySettings.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("CommunitySettings singleton row 'DEFAULT' not found in database."));
        return toDomain(entity);
    }

    @Override
    @Transactional
    public boolean updateSettings(
            CommunityPublicationMode newMode,
            long expectedVersion,
            UUID updatedByUserId,
            Instant updatedAt
    ) {
        Objects.requireNonNull(newMode, "CommunityPublicationMode cannot be null.");
        Objects.requireNonNull(updatedByUserId, "UpdatedByUserId cannot be null.");
        Objects.requireNonNull(updatedAt, "UpdatedAt cannot be null.");

        int rows = settingsRepository.updateSettingsOptimistic(
                CommunitySettings.SINGLETON_ID,
                newMode.name(),
                expectedVersion,
                updatedByUserId.toString(),
                updatedAt
        );
        return rows > 0;
    }

    private CommunitySettings toDomain(CommunitySettingsJpaEntity entity) {
        return new CommunitySettings(
                entity.getId(),
                CommunityPublicationMode.valueOf(entity.getPublicationMode()),
                entity.getVersion(),
                entity.getUpdatedAt(),
                entity.getUpdatedByUserId() != null ? UUID.fromString(entity.getUpdatedByUserId()) : null
        );
    }
}
