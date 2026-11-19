package com.universe.wiki.entry.web;

import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.wiki.application.appreciation.GetWikiAppreciationDetailStateUseCase;
import com.universe.wiki.application.appreciation.GetWikiAppreciationSummariesUseCase;
import com.universe.wiki.application.article.query.contributor.GetWikiArticlePublicContributorsUseCase;
import com.universe.wiki.application.article.query.published.GetPublicWikiArticleDetailUseCase;
import com.universe.wiki.application.article.query.published.GetPublishedWikiArticleQuery;
import com.universe.wiki.application.article.query.published.GetPublishedWikiArticleUseCase;
import com.universe.wiki.application.article.query.published.GetPublicWikiCatalogOverviewUseCase;
import com.universe.wiki.application.article.query.published.ListPublishedWikiArticlesQuery;
import com.universe.wiki.application.article.query.published.ListPublishedWikiArticlesUseCase;
import com.universe.wiki.application.article.render.RenderedWikiContent;
import com.universe.wiki.application.article.render.WikiMarkdownRenderer;
import com.universe.wiki.application.ports.WikiPublicCoverImageDeliveryPort;
import com.universe.wiki.application.saved.IsWikiArticleSavedUseCase;
import com.universe.wiki.contracts.dto.PublishedWikiArticleDTO;
import com.universe.wiki.contracts.dto.PublishedWikiArticleListItemDTO;
import com.universe.wiki.contracts.dto.PublishedWikiArticlePageDTO;
import com.universe.wiki.contracts.dto.WikiPublicContributorsResult;
import com.universe.wiki.contracts.path.ArticleTypePathMapper;
import com.universe.wiki.domain.article.ArticleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.ui.ExtendedModelMap;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PublicWikiCoverBatchOrchestrationTest — Batch & Single Cover Image Resolution")
class PublicWikiCoverBatchOrchestrationTest {

    private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");

    @Mock
    private ListPublishedWikiArticlesUseCase listPublishedArticlesUseCase;

    @Mock
    private GetPublishedWikiArticleUseCase getPublishedArticleUseCase;

    @Mock
    private ArticleTypePathMapper articleTypePathMapper;

    @Mock
    private WikiMarkdownRenderer wikiMarkdownRenderer;

    @Mock
    private IsWikiArticleSavedUseCase isWikiArticleSavedUseCase;

    @Mock
    private GetWikiAppreciationDetailStateUseCase getWikiAppreciationDetailStateUseCase;

    @Mock
    private GetWikiAppreciationSummariesUseCase getWikiAppreciationSummariesUseCase;

    @Mock
    private UserIdentityContract userIdentityContract;

    @Mock
    private GetWikiArticlePublicContributorsUseCase getWikiArticlePublicContributorsUseCase;

    @Mock
    private WikiPublicCoverImageDeliveryPort wikiPublicCoverImageDeliveryPort;

    @Captor
    private ArgumentCaptor<Collection<UUID>> assetIdsCaptor;

    private PublicWikiController controller;

    @BeforeEach
    void setUp() {
        GetPublicWikiCatalogOverviewUseCase coordinator = new GetPublicWikiCatalogOverviewUseCase(
                listPublishedArticlesUseCase,
                getWikiAppreciationSummariesUseCase,
                wikiPublicCoverImageDeliveryPort
        );
        GetPublicWikiArticleDetailUseCase detailCoordinator = new GetPublicWikiArticleDetailUseCase(
                getPublishedArticleUseCase,
                isWikiArticleSavedUseCase,
                wikiPublicCoverImageDeliveryPort,
                getWikiAppreciationDetailStateUseCase,
                userIdentityContract,
                getWikiArticlePublicContributorsUseCase
        );
        controller = new PublicWikiController(
                coordinator,
                detailCoordinator,
                articleTypePathMapper,
                wikiMarkdownRenderer
        );
        lenient().when(getWikiArticlePublicContributorsUseCase.execute(any()))
                .thenReturn(WikiPublicContributorsResult.empty());
        lenient().when(wikiMarkdownRenderer.render(any()))
                .thenReturn(new RenderedWikiContent("<p>Nội dung</p>", List.of()));
    }

