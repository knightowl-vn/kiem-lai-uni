package com.universe.novel.contracts.dto;

import java.time.Instant;
import java.util.UUID;

public record ChapterListItemDTO(
        UUID id,
        int chapterNumber,
        String title,
        String slug,
        String status,
        Instant updatedAt
) {
}