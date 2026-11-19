package com.universe.wiki.application.article.query.published;

import com.universe.wiki.contracts.dto.PublishedWikiArticlePageDTO;
import com.universe.wiki.domain.appreciation.WikiAppreciationSummary;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Application result containing paginated published Wiki articles, batch-resolved appreciation summaries,
 * and batch-resolved public cover direct delivery URLs.
 */
public record PublicWikiCatalogOverviewResult(
        PublishedWikiArticlePageDTO articlePage,
        Map<UUID, WikiAppreciationSummary> appreciationSummaries,
        Map<UUID, String> directCoverUrls,
        PublicWikiCatalogOverviewTimings timings
) {

    public PublicWikiCatalogOverviewResult {
        Objects.requireNonNull(articlePage, "PublishedWikiArticlePageDTO cannot be null.");
        appreciationSummaries = appreciationSummaries != null ? Collections.unmodifiableMap(appreciationSummaries) : Collections.emptyMap();
        directCoverUrls = directCoverUrls != null ? Collections.unmodifiableMap(directCoverUrls) : Collections.emptyMap();
        timings = timings != null ? timings : PublicWikiCatalogOverviewTimings.empty();
    }
}
