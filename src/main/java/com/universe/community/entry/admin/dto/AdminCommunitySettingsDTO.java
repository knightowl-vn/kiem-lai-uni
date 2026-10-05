package com.universe.community.entry.admin.dto;

import com.universe.community.domain.CommunityPublicationMode;

import java.time.Instant;
import java.util.Objects;

/**
 * Presentation DTO for Community runtime settings view.
 */
public record AdminCommunitySettingsDTO(
        CommunityPublicationMode publicationMode,
        long version,
        Instant updatedAt,
        AdminCommunityPostUserDTO updatedBy
) {
    public AdminCommunitySettingsDTO {
        Objects.requireNonNull(publicationMode, "publicationMode cannot be null.");
        Objects.requireNonNull(updatedAt, "updatedAt cannot be null.");
    }

    public AdminCommunityPostUserDTO updater() {
        return updatedBy;
    }
}
