package com.universe.media.infrastructure.persistence;

public interface MediaAssetCurrentMetadataProjection {

    String getAssetId();

    String getStatus();

    String getVisibility();

    int getCurrentVersionNumber();

    String getDeclaredCurrentVersionAssetId();

    Integer getDeclaredCurrentVersionNumber();
}
