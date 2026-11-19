package com.universe.wiki.application.article.query.published;

import com.universe.wiki.contracts.dto.PublishedWikiArticleDTO;
import com.universe.wiki.contracts.dto.WikiPublicContributorsResult;
import com.universe.wiki.contracts.dto.appreciation.WikiAppreciationDetailState;

import java.util.Objects;

/**
 * Application result containing all database-backed state required to render a public Wiki article detail page.
 */
public record PublicWikiArticleDetailResult(
        PublishedWikiArticleDTO article,
        boolean saved,
        String directCoverUrl,
        boolean appreciationEligible,
        WikiAppreciationDetailState appreciationState,
        String attributionLine,
        WikiPublicContributorsResult publicContributorsResult,
        PublicWikiArticleDetailTimings timings
) {

    public PublicWikiArticleDetailResult {
        Objects.requireNonNull(article, "PublishedWikiArticleDTO cannot be null.");
        publicContributorsResult = publicContributorsResult != null
                ? publicContributorsResult
                : WikiPublicContributorsResult.empty();
        timings = timings != null ? timings : PublicWikiArticleDetailTimings.empty();
    }
}
