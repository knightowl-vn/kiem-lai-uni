package com.universe.media.infrastructure.persistence;

public interface MediaAssetContentDeliveryProjection {

    String getAssetId();

    String getStatus();

    String getVisibility();

    int getCurrentVersionNumber();

    String getVersionId();

    String getVersionAssetId();

    Integer getVersionNumber();

    String getStorageProviderId();

    String getStorageKey();

    String getPublicUrl();

    String getContentHash();

    String getMimeType();

    Long getSizeBytes();
}
