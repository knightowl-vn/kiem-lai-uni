package com.universe.wiki.application.article.query.published;

import com.universe.wiki.application.appreciation.GetWikiAppreciationSummariesUseCase;
import com.universe.wiki.application.ports.WikiPublicCoverImageDeliveryPort;
import com.universe.wiki.contracts.dto.PublishedWikiArticleListItemDTO;
import com.universe.wiki.contracts.dto.PublishedWikiArticlePageDTO;
import com.universe.wiki.domain.appreciation.WikiAppreciationSummary;
import com.universe.wiki.domain.article.ArticleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetPublicWikiCatalogOverviewUseCase Unit Tests")
class GetPublicWikiCatalogOverviewUseCaseTest {

    private static final UUID CHARACTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID FACTION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ITEM_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID COVER_ASSET_1 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID COVER_ASSET_2 = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Mock
    private ListPublishedWikiArticlesUseCase listPublishedArticlesUseCase;

    @Mock
    private GetWikiAppreciationSummariesUseCase getWikiAppreciationSummariesUseCase;

    @Mock
    private WikiPublicCoverImageDeliveryPort wikiPublicCoverImageDeliveryPort;

    private GetPublicWikiCatalogOverviewUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new GetPublicWikiCatalogOverviewUseCase(
                listPublishedArticlesUseCase,
                getWikiAppreciationSummariesUseCase,
                wikiPublicCoverImageDeliveryPort
        );
    }

    @Test
    @DisplayName("UseCase class is annotated with @Transactional(readOnly = true)")
    void classHasTransactionalReadOnlyAnnotation() {
        Transactional annotation = GetPublicWikiCatalogOverviewUseCase.class.getAnnotation(Transactional.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.readOnly()).isTrue();
    }

    @Test
    @DisplayName("Orchestrates article query, appreciation query and cover URL resolution within single call")
    void orchestratesAllThreeReadStages() {
        PublishedWikiArticleListItemDTO charItem = createItem(CHARACTER_ID, ArticleType.CHARACTER, COVER_ASSET_1);
        PublishedWikiArticleListItemDTO factItem = createItem(FACTION_ID, ArticleType.FACTION, COVER_ASSET_2);
        PublishedWikiArticleListItemDTO itemItem = createItem(ITEM_ID, ArticleType.ITEM, null);

        PublishedWikiArticlePageDTO pageDTO = new PublishedWikiArticlePageDTO(
                List.of(charItem, factItem, itemItem),
                0, 20, 3, 1, false, false
        );

        when(listPublishedArticlesUseCase.execute(any())).thenReturn(pageDTO);

        WikiAppreciationSummary summary1 = new WikiAppreciationSummary(CHARACTER_ID, new BigDecimal("4.5"), 10L);
        WikiAppreciationSummary summary2 = new WikiAppreciationSummary(FACTION_ID, new BigDecimal("4.0"), 5L);
        when(getWikiAppreciationSummariesUseCase.execute(List.of(CHARACTER_ID, FACTION_ID)))
                .thenReturn(Map.of(CHARACTER_ID, summary1, FACTION_ID, summary2));

        when(wikiPublicCoverImageDeliveryPort.resolvePublicCoverUrls(
                eq(List.of(COVER_ASSET_1, COVER_ASSET_2)), eq(300), eq(true)
        )).thenReturn(Map.of(
                COVER_ASSET_1, "https://res.cloudinary.com/demo/image/upload/w_300/cover1.webp",
                COVER_ASSET_2, "https://res.cloudinary.com/demo/image/upload/w_300/cover2.webp"
        ));

        GetPublicWikiCatalogOverviewQuery query = GetPublicWikiCatalogOverviewQuery.of("test", ArticleType.CHARACTER, 0, 20);
        PublicWikiCatalogOverviewResult result = useCase.execute(query);

        assertThat(result).isNotNull();
        assertThat(result.articlePage()).isSameAs(pageDTO);
        assertThat(result.appreciationSummaries()).hasSize(2)
                .containsEntry(CHARACTER_ID, summary1)
                .containsEntry(FACTION_ID, summary2);
        assertThat(result.directCoverUrls()).hasSize(2)
                .containsEntry(COVER_ASSET_1, "https://res.cloudinary.com/demo/image/upload/w_300/cover1.webp")
                .containsEntry(COVER_ASSET_2, "https://res.cloudinary.com/demo/image/upload/w_300/cover2.webp");

        assertThat(result.timings()).isNotNull();
        assertThat(result.timings().eligibleAppreciationCount()).isEqualTo(2);
        assertThat(result.timings().coverAssetCount()).isEqualTo(2);
        assertThat(result.timings().articleQueryNanos()).isGreaterThanOrEqualTo(0L);
        assertThat(result.timings().appreciationQueryNanos()).isGreaterThanOrEqualTo(0L);
        assertThat(result.timings().mediaBatchResolveNanos()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("Zero articles skips appreciation and cover image resolution")
    void zeroArticlesSkipsDownstreamQueries() {
        PublishedWikiArticlePageDTO emptyPage = new PublishedWikiArticlePageDTO(
                Collections.emptyList(), 0, 20, 0, 0, false, false
        );
        when(listPublishedArticlesUseCase.execute(any())).thenReturn(emptyPage);

        GetPublicWikiCatalogOverviewQuery query = GetPublicWikiCatalogOverviewQuery.of(null, null, 0, 20);
        PublicWikiCatalogOverviewResult result = useCase.execute(query);

        assertThat(result.articlePage().items()).isEmpty();
        assertThat(result.appreciationSummaries()).isEmpty();
        assertThat(result.directCoverUrls()).isEmpty();
        assertThat(result.timings().eligibleAppreciationCount()).isEqualTo(0);
        assertThat(result.timings().coverAssetCount()).isEqualTo(0);

        verify(getWikiAppreciationSummariesUseCase, never()).execute(any());
        verify(wikiPublicCoverImageDeliveryPort, never()).resolvePublicCoverUrls(any(), anyInt(), anyBoolean());
    }

    @Test
    @DisplayName("Articles without CHARACTER or FACTION type skip appreciation query")
    void nonCharacterNonFactionSkipsAppreciationQuery() {
        PublishedWikiArticleListItemDTO item1 = createItem(ITEM_ID, ArticleType.ITEM, COVER_ASSET_1);
        PublishedWikiArticlePageDTO pageDTO = new PublishedWikiArticlePageDTO(
                List.of(item1), 0, 20, 1, 1, false, false
        );
        when(listPublishedArticlesUseCase.execute(any())).thenReturn(pageDTO);
        when(wikiPublicCoverImageDeliveryPort.resolvePublicCoverUrls(any(), eq(300), eq(true)))
                .thenReturn(Map.of(COVER_ASSET_1, "https://cdn.example.com/cover1.webp"));

        PublicWikiCatalogOverviewResult result = useCase.execute(GetPublicWikiCatalogOverviewQuery.of(null, null, 0, 20));

        assertThat(result.appreciationSummaries()).isEmpty();
        assertThat(result.timings().eligibleAppreciationCount()).isEqualTo(0);
        assertThat(result.directCoverUrls()).hasSize(1);
        verify(getWikiAppreciationSummariesUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("Articles without coverMediaAssetId skip cover resolution")
    void articlesWithoutCoverSkipCoverResolution() {
        PublishedWikiArticleListItemDTO item1 = createItem(CHARACTER_ID, ArticleType.CHARACTER, null);
        PublishedWikiArticlePageDTO pageDTO = new PublishedWikiArticlePageDTO(
                List.of(item1), 0, 20, 1, 1, false, false
        );
        when(listPublishedArticlesUseCase.execute(any())).thenReturn(pageDTO);
        when(getWikiAppreciationSummariesUseCase.execute(any())).thenReturn(Map.of());

        PublicWikiCatalogOverviewResult result = useCase.execute(GetPublicWikiCatalogOverviewQuery.of(null, null, 0, 20));

        assertThat(result.directCoverUrls()).isEmpty();
        assertThat(result.timings().coverAssetCount()).isEqualTo(0);
        verify(wikiPublicCoverImageDeliveryPort, never()).resolvePublicCoverUrls(any(), anyInt(), anyBoolean());
    }

    @Test
    @DisplayName("Duplicate cover media asset IDs are deduplicated before batch resolution")
    @SuppressWarnings("unchecked")
    void duplicateCoverAssetIdsAreDeduplicated() {
        PublishedWikiArticleListItemDTO item1 = createItem(CHARACTER_ID, ArticleType.CHARACTER, COVER_ASSET_1);
        PublishedWikiArticleListItemDTO item2 = createItem(FACTION_ID, ArticleType.FACTION, COVER_ASSET_1);
        PublishedWikiArticlePageDTO pageDTO = new PublishedWikiArticlePageDTO(
                List.of(item1, item2), 0, 20, 2, 1, false, false
        );
        when(listPublishedArticlesUseCase.execute(any())).thenReturn(pageDTO);
        when(getWikiAppreciationSummariesUseCase.execute(any())).thenReturn(Map.of());

        ArgumentCaptor<Collection<UUID>> captor = ArgumentCaptor.forClass(Collection.class);
        when(wikiPublicCoverImageDeliveryPort.resolvePublicCoverUrls(captor.capture(), eq(300), eq(true)))
                .thenReturn(Map.of(COVER_ASSET_1, "https://cdn.example.com/cover1.webp"));

        PublicWikiCatalogOverviewResult result = useCase.execute(GetPublicWikiCatalogOverviewQuery.of(null, null, 0, 20));

        assertThat(captor.getValue()).containsExactly(COVER_ASSET_1);
        assertThat(result.timings().coverAssetCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Rejects null query")
    void rejectsNullQuery() {
        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("GetPublicWikiCatalogOverviewQuery cannot be null.");
    }

    @Test
    @DisplayName("Constructor rejects null ListPublishedWikiArticlesUseCase")
    void constructorRejectsNullListPublishedArticlesUseCase() {
        assertThatThrownBy(() -> new GetPublicWikiCatalogOverviewUseCase(
                null,
                getWikiAppreciationSummariesUseCase,
                wikiPublicCoverImageDeliveryPort
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ListPublishedWikiArticlesUseCase cannot be null.");
    }

    @Test
    @DisplayName("Constructor rejects null GetWikiAppreciationSummariesUseCase")
    void constructorRejectsNullGetWikiAppreciationSummariesUseCase() {
        assertThatThrownBy(() -> new GetPublicWikiCatalogOverviewUseCase(
                listPublishedArticlesUseCase,
                null,
                wikiPublicCoverImageDeliveryPort
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("GetWikiAppreciationSummariesUseCase cannot be null.");
    }

    @Test
    @DisplayName("Constructor rejects null WikiPublicCoverImageDeliveryPort")
    void constructorRejectsNullWikiPublicCoverImageDeliveryPort() {
        assertThatThrownBy(() -> new GetPublicWikiCatalogOverviewUseCase(
                listPublishedArticlesUseCase,
                getWikiAppreciationSummariesUseCase,
                null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("WikiPublicCoverImageDeliveryPort cannot be null.");
    }

    @Test
    @DisplayName("Query factory accepts valid pagination parameters")
    void queryFactoryAcceptsValidPaginationParameters() {
        GetPublicWikiCatalogOverviewQuery query = GetPublicWikiCatalogOverviewQuery.of("keyword", null, 0, 20);
        assertThat(query.page()).isEqualTo(0);
        assertThat(query.size()).isEqualTo(20);

        GetPublicWikiCatalogOverviewQuery boundaryQuery = GetPublicWikiCatalogOverviewQuery.of("keyword", null, 5, 100);
        assertThat(boundaryQuery.page()).isEqualTo(5);
        assertThat(boundaryQuery.size()).isEqualTo(100);
    }

    @Test
    @DisplayName("Query factory rejects negative page")
    void queryFactoryRejectsNegativePage() {
        assertThatThrownBy(() -> GetPublicWikiCatalogOverviewQuery.of("keyword", null, -1, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page không được nhỏ hơn 0.");
    }

    @Test
    @DisplayName("Query factory rejects zero or negative size")
    void queryFactoryRejectsZeroOrNegativeSize() {
        assertThatThrownBy(() -> GetPublicWikiCatalogOverviewQuery.of("keyword", null, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page size phải lớn hơn hoặc bằng 1.");

        assertThatThrownBy(() -> GetPublicWikiCatalogOverviewQuery.of("keyword", null, 0, -5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page size phải lớn hơn hoặc bằng 1.");
    }

    @Test
    @DisplayName("Query factory rejects size exceeding max page size")
    void queryFactoryRejectsSizeExceedingMax() {
        assertThatThrownBy(() -> GetPublicWikiCatalogOverviewQuery.of("keyword", null, 0, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page size không được vượt quá 100.");
    }

    private PublishedWikiArticleListItemDTO createItem(UUID id, ArticleType type, UUID coverAssetId) {
        return new PublishedWikiArticleListItemDTO(
                id,
                "Article " + id,
                "slug-" + id,
                type.name(),
                "Summary",
                Instant.now(),
                Instant.now(),
                coverAssetId
        );
    }
}