    @ParameterizedTest(name = "Batch resolution invokes port exactly once for page size {0}")
    @ValueSource(ints = {1, 20, 100})
    void shouldInvokeBatchResolutionExactlyOnceForVariousPageSizes(int count) {
        List<PublishedWikiArticleListItemDTO> items = new ArrayList<>();
        Map<UUID, String> expectedUrls = new HashMap<>();

        for (int i = 0; i < count; i++) {
            UUID articleId = UUID.randomUUID();
            UUID coverId = UUID.randomUUID();
            items.add(new PublishedWikiArticleListItemDTO(
                    articleId, "Bài " + i, "bai-" + i, "ITEM", "Tóm tắt " + i, NOW, NOW, coverId, 50, 50
            ));
            expectedUrls.put(coverId, "https://res.cloudinary.com/test/w300_" + i + ".webp");
        }

        PublishedWikiArticlePageDTO pageDTO = new PublishedWikiArticlePageDTO(
                items, 0, count, (long) count, 1, true, true
        );

        when(listPublishedArticlesUseCase.execute(any(ListPublishedWikiArticlesQuery.class))).thenReturn(pageDTO);
        when(wikiPublicCoverImageDeliveryPort.resolvePublicCoverUrls(any(), eq(300), eq(true))).thenReturn(expectedUrls);

        ExtendedModelMap model = new ExtendedModelMap();
        String view = controller.listPage(null, null, 0, count, model);

        assertThat(view).isEqualTo("wiki/public/index");
        verify(wikiPublicCoverImageDeliveryPort, times(1))
                .resolvePublicCoverUrls(assetIdsCaptor.capture(), eq(300), eq(true));

        Collection<UUID> capturedIds = assetIdsCaptor.getValue();
        assertThat(capturedIds).hasSize(count);
        assertThat(capturedIds).containsExactlyInAnyOrderElementsOf(expectedUrls.keySet());

        Object attribute = model.getAttribute("articlePage");
        assertThat(attribute).isInstanceOf(PublicWikiArticlePageView.class);
        PublicWikiArticlePageView pageView = (PublicWikiArticlePageView) attribute;
        assertThat(pageView.items()).hasSize(count);

        for (int i = 0; i < count; i++) {
            PublicWikiArticleCardView card = pageView.items().get(i);
            assertThat(card.id()).isEqualTo(items.get(i).id());
            assertThat(card.displayCoverImageUrl()).isEqualTo(expectedUrls.get(items.get(i).coverMediaAssetId()));
        }
    }

    @Test
    @DisplayName("Deduplicates distinct cover IDs before invoking batch delivery port")
    void shouldDeduplicateCoverAssetIds() {
        UUID sharedCoverId = UUID.randomUUID();
        UUID otherCoverId = UUID.randomUUID();

        PublishedWikiArticleListItemDTO item1 = new PublishedWikiArticleListItemDTO(
                UUID.randomUUID(), "Bài 1", "bai-1", "ITEM", "Tóm tắt", NOW, NOW, sharedCoverId, 50, 50
        );
        PublishedWikiArticleListItemDTO item2 = new PublishedWikiArticleListItemDTO(
                UUID.randomUUID(), "Bài 2", "bai-2", "ITEM", "Tóm tắt", NOW, NOW, sharedCoverId, 50, 50
        );
        PublishedWikiArticleListItemDTO item3 = new PublishedWikiArticleListItemDTO(
                UUID.randomUUID(), "Bài 3", "bai-3", "ITEM", "Tóm tắt", NOW, NOW, otherCoverId, 50, 50
        );

        PublishedWikiArticlePageDTO pageDTO = new PublishedWikiArticlePageDTO(
                List.of(item1, item2, item3), 0, 20, 3L, 1, true, true
        );

        when(listPublishedArticlesUseCase.execute(any())).thenReturn(pageDTO);
        when(wikiPublicCoverImageDeliveryPort.resolvePublicCoverUrls(any(), eq(300), eq(true)))
                .thenReturn(Map.of(
                        sharedCoverId, "https://res.cloudinary.com/test/shared.webp",
                        otherCoverId, "https://res.cloudinary.com/test/other.webp"
                ));

        ExtendedModelMap model = new ExtendedModelMap();
        controller.listPage(null, null, 0, 20, model);

        verify(wikiPublicCoverImageDeliveryPort, times(1))
                .resolvePublicCoverUrls(assetIdsCaptor.capture(), eq(300), eq(true));

        Collection<UUID> capturedIds = assetIdsCaptor.getValue();
        assertThat(capturedIds).containsExactlyInAnyOrder(sharedCoverId, otherCoverId);
    }

