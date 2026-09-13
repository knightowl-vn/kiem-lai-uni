package com.universe.media.domain;

/**
 * Access visibility level for a media asset.
 *
 * Mutable through domain methods on MediaAsset.
 */
public enum MediaVisibility {
    PUBLIC,
    PRIVATE,
    RESTRICTED
}
