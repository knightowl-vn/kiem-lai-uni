package com.universe.media.application.ports;

import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;
import com.universe.media.domain.StorageProviderId;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Authoritative port for resolving eligible public image delivery candidates in batch.
 */
public interface MediaPublicImageBatchDeliveryQueryPort {

    List<MediaPublicImageBatchDeliveryRow> findEligibleBatchDeliveryRows(
            Collection<UUID> assetIds,
            String variantKey
    );

    record MediaPublicImageBatchDeliveryRow(
            UUID assetId,
            MediaAssetStatus status,
            MediaVisibility visibility,
            MediaType mediaType,
            int currentVersionNumber,
            UUID versionId,
            int versionNumber,
            StorageProviderId storageProviderId,
            String versionPublicUrl,
            UUID variantId,
            String variantPublicUrl
    ) {
        public boolean isVariantPresent() {
            return variantId != null;
        }
    }
}
