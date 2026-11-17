package com.universe.media.infrastructure.persistence;

import com.universe.media.application.ports.MediaPublicImageBatchDeliveryQueryPort;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;
import com.universe.media.domain.StorageProviderId;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Component
public class MediaPublicImageBatchDeliveryQueryPersistenceAdapter
        implements MediaPublicImageBatchDeliveryQueryPort {

    static final int BATCH_CHUNK_SIZE = 500;

    private final SpringDataMediaPublicImageBatchDeliveryQueryRepository repository;

    public MediaPublicImageBatchDeliveryQueryPersistenceAdapter(
            SpringDataMediaPublicImageBatchDeliveryQueryRepository repository
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    @Override
    public List<MediaPublicImageBatchDeliveryRow> findEligibleBatchDeliveryRows(
            Collection<UUID> assetIds,
            String variantKey
    ) {
        if (assetIds == null || assetIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> stringIds = assetIds.stream()
                .filter(Objects::nonNull)
                .map(UUID::toString)
                .distinct()
                .toList();

        if (stringIds.isEmpty()) {
            return Collections.emptyList();
        }

        String effectiveVariantKey = (variantKey != null && !variantKey.isBlank())
                ? variantKey.trim().toLowerCase(Locale.ROOT)
                : "";

        if (stringIds.size() <= BATCH_CHUNK_SIZE) {
            return executeChunk(stringIds, effectiveVariantKey);
        }

        List<MediaPublicImageBatchDeliveryRow> results = new ArrayList<>(stringIds.size());
        for (int i = 0; i < stringIds.size(); i += BATCH_CHUNK_SIZE) {
            int end = Math.min(i + BATCH_CHUNK_SIZE, stringIds.size());
            List<String> chunk = stringIds.subList(i, end);
            results.addAll(executeChunk(chunk, effectiveVariantKey));
        }
        return Collections.unmodifiableList(results);
    }

    private List<MediaPublicImageBatchDeliveryRow> executeChunk(List<String> stringIds, String variantKey) {
        List<MediaPublicImageBatchDeliveryProjection> projections =
                repository.findBatchDeliveryProjections(stringIds, variantKey);
        if (projections == null || projections.isEmpty()) {
            return Collections.emptyList();
        }
        return projections.stream()
                .map(this::toRow)
                .filter(Objects::nonNull)
                .toList();
    }

    private MediaPublicImageBatchDeliveryRow toRow(MediaPublicImageBatchDeliveryProjection projection) {
        if (projection == null || projection.getAssetId() == null) {
            return null;
        }
        try {
            UUID assetId = UUID.fromString(projection.getAssetId());
            MediaAssetStatus status = projection.getStatus() != null
                    ? MediaAssetStatus.valueOf(projection.getStatus()) : null;
            MediaVisibility visibility = projection.getVisibility() != null
                    ? MediaVisibility.valueOf(projection.getVisibility()) : null;
            MediaType mediaType = projection.getMediaType() != null
                    ? MediaType.valueOf(projection.getMediaType()) : null;
            UUID versionId = projection.getVersionId() != null
                    ? UUID.fromString(projection.getVersionId()) : null;
            UUID variantId = projection.getVariantId() != null
                    ? UUID.fromString(projection.getVariantId()) : null;
            StorageProviderId providerId = projection.getStorageProviderId() != null
                    ? StorageProviderId.of(projection.getStorageProviderId()) : null;
            int versionNumber = projection.getVersionNumber() != null ? projection.getVersionNumber() : 0;

            return new MediaPublicImageBatchDeliveryRow(
                    assetId,
                    status,
                    visibility,
                    mediaType,
                    projection.getCurrentVersionNumber(),
                    versionId,
                    versionNumber,
                    providerId,
                    projection.getVersionPublicUrl(),
                    variantId,
                    projection.getVariantPublicUrl()
            );
        } catch (Exception e) {
            return null;
        }
    }
}
