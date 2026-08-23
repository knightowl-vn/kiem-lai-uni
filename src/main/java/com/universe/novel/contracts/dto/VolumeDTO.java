package com.universe.novel.contracts.dto;

import java.time.Instant;
import java.util.UUID;

public record VolumeDTO(
        UUID id,
        String title,
        String slug,
        String description,
        int sortOrder,
        String status,
        UUID createdBy,
        UUID updatedBy,
        UUID publishedBy,
        UUID archivedBy,
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt,
        Instant archivedAt,
        long aggregateVersion
) {
}