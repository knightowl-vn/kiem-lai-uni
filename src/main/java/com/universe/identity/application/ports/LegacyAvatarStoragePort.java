package com.universe.identity.application.ports;

import java.util.UUID;

/**
 * Port for managing legacy Identity avatar storage during transition.
 * Provides recognition of legacy avatar URLs and deterministic legacy deletion.
 */
public interface LegacyAvatarStoragePort {

    /**
     * Checks if the given avatar URL belongs to the legacy Identity storage backend.
     *
     * @param avatarUrl the URL to check
     * @return true if the URL belongs to legacy Identity storage, false otherwise
     */
    boolean isLegacyAvatarUrl(String avatarUrl);

    /**
     * Deletes the legacy avatar for the given user ID.
     *
     * @param userId the user ID owning the legacy avatar
     */
    void deleteAvatar(UUID userId);
}
