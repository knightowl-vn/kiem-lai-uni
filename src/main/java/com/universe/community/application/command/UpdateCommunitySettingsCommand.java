package com.universe.community.application.command;

import com.universe.community.domain.CommunityPublicationMode;

import java.util.Objects;
import java.util.UUID;

/**
 * Command representing an update to Community runtime settings.
 */
public record UpdateCommunitySettingsCommand(
        CommunityPublicationMode publicationMode,
        long expectedVersion,
        UUID updatedByUserId
) {
    public UpdateCommunitySettingsCommand {
        Objects.requireNonNull(publicationMode, "publicationMode cannot be null.");
        Objects.requireNonNull(updatedByUserId, "updatedByUserId cannot be null.");
    }
}
