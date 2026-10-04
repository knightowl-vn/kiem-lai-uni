package com.universe.community.application.port.out;

import com.universe.community.domain.CommunityPublicationMode;
import com.universe.community.domain.CommunitySettings;

import java.time.Instant;
import java.util.UUID;

/**
 * Output port for accessing and updating Community runtime settings.
 */
public interface CommunitySettingsRepositoryPort {

    /**
     * Retrieves the current authoritative Community runtime settings from the database.
     *
     * @return current {@link CommunitySettings}
     */
    CommunitySettings getSettings();

    /**
     * Atomically updates Community publication settings with optimistic concurrency verification.
     *
     * @param newMode the updated publication mode
     * @param expectedVersion the version expected by the caller
     * @param updatedByUserId the acting admin user UUID
     * @param updatedAt the timestamp of update
     * @return true if updated, false if expectedVersion was stale
     */
    boolean updateSettings(
            CommunityPublicationMode newMode,
            long expectedVersion,
            UUID updatedByUserId,
            Instant updatedAt
    );
}