    @Test
    @DisplayName("Excludes null coverMediaAssetId from batch resolution request")
    void shouldExcludeNullCoverIds() {
        UUID validCoverId = UUID.randomUUID();

        PublishedWikiArticleListItemDTO itemWithCover = new PublishedWikiArticleListItemDTO(
                UUID.randomUUID(), "Có ảnh", "co-anh", "ITEM", "Tóm tắt", NOW, NOW, validCoverId, 50, 50
        );
        PublishedWikiArticleListItemDTO itemWithoutCover = new PublishedWikiArticleListItemDTO(
                UUID.randomUUID(), "Không ảnh", "khong-anh", "ITEM", "Tóm tắt", NOW, NOW, null, 50, 50
        );

        PublishedWikiArticlePageDTO pageDTO = new PublishedWikiArticlePageDTO(
                List.of(itemWithCover, itemWithoutCover), 0, 20, 2L, 1, true, true
        );

        when(listPublishedArticlesUseCase.execute(any())).thenReturn(pageDTO);
        when(wikiPublicCoverImageDeliveryPort.resolvePublicCoverUrls(any(), eq(300), eq(true)))
                .thenReturn(Map.of(validCoverId, "https://res.cloudinary.com/test/valid.webp"));

        ExtendedModelMap model = new ExtendedModelMap();
        controller.listPage(null, null, 0, 20, model);

        verify(wikiPublicCoverImageDeliveryPort, times(1))
                .resolvePublicCoverUrls(assetIdsCaptor.capture(), eq(300), eq(true));

        Collection<UUID> capturedIds = assetIdsCaptor.getValue();
        assertThat(capturedIds).containsExactly(validCoverId);

        PublicWikiArticlePageView pageView = (PublicWikiArticlePageView) model.getAttribute("articlePage");
        assertThat(pageView.items().get(0).displayCoverImageUrl()).isEqualTo("https://res.cloudinary.com/test/valid.webp");
        assertThat(pageView.items().get(1).displayCoverImageUrl()).isNull();
    }

    @Test
    @DisplayName("Never invokes batch delivery port when page is empty or all articles lack cover")
    void shouldNotInvokeBatchDeliveryWhenPageEmptyOrNoCovers() {
        PublishedWikiArticlePageDTO emptyPage = new PublishedWikiArticlePageDTO(
                List.of(), 0, 20, 0L, 0, true, true
        );

        when(listPublishedArticlesUseCase.execute(any())).thenReturn(emptyPage);

        ExtendedModelMap model = new ExtendedModelMap();
        controller.listPage(null, null, 0, 20, model);

        verify(wikiPublicCoverImageDeliveryPort, never()).resolvePublicCoverUrls(any(), anyInt(), anyBoolean());

        PublishedWikiArticleListItemDTO noCoverItem = new PublishedWikiArticleListItemDTO(
                UUID.randomUUID(), "Bài", "bai", "ITEM", "Tóm tắt", NOW, NOW, null, 50, 50
        );
        PublishedWikiArticlePageDTO noCoversPage = new PublishedWikiArticlePageDTO(
                List.of(noCoverItem), 0, 20, 1L, 1, true, true
        );

        when(listPublishedArticlesUseCase.execute(any())).thenReturn(noCoversPage);

        controller.listPage(null, null, 0, 20, model);

        verify(wikiPublicCoverImageDeliveryPort, never()).resolvePublicCoverUrls(any(), anyInt(), anyBoolean());
    }

