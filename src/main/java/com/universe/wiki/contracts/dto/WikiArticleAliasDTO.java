package com.universe.wiki.contracts.dto;

import java.time.Instant;
import java.util.UUID;

public record WikiArticleAliasDTO(
        UUID id,
        UUID articleId,
        String alias,
        String normalizedAlias,
        Instant createdAt
) {
}