package com.universe.media.infrastructure.persistence;

import com.universe.media.application.ports.MediaAssetContentDeliveryQueryPort;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaVisibility;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class MediaAssetContentDeliveryQueryPersistenceAdapter
        implements MediaAssetContentDeliveryQueryPort {

    private final SpringDataMediaAssetContentDeliveryQueryRepository repository;

    public MediaAssetContentDeliveryQueryPersistenceAdapter(
            SpringDataMediaAssetContentDeliveryQueryRepository repository
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    @Override
    public Optional<MediaAssetContentDeliverySnapshot> findByAssetId(UUID assetId) {
        Objects.requireNonNull(assetId, "Media asset ID cannot be null.");
        return repository.findContentDelivery(assetId.toString()).map(this::toSnapshot);
    }

    private MediaAssetContentDeliverySnapshot toSnapshot(
            MediaAssetContentDeliveryProjection projection
    ) {
        return new MediaAssetContentDeliverySnapshot(
                toUuid(projection.getAssetId()),
                MediaAssetStatus.valueOf(projection.getStatus()),
                MediaVisibility.valueOf(projection.getVisibility()),
                projection.getCurrentVersionNumber(),
                toUuid(projection.getVersionId()),
                toUuid(projection.getVersionAssetId()),
                projection.getVersionNumber(),
                projection.getStorageProviderId(),
                projection.getStorageKey(),
                projection.getContentHash(),
                projection.getMimeType(),
                projection.getSizeBytes()
        );
    }

    private UUID toUuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }
}
