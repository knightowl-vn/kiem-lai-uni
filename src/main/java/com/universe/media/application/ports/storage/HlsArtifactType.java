package com.universe.media.application.ports.storage;

/**
 * Classification of an immutable HLS fMP4 VOD package artifact.
 */
public enum HlsArtifactType {
    MASTER_MANIFEST,
    MEDIA_PLAYLIST,
    INIT_SEGMENT,
    MEDIA_SEGMENT
}
