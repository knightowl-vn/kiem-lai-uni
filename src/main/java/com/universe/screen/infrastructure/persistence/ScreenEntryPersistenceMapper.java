package com.universe.screen.infrastructure.persistence;

import com.universe.screen.domain.ScreenEntry;
import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenPublicationStatus;
import com.universe.screen.domain.ScreenTimelineMode;

import java.util.Objects;
import java.util.UUID;

/**
 * Two-way persistence mapper for {@link ScreenEntry}.
 */
public final class ScreenEntryPersistenceMapper {

    private ScreenEntryPersistenceMapper() {
    }

    public static ScreenEntryJpaEntity toEntity(ScreenEntry domain) {
        Objects.requireNonNull(domain, "ScreenEntry domain object cannot be null.");
        return new ScreenEntryJpaEntity(
                domain.getId().toString(),
                domain.getTitleId().toString(),
                domain.getSlug(),
                domain.getType().name(),
                domain.getNumber(),
                domain.getTitle(),
                domain.getSynopsis(),
                toNullableString(domain.getVideoMediaAssetId()),
                toNullableString(domain.getThumbnailMediaAssetId()),
                domain.getDurationSeconds(),
                domain.getStatus().name(),
                domain.getPublishedAt(),
                domain.getCreatedAt(),
                domain.getUpdatedAt(),
                domain.getIntroMode().name(),
                domain.getIntroStartSeconds(),
                domain.getIntroEndSeconds(),
                domain.getOutroMode().name(),
                domain.getOutroStartSeconds()
        );
    }

    public static void updateEntity(ScreenEntryJpaEntity entity, ScreenEntry domain) {
        Objects.requireNonNull(entity, "ScreenEntryJpaEntity cannot be null.");
        Objects.requireNonNull(domain, "ScreenEntry domain object cannot be null.");
        entity.setTitleId(domain.getTitleId().toString());
        entity.setSlug(domain.getSlug());
        entity.setType(domain.getType().name());
        entity.setNumber(domain.getNumber());
        entity.setTitle(domain.getTitle());
        entity.setSynopsis(domain.getSynopsis());
        entity.setVideoMediaAssetId(toNullableString(domain.getVideoMediaAssetId()));
        entity.setThumbnailMediaAssetId(toNullableString(domain.getThumbnailMediaAssetId()));
        entity.setDurationSeconds(domain.getDurationSeconds());
        entity.setStatus(domain.getStatus().name());
        entity.setPublishedAt(domain.getPublishedAt());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        entity.setIntroMode(domain.getIntroMode().name());
        entity.setIntroStartSeconds(domain.getIntroStartSeconds());
        entity.setIntroEndSeconds(domain.getIntroEndSeconds());
        entity.setOutroMode(domain.getOutroMode().name());
        entity.setOutroStartSeconds(domain.getOutroStartSeconds());
    }

    public static ScreenEntry toDomain(ScreenEntryJpaEntity entity) {
        Objects.requireNonNull(entity, "ScreenEntryJpaEntity cannot be null.");
        ScreenTimelineMode introMode = ScreenTimelineMode.valueOf(
                Objects.requireNonNull(entity.getIntroMode(), "Persisted introMode cannot be null.")
        );
        ScreenTimelineMode outroMode = ScreenTimelineMode.valueOf(
                Objects.requireNonNull(entity.getOutroMode(), "Persisted outroMode cannot be null.")
        );
        return new ScreenEntry(
                UUID.fromString(entity.getId()),
                UUID.fromString(entity.getTitleId()),
                entity.getSlug(),
                ScreenEntryType.valueOf(entity.getType()),
                entity.getNumber(),
                entity.getTitle(),
                entity.getSynopsis(),
                toNullableUuid(entity.getVideoMediaAssetId()),
                toNullableUuid(entity.getThumbnailMediaAssetId()),
                entity.getDurationSeconds(),
                ScreenPublicationStatus.valueOf(entity.getStatus()),
                entity.getPublishedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                introMode,
                entity.getIntroStartSeconds(),
                entity.getIntroEndSeconds(),
                outroMode,
                entity.getOutroStartSeconds()
        );
    }

    private static String toNullableString(UUID uuid) {
        return uuid != null ? uuid.toString() : null;
    }

    private static UUID toNullableUuid(String uuidString) {
        return uuidString == null ? null : UUID.fromString(uuidString);
    }
}
