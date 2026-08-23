package com.universe.novel.contracts.dto.profile;

import java.time.Instant;
import java.util.UUID;

public record NovelProfileDTO(
        UUID id,
        String title,
        String slug,
        String author,
        String description,
        String coverImageUrl,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
}
