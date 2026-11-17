package com.universe.media.infrastructure.persistence;

public interface MediaPublicImageBatchDeliveryProjection {

    String getAssetId();

    String getStatus();

    String getVisibility();

    String getMediaType();

    int getCurrentVersionNumber();

    String getVersionId();

    Integer getVersionNumber();

    String getStorageProviderId();

    String getVersionPublicUrl();

    String getVariantId();

    String getVariantPublicUrl();
}
