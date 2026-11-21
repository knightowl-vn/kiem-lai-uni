package com.universe.media.domain;

/**
 * Lifecycle states of an immutable HLS package attempt bound to a {@link MediaAssetVersion}.
 */
public enum MediaVideoHlsPackageStatus {
    PROCESSING,
    READY,
    FAILED,
    ABANDONED,
    SUPERSEDED,
    PURGING,
    PURGED
}
