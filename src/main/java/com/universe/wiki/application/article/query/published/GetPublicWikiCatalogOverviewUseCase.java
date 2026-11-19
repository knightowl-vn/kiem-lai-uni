package com.universe.wiki.application.article.query.published;

import com.universe.wiki.application.appreciation.GetWikiAppreciationSummariesUseCase;
import com.universe.wiki.application.ports.WikiPublicCoverImageDeliveryPort;
import com.universe.wiki.contracts.dto.PublishedWikiArticleListItemDTO;
import com.universe.wiki.contracts.dto.PublishedWikiArticlePageDTO;
import com.universe.wiki.domain.appreciation.WikiAppreciationSummary;
import com.universe.wiki.domain.article.ArticleType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Public Wiki catalog overview query coordinator.
 *
 * <p>Orchestrates reading the published article page, appreciation summaries for eligible
 * character and faction articles, and direct public cover image URLs within a single
 * Spring-managed read-only transaction boundary {@code @Transactional(readOnly = true)}.
 *
 * <p>Consolidating these three read operations into a single shared transaction minimizes
 * redundant JDBC connection acquisitions and remote transaction-control round trips
 * ({@code setAutoCommit}, {@code setReadOnly}, {@code commit}).
 */
@Service
@Transactional(readOnly = true)
public class GetPublicWikiCatalogOverviewUseCase {

    public static final int COVER_TARGET_WIDTH = 300;

    private final ListPublishedWikiArticlesUseCase listPublishedArticlesUseCase;
    private final GetWikiAppreciationSummariesUseCase getWikiAppreciationSummariesUseCase;
    private final WikiPublicCoverImageDeliveryPort wikiPublicCoverImageDeliveryPort;

    @Autowired
    public GetPublicWikiCatalogOverviewUseCase(
            ListPublishedWikiArticlesUseCase listPublishedArticlesUseCase,
            GetWikiAppreciationSummariesUseCase getWikiAppreciationSummariesUseCase,
            WikiPublicCoverImageDeliveryPort wikiPublicCoverImageDeliveryPort
    ) {
        this.listPublishedArticlesUseCase = Objects.requireNonNull(
                listPublishedArticlesUseCase,
                "ListPublishedWikiArticlesUseCase cannot be null."
        );
        this.getWikiAppreciationSummariesUseCase = Objects.requireNonNull(
                getWikiAppreciationSummariesUseCase,
                "GetWikiAppreciationSummariesUseCase cannot be null."
        );
        this.wikiPublicCoverImageDeliveryPort = Objects.requireNonNull(
                wikiPublicCoverImageDeliveryPort,
                "WikiPublicCoverImageDeliveryPort cannot be null."
        );
    }

    public PublicWikiCatalogOverviewResult execute(GetPublicWikiCatalogOverviewQuery query) {
        Objects.requireNonNull(query, "GetPublicWikiCatalogOverviewQuery cannot be null.");

        long coordinatorStart = System.nanoTime();

        // Stage 1: Published article list query
        long t0 = System.nanoTime();
        PublishedWikiArticlePageDTO articlePage = listPublishedArticlesUseCase.execute(
                new ListPublishedWikiArticlesQuery(
                        query.keyword(),
                        query.articleType(),
                        query.page(),
                        query.size()
                )
        );
        long t1 = System.nanoTime();
        long articleQueryNanos = t1 - t0;

        // Stage 2: Appreciation summaries for eligible articles (CHARACTER, FACTION)
        List<UUID> eligibleArticleIds = articlePage.items().stream()
                .filter(article -> ArticleType.CHARACTER.name().equals(article.articleType())
                        || ArticleType.FACTION.name().equals(article.articleType()))
                .map(PublishedWikiArticleListItemDTO::id)
                .toList();

        Map<UUID, WikiAppreciationSummary> appreciationSummaries = eligibleArticleIds.isEmpty()
                ? Collections.emptyMap()
                : getWikiAppreciationSummariesUseCase.execute(eligibleArticleIds);
        long t2 = System.nanoTime();
        long appreciationQueryNanos = t2 - t1;

        // Stage 3: Direct public cover image URLs for distinct non-null cover media assets
        List<UUID> coverAssetIds = articlePage.items().stream()
                .map(PublishedWikiArticleListItemDTO::coverMediaAssetId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<UUID, String> directCoverUrls = coverAssetIds.isEmpty()
                ? Collections.emptyMap()
                : wikiPublicCoverImageDeliveryPort.resolvePublicCoverUrls(coverAssetIds, COVER_TARGET_WIDTH, true);
        long t3 = System.nanoTime();
        long mediaBatchResolveNanos = t3 - t2;
        long totalCoordinatorNanos = t3 - coordinatorStart;

        PublicWikiCatalogOverviewTimings timings = new PublicWikiCatalogOverviewTimings(
                articleQueryNanos,
                appreciationQueryNanos,
                mediaBatchResolveNanos,
                totalCoordinatorNanos,
                eligibleArticleIds.size(),
                coverAssetIds.size()
        );

        return new PublicWikiCatalogOverviewResult(
                articlePage,
                appreciationSummaries,
                directCoverUrls,
                timings
        );
    }
}
