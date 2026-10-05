package com.universe.search.contracts.dto;

import com.universe.novel.contracts.dto.locator.NovelChapterLocatorResultDTO;
import com.universe.wiki.contracts.dto.search.WikiNavigationalSearchResultDTO;

import java.util.Objects;

/**
 * Result DTO containing grouped results for Wiki, Novel, and Community bounded contexts.
 * Preserves separate bounded-context results without cross-context ranking.
 */
public record SearchAggregationResultDTO(
        String query,
        SearchScope scope,
        WikiNavigationalSearchResultDTO wiki,
        NovelChapterLocatorResultDTO novel,
        CommunityProfileSearchResultDTO community
) {
    public SearchAggregationResultDTO {
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(wiki, "wiki must not be null");
        Objects.requireNonNull(novel, "novel must not be null");
        Objects.requireNonNull(community, "community must not be null");
    }
}
