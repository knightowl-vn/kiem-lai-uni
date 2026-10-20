package com.universe.screen.application.dto;

/**
 * Snapshot classification for completed Screen VIDEO upload sessions during recovery discovery.
 */
public enum RecoveryClassification {

    /**
     * The video asset is ACTIVE, unreferenced by any ScreenEntry, and the target ScreenEntry exists.
     */
    UNATTACHED,

    /**
     * The video asset is ACTIVE and attached to the original target ScreenEntry.
     */
    ATTACHED_TO_TARGET,

    /**
     * The video asset is ACTIVE and referenced by a ScreenEntry different from the original target.
     */
    REFERENCED_ELSEWHERE,

    /**
     * The video asset is ACTIVE and unreferenced, but the original target ScreenEntry no longer exists.
     */
    TARGET_MISSING,

    /**
     * The video asset has already been soft-deleted (DELETED).
     */
    ALREADY_DISCARDED,

    /**
     * The upload session or registered asset is in an inconsistent state (e.g. asset missing, wrong type, ARCHIVED).
     */
    INCONSISTENT
}
