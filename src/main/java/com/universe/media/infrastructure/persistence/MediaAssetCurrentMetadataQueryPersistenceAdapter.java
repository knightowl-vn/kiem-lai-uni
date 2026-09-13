package com.universe.media.infrastructure.persistence;

import com.universe.media.application.ports.MediaAssetCurrentMetadataQueryPort;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaVisibility;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class MediaAssetCurrentMetadataQueryPersistenceAdapter
        implements MediaAssetCurrentMetadataQueryPort {

    private final SpringDataMediaAssetCurrentMetadataQueryRepository repository;

    public MediaAssetCurrentMetadataQueryPersistenceAdapter(
            SpringDataMediaAssetCurrentMetadataQueryRepository repository
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    @Override
    public Optional<MediaAssetCurrentMetadataSnapshot> findByAssetId(UUID assetId) {
        Objects.requireNonNull(assetId, "Media asset ID cannot be null.");
        return repository.findCurrentMetadata(assetId.toString()).map(this::toSnapshot);
    }

    private MediaAssetCurrentMetadataSnapshot toSnapshot(
            MediaAssetCurrentMetadataProjection projection
    ) {
        return new MediaAssetCurrentMetadataSnapshot(
                toUuid(projection.getAssetId()),
                MediaAssetStatus.valueOf(projection.getStatus()),
                MediaVisibility.valueOf(projection.getVisibility()),
                projection.getCurrentVersionNumber(),
                toUuid(projection.getDeclaredCurrentVersionAssetId()),
                projection.getDeclaredCurrentVersionNumber()
        );
    }

    private UUID toUuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }
}
