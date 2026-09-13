package com.universe.media.domain;

/**
 * Lifecycle status of a media asset.
 *
 * Transitions:
 * - ACTIVE -> ARCHIVED or DELETED
 * - ARCHIVED -> ACTIVE or DELETED
 * - DELETED is terminal
 */
public enum MediaAssetStatus {
    ACTIVE,
    ARCHIVED,
    DELETED
}
