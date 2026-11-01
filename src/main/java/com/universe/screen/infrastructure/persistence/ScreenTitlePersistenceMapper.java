package com.universe.screen.infrastructure.persistence;

import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenPublicationStatus;
import com.universe.screen.domain.ScreenStructure;
import com.universe.screen.domain.ScreenTitle;

import java.util.Objects;
import java.util.UUID;

/**
 * Two-way persistence mapper for {@link ScreenTitle}.
 */
public final class ScreenTitlePersistenceMapper {

    private ScreenTitlePersistenceMapper() {
    }

    public static ScreenTitleJpaEntity toEntity(ScreenTitle domain) {
        Objects.requireNonNull(domain, "ScreenTitle domain object cannot be null.");
        return new ScreenTitleJpaEntity(
                domain.getId().toString(),
                domain.getSlug(),
                domain.getTitle(),
                domain.getOriginalTitle(),
                domain.getSynopsis(),
                domain.getStructure().name(),
                domain.getPresentation().name(),
                toNullableString(domain.getPosterMediaAssetId()),
                toNullableString(domain.getBackdropMediaAssetId()),
                domain.getStatus().name(),
                domain.getPublishedAt(),
                domain.getCreatedAt(),
                domain.getUpdatedAt(),
                domain.getTotalEpisodeCount()
        );
    }

    public static void updateEntity(ScreenTitleJpaEntity entity, ScreenTitle domain) {
        Objects.requireNonNull(entity, "ScreenTitleJpaEntity cannot be null.");
        Objects.requireNonNull(domain, "ScreenTitle domain object cannot be null.");
        entity.setSlug(domain.getSlug());
        entity.setTitle(domain.getTitle());
        entity.setOriginalTitle(domain.getOriginalTitle());
        entity.setSynopsis(domain.getSynopsis());
        entity.setStructure(domain.getStructure().name());
        entity.setPresentation(domain.getPresentation().name());
        entity.setPosterMediaAssetId(toNullableString(domain.getPosterMediaAssetId()));
        entity.setBackdropMediaAssetId(toNullableString(domain.getBackdropMediaAssetId()));
        entity.setStatus(domain.getStatus().name());
        entity.setPublishedAt(domain.getPublishedAt());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        entity.setTotalEpisodeCount(domain.getTotalEpisodeCount());
    }

    public static ScreenTitle toDomain(ScreenTitleJpaEntity entity) {
        Objects.requireNonNull(entity, "ScreenTitleJpaEntity cannot be null.");
        return new ScreenTitle(
                UUID.fromString(entity.getId()),
                entity.getSlug(),
                entity.getTitle(),
                entity.getOriginalTitle(),
                entity.getSynopsis(),
                ScreenStructure.valueOf(entity.getStructure()),
                ScreenPresentation.valueOf(entity.getPresentation()),
                toNullableUuid(entity.getPosterMediaAssetId()),
                toNullableUuid(entity.getBackdropMediaAssetId()),
                ScreenPublicationStatus.valueOf(entity.getStatus()),
                entity.getPublishedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getTotalEpisodeCount()
        );
    }

    private static String toNullableString(UUID uuid) {
        return uuid != null ? uuid.toString() : null;
    }

    private static UUID toNullableUuid(String uuidString) {
        return uuidString == null ? null : UUID.fromString(uuidString);
    }
}