    @Test
    @DisplayName("Preserves original item ordering and handles partial Media resolution gracefully")
    void shouldPreserveItemOrderAndHandlePartialResolution() {
        UUID cover1 = UUID.randomUUID();
        UUID cover2 = UUID.randomUUID();
        UUID cover3 = UUID.randomUUID();

        PublishedWikiArticleListItemDTO item1 = new PublishedWikiArticleListItemDTO(
                UUID.randomUUID(), "Bài 1", "bai-1", "ITEM", "Tóm tắt", NOW, NOW, cover1, 50, 50
        );
        PublishedWikiArticleListItemDTO item2 = new PublishedWikiArticleListItemDTO(
                UUID.randomUUID(), "Bài 2", "bai-2", "ITEM", "Tóm tắt", NOW, NOW, cover2, 50, 50
        );
        PublishedWikiArticleListItemDTO item3 = new PublishedWikiArticleListItemDTO(
                UUID.randomUUID(), "Bài 3", "bai-3", "ITEM", "Tóm tắt", NOW, NOW, cover3, 50, 50
        );

        PublishedWikiArticlePageDTO pageDTO = new PublishedWikiArticlePageDTO(
                List.of(item1, item2, item3), 0, 20, 3L, 1, true, true
        );

        when(listPublishedArticlesUseCase.execute(any())).thenReturn(pageDTO);
        // Media returns result for cover3 and cover1 only, omit cover2
        when(wikiPublicCoverImageDeliveryPort.resolvePublicCoverUrls(any(), eq(300), eq(true)))
                .thenReturn(Map.of(
                        cover3, "https://res.cloudinary.com/test/cover3.webp",
                        cover1, "https://res.cloudinary.com/test/cover1.webp"
                ));

        ExtendedModelMap model = new ExtendedModelMap();
        controller.listPage(null, null, 0, 20, model);

        PublicWikiArticlePageView pageView = (PublicWikiArticlePageView) model.getAttribute("articlePage");
        assertThat(pageView.items()).hasSize(3);

        // Item 1: Direct URL
        assertThat(pageView.items().get(0).id()).isEqualTo(item1.id());
        assertThat(pageView.items().get(0).displayCoverImageUrl()).isEqualTo("https://res.cloudinary.com/test/cover1.webp");

        // Item 2: Missing direct URL -> falls back to canonical /content
        assertThat(pageView.items().get(1).id()).isEqualTo(item2.id());
        assertThat(pageView.items().get(1).displayCoverImageUrl()).isEqualTo("/media/assets/" + cover2 + "/content");
        assertThat(pageView.items().get(1).fallbackCoverImageUrl()).isEqualTo("/media/assets/" + cover2 + "/content");

        // Item 3: Direct URL
        assertThat(pageView.items().get(2).id()).isEqualTo(item3.id());
        assertThat(pageView.items().get(2).displayCoverImageUrl()).isEqualTo("https://res.cloudinary.com/test/cover3.webp");
    }

    @Test
    @DisplayName("Detail page: invokes single resolution exactly once when article has cover")
    void shouldInvokeSingleResolutionWhenArticleHasCover() {
        UUID coverId = UUID.randomUUID();
        PublishedWikiArticleDTO article = new PublishedWikiArticleDTO(
                UUID.randomUUID(), "Tiêu đề", "tieu-de", "CHARACTER", "Tóm tắt", "Nội dung",
                NOW, NOW, coverId, 50, 50, 1L, UUID.randomUUID(), null
        );

        when(articleTypePathMapper.fromPath("character")).thenReturn(ArticleType.CHARACTER);
        when(articleTypePathMapper.toPath(ArticleType.CHARACTER)).thenReturn("character");
        when(getPublishedArticleUseCase.execute(any())).thenReturn(article);
        when(wikiPublicCoverImageDeliveryPort.resolvePublicCoverUrl(coverId, 300, true))
                .thenReturn(Optional.of("https://res.cloudinary.com/test/detail.webp"));

        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletRequest request = new MockHttpServletRequest();

        String view = controller.detailPage("character", "tieu-de", request, model);

        assertThat(view).isEqualTo("wiki/public/detail");
        verify(wikiPublicCoverImageDeliveryPort, times(1))
                .resolvePublicCoverUrl(coverId, 300, true);

        PublicWikiArticleDetailView detailView = (PublicWikiArticleDetailView) model.getAttribute("article");
        assertThat(detailView.displayCoverImageUrl()).isEqualTo("https://res.cloudinary.com/test/detail.webp");
        assertThat(detailView.fallbackCoverImageUrl()).isEqualTo("/media/assets/" + coverId + "/content");
    }

    @Test
    @DisplayName("Detail page: skips single resolution when article has no cover (null)")
    void shouldSkipSingleResolutionWhenArticleHasNoCover() {
        PublishedWikiArticleDTO article = new PublishedWikiArticleDTO(
                UUID.randomUUID(), "Tiêu đề", "tieu-de", "CHARACTER", "Tóm tắt", "Nội dung",
                NOW, NOW, null, 50, 50, 1L, UUID.randomUUID(), null
        );

        when(articleTypePathMapper.fromPath("character")).thenReturn(ArticleType.CHARACTER);
        when(articleTypePathMapper.toPath(ArticleType.CHARACTER)).thenReturn("character");
        when(getPublishedArticleUseCase.execute(any())).thenReturn(article);

        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletRequest request = new MockHttpServletRequest();

        String view = controller.detailPage("character", "tieu-de", request, model);

        assertThat(view).isEqualTo("wiki/public/detail");
        verify(wikiPublicCoverImageDeliveryPort, never())
                .resolvePublicCoverUrl(any(), anyInt(), anyBoolean());

        PublicWikiArticleDetailView detailView = (PublicWikiArticleDetailView) model.getAttribute("article");
        assertThat(detailView.displayCoverImageUrl()).isNull();
        assertThat(detailView.fallbackCoverImageUrl()).isNull();
    }
}
