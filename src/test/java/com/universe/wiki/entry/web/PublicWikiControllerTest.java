package com.universe.wiki.entry.web;

import com.universe.wiki.application.article.query.published
        .GetPublicWikiArticleDetailQuery;
import com.universe.wiki.application.article.query.published
        .GetPublicWikiArticleDetailUseCase;
import com.universe.wiki.application.article.query.published
        .GetPublishedWikiArticleQuery;
import com.universe.wiki.application.article.query.published
        .GetPublishedWikiArticleUseCase;
import com.universe.wiki.application.article.query.published
        .GetPublicWikiCatalogOverviewQuery;
import com.universe.wiki.application.article.query.published
        .GetPublicWikiCatalogOverviewUseCase;
import com.universe.wiki.application.article.query.published
        .PublicWikiArticleDetailResult;
import com.universe.wiki.application.article.query.published
        .PublicWikiArticleDetailTimings;
import com.universe.wiki.application.article.query.published
        .PublicWikiCatalogOverviewResult;
import com.universe.wiki.application.article.query.published
        .PublicWikiCatalogOverviewTimings;
import com.universe.wiki.application.ports.WikiPublicCoverImageDeliveryPort;
import com.universe.wiki.application.article.query.published
        .ListPublishedWikiArticlesQuery;
import com.universe.wiki.application.article.query.published
        .ListPublishedWikiArticlesUseCase;

import com.universe.wiki.application.article.render
        .RenderedWikiContent;
import com.universe.wiki.application.article.render
        .WikiMarkdownRenderer;
import com.universe.wiki.application.article.render
        .WikiTocItem;

import com.universe.wiki.contracts.dto
        .PublishedWikiArticleDTO;
import com.universe.wiki.contracts.dto
        .PublishedWikiArticleListItemDTO;
import com.universe.wiki.contracts.dto
        .PublishedWikiArticlePageDTO;

import com.universe.wiki.domain.article.ArticleType;

import com.universe.wiki.contracts.path.ArticleTypePathMapper;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityTestSupport;
import com.universe.wiki.application.appreciation.GetWikiAppreciationDetailStateUseCase;
import com.universe.wiki.application.appreciation.GetWikiAppreciationSummariesUseCase;
import com.universe.wiki.application.saved.IsWikiArticleSavedUseCase;
import com.universe.wiki.contracts.dto.appreciation.WikiAppreciationDetailState;
import com.universe.wiki.domain.appreciation.WikiAppreciationSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.ui.ExtendedModelMap;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicWikiControllerTest {

    private static final UUID ARTICLE_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID USER_ID =
            UUID.fromString(
                    "99999999-9999-9999-9999-999999999999"
            );

    private static final Instant PUBLISHED_AT =
            Instant.parse(
                    "2026-08-06T09:00:00Z"
            );

    private static final Instant UPDATED_AT =
            Instant.parse(
                    "2026-08-06T10:00:00Z"
            );

    @Mock
    private ListPublishedWikiArticlesUseCase
            listPublishedArticlesUseCase;

    @Mock
    private GetPublishedWikiArticleUseCase
            getPublishedArticleUseCase;

    @Mock
    private ArticleTypePathMapper
            articleTypePathMapper;

    @Mock
    private WikiMarkdownRenderer
            wikiMarkdownRenderer;

    @Mock
    private IsWikiArticleSavedUseCase
            isWikiArticleSavedUseCase;

    @Mock
    private GetWikiAppreciationDetailStateUseCase
            getWikiAppreciationDetailStateUseCase;

    @Mock
    private GetWikiAppreciationSummariesUseCase
            getWikiAppreciationSummariesUseCase;

    @Mock
    private com.universe.identity.contracts.interfaces.UserIdentityContract
            userIdentityContract;

    @Mock
    private com.universe.wiki.application.article.query.contributor.GetWikiArticlePublicContributorsUseCase
            getWikiArticlePublicContributorsUseCase;

    @Mock
    private GetPublicWikiCatalogOverviewUseCase
            getPublicWikiCatalogOverviewUseCase;

    @Mock
    private WikiPublicCoverImageDeliveryPort
            wikiPublicCoverImageDeliveryPort;

    private GetPublicWikiArticleDetailUseCase
            getPublicWikiArticleDetailUseCase;

    private PublicWikiController
            controller;

    @BeforeEach
    void setUp() {
        getPublicWikiArticleDetailUseCase =
                new GetPublicWikiArticleDetailUseCase(
                        getPublishedArticleUseCase,
                        isWikiArticleSavedUseCase,
                        wikiPublicCoverImageDeliveryPort,
                        getWikiAppreciationDetailStateUseCase,
                        userIdentityContract,
                        getWikiArticlePublicContributorsUseCase
                );
        controller =
                new PublicWikiController(
                        getPublicWikiCatalogOverviewUseCase,
                        getPublicWikiArticleDetailUseCase,
                        articleTypePathMapper,
                        wikiMarkdownRenderer
                );
        org.mockito.Mockito.lenient().when(getWikiArticlePublicContributorsUseCase.execute(any()))
                .thenReturn(com.universe.wiki.contracts.dto.WikiPublicContributorsResult.empty());
    }

    @Test
    @DisplayName(
            "Hiển thị danh sách Wiki công khai có bộ lọc"
    )
    void shouldShowPublishedWikiList() {
        PublishedWikiArticlePageDTO articlePage =
                createPageDTO();

        when(
                articleTypePathMapper.fromPath(
                        "character"
                )
        ).thenReturn(
                ArticleType.CHARACTER
        );

        when(
                getPublicWikiCatalogOverviewUseCase.execute(
                        new GetPublicWikiCatalogOverviewQuery(
                                "Trần Bình",
                                ArticleType.CHARACTER,
                                0,
                                20
                        )
                )
        ).thenReturn(
                new PublicWikiCatalogOverviewResult(
                        articlePage,
                        Map.of(ARTICLE_ID, WikiAppreciationSummary.empty(ARTICLE_ID)),
                        Map.of(),
                        PublicWikiCatalogOverviewTimings.empty()
                )
        );

        ExtendedModelMap model =
                new ExtendedModelMap();

        String viewName =
                controller.listPage(
                        "Trần Bình",
                        "character",
                        0,
                        20,
                        model
                );

        assertThat(viewName)
                .isEqualTo(
                        "wiki/public/index"
                );

        assertThat(model.getAttribute("articlePage"))
                .isInstanceOf(PublicWikiArticlePageView.class);
        PublicWikiArticlePageView pageView =
                (PublicWikiArticlePageView) model.getAttribute("articlePage");
        assertThat(pageView.page()).isEqualTo(articlePage.page());
        assertThat(pageView.size()).isEqualTo(articlePage.size());
        assertThat(pageView.totalElements()).isEqualTo(articlePage.totalElements());
        assertThat(pageView.totalPages()).isEqualTo(articlePage.totalPages());
        assertThat(pageView.first()).isEqualTo(articlePage.first());
        assertThat(pageView.last()).isEqualTo(articlePage.last());
        assertThat(pageView.items()).hasSize(1);
        PublicWikiArticleCardView card = pageView.items().get(0);
        assertThat(card.id()).isEqualTo(ARTICLE_ID);
        assertThat(card.title()).isEqualTo("Trần Bình An");
        assertThat(card.slug()).isEqualTo("tran-binh-an");
        assertThat(card.articleType()).isEqualTo("CHARACTER");
        assertThat(card.summary()).isEqualTo("Nhân vật chính của Kiếm Lai.");
        assertThat(card.publishedAt()).isEqualTo(PUBLISHED_AT);
        assertThat(card.updatedAt()).isEqualTo(UPDATED_AT);

        assertThat(
                model.getAttribute(
                        "keyword"
                )
        ).isEqualTo(
                "Trần Bình"
        );

        assertThat(
                model.getAttribute(
                        "selectedType"
                )
        ).isEqualTo(
                ArticleType.CHARACTER
        );

        assertThat(
                model.getAttribute(
                        "appreciationSummaries"
                )
        ).isEqualTo(
                Map.of(ARTICLE_ID, WikiAppreciationSummary.empty(ARTICLE_ID))
        );

        ArticleType[] articleTypes =
                (ArticleType[])
                        model.getAttribute(
                                "articleTypes"
                        );

        assertThat(articleTypes)
                .containsExactly(
                        ArticleType.values()
                );

        verify(articleTypePathMapper)
                .fromPath(
                        "character"
                );

        verify(getPublicWikiCatalogOverviewUseCase)
                .execute(
                        new GetPublicWikiCatalogOverviewQuery(
                                "Trần Bình",
                                ArticleType.CHARACTER,
                                0,
                                20
                        )
                );
    }

    @Test
    @DisplayName("F6: Bulk appreciation summary được gọi duy nhất một lần chỉ với các bài viết CHARACTER và FACTION")
    void shouldCallBulkAppreciationOnlyForEligibleArticles() {
        UUID charId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID factionId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID itemId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID realmId = UUID.fromString("44444444-4444-4444-4444-444444444444");

        PublishedWikiArticleListItemDTO charItem = new PublishedWikiArticleListItemDTO(
                charId, "Trần Bình An", "tran-binh-an", "CHARACTER", "Nhân vật chính", PUBLISHED_AT, UPDATED_AT
        );
        PublishedWikiArticleListItemDTO factionItem = new PublishedWikiArticleListItemDTO(
                factionId, "Thần Tú Phong", "than-tu-phong", "FACTION", "Tông môn", PUBLISHED_AT, UPDATED_AT
        );
        PublishedWikiArticleListItemDTO itemArticle = new PublishedWikiArticleListItemDTO(
                itemId, "Dưỡng Kiếm Hồ", "duong-kiem-ho", "ITEM", "Bảo vật", PUBLISHED_AT, UPDATED_AT
        );
        PublishedWikiArticleListItemDTO realmArticle = new PublishedWikiArticleListItemDTO(
                realmId, "Ngọc Phác Cảnh", "ngoc-phac-canh", "REALM", "Cảnh giới tu luyện", PUBLISHED_AT, UPDATED_AT
        );

        PublishedWikiArticlePageDTO mixedPage = new PublishedWikiArticlePageDTO(
                List.of(charItem, factionItem, itemArticle, realmArticle),
                0, 20, 4L, 1, true, true
        );

        Map<UUID, WikiAppreciationSummary> expectedSummaries = Map.of(
                charId, new WikiAppreciationSummary(charId, new BigDecimal("4.8"), 120L),
                factionId, new WikiAppreciationSummary(factionId, new BigDecimal("4.2"), 45L)
        );

        when(getPublicWikiCatalogOverviewUseCase.execute(any()))
                .thenReturn(new PublicWikiCatalogOverviewResult(
                        mixedPage,
                        expectedSummaries,
                        Map.of(),
                        PublicWikiCatalogOverviewTimings.empty()
                ));

        ExtendedModelMap model = new ExtendedModelMap();
        String viewName = controller.listPage(null, null, 0, 20, model);

        assertThat(viewName).isEqualTo("wiki/public/index");
        assertThat(model.getAttribute("articlePage"))
                .isInstanceOf(PublicWikiArticlePageView.class);
        PublicWikiArticlePageView pageView =
                (PublicWikiArticlePageView) model.getAttribute("articlePage");
        assertThat(pageView.page()).isEqualTo(mixedPage.page());
        assertThat(pageView.size()).isEqualTo(mixedPage.size());
        assertThat(pageView.totalElements()).isEqualTo(mixedPage.totalElements());
        assertThat(pageView.totalPages()).isEqualTo(mixedPage.totalPages());
        assertThat(pageView.first()).isEqualTo(mixedPage.first());
        assertThat(pageView.last()).isEqualTo(mixedPage.last());
        assertThat(pageView.items()).hasSize(4);
        assertThat(pageView.items().stream().map(PublicWikiArticleCardView::id).toList())
                .containsExactly(charId, factionId, itemId, realmId);
        assertThat(model.getAttribute("appreciationSummaries")).isEqualTo(expectedSummaries);

        verify(getPublicWikiCatalogOverviewUseCase, times(1)).execute(any());
    }

    @Test
    @DisplayName("F6: Không gọi bulk appreciation query khi trang chỉ chứa các bài viết không đủ điều kiện (REALM/ITEM) hoặc rỗng")
    void shouldNotCallAppreciationQueryWhenNoEligibleArticlesOnPage() {
        UUID itemId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        PublishedWikiArticleListItemDTO itemArticle = new PublishedWikiArticleListItemDTO(
                itemId, "Dưỡng Kiếm Hồ", "duong-kiem-ho", "ITEM", "Bảo vật", PUBLISHED_AT, UPDATED_AT
        );

        PublishedWikiArticlePageDTO ineligiblePage = new PublishedWikiArticlePageDTO(
                List.of(itemArticle),
                0, 20, 1L, 1, true, true
        );

        when(articleTypePathMapper.fromPath("item")).thenReturn(ArticleType.ITEM);
        when(getPublicWikiCatalogOverviewUseCase.execute(any()))
                .thenReturn(new PublicWikiCatalogOverviewResult(
                        ineligiblePage,
                        Map.of(),
                        Map.of(),
                        PublicWikiCatalogOverviewTimings.empty()
                ));

        ExtendedModelMap model = new ExtendedModelMap();
        String viewName = controller.listPage(null, "item", 0, 20, model);

        assertThat(viewName).isEqualTo("wiki/public/index");
        assertThat(model.getAttribute("articlePage"))
                .isInstanceOf(PublicWikiArticlePageView.class);
        PublicWikiArticlePageView pageView =
                (PublicWikiArticlePageView) model.getAttribute("articlePage");
        assertThat(pageView.page()).isEqualTo(ineligiblePage.page());
        assertThat(pageView.size()).isEqualTo(ineligiblePage.size());
        assertThat(pageView.totalElements()).isEqualTo(ineligiblePage.totalElements());
        assertThat(pageView.totalPages()).isEqualTo(ineligiblePage.totalPages());
        assertThat(pageView.items()).hasSize(1);
        assertThat(pageView.items().get(0).id()).isEqualTo(itemId);
        assertThat(model.getAttribute("appreciationSummaries")).isEqualTo(Map.of());

        verify(getPublicWikiCatalogOverviewUseCase, times(1)).execute(any());
    }

    @Test
    @DisplayName(
            "Không lọc loại bài khi type để trống"
    )
    void shouldListWithoutArticleTypeFilter() {
        PublishedWikiArticlePageDTO emptyPage =
                new PublishedWikiArticlePageDTO(
                        List.of(),
                        0,
                        20,
                        0L,
                        0,
                        true,
                        true
                );

        when(
                getPublicWikiCatalogOverviewUseCase.execute(
                        new GetPublicWikiCatalogOverviewQuery(
                                null,
                                null,
                                0,
                                20
                        )
                )
        ).thenReturn(
                new PublicWikiCatalogOverviewResult(
                        emptyPage,
                        Map.of(),
                        Map.of(),
                        PublicWikiCatalogOverviewTimings.empty()
                )
        );

        ExtendedModelMap model =
                new ExtendedModelMap();

        String viewName =
                controller.listPage(
                        null,
                        "   ",
                        0,
                        20,
                        model
                );

        assertThat(viewName)
                .isEqualTo(
                        "wiki/public/index"
                );

        assertThat(
                model.getAttribute(
                        "selectedType"
                )
        ).isNull();

        assertThat(
                model.getAttribute(
                        "keyword"
                )
        ).isEqualTo("");

        verify(
                articleTypePathMapper,
                never()
        ).fromPath(
                anyString()
        );

        verify(
                getPublicWikiCatalogOverviewUseCase
        ).execute(
                new GetPublicWikiCatalogOverviewQuery(
                        null,
                        null,
                        0,
                        20
                )
        );
    }

    @Test
    @DisplayName(
            "Hiển thị chi tiết bài Wiki đã xuất bản với Markdown và mục lục"
    )
    void shouldShowPublishedWikiDetail() {
        PublishedWikiArticleDTO article =
                createPublishedDTO();

        RenderedWikiContent renderedContent =
                new RenderedWikiContent(
                        """
                        <h2 id="tong-quan">Tổng quan</h2>
                        <p>Nội dung công khai.</p>
                        <h3 id="canh-gioi">Cảnh giới</h3>
                        """,
                        List.of(
                                new WikiTocItem(
                                        2,
                                        "Tổng quan",
                                        "tong-quan"
                                ),
                                new WikiTocItem(
                                        3,
                                        "Cảnh giới",
                                        "canh-gioi"
                                )
                        )
                );

        when(
                articleTypePathMapper.fromPath(
                        "character"
                )
        ).thenReturn(
                ArticleType.CHARACTER
        );

        when(
                getPublishedArticleUseCase.execute(
                        new GetPublishedWikiArticleQuery(
                                ArticleType.CHARACTER,
                                "tran-binh-an"
                        )
                )
        ).thenReturn(
                article
        );

        when(
                articleTypePathMapper.toPath(
                        ArticleType.CHARACTER
                )
        ).thenReturn(
                "character"
        );

        when(
                wikiMarkdownRenderer.render(
                        article.content()
                )
        ).thenReturn(
                renderedContent
        );

        WikiAppreciationDetailState appreciationState =
                new WikiAppreciationDetailState(
                        new java.math.BigDecimal("4.80"),
                        10L,
                        null
                );
        when(
                getWikiAppreciationDetailStateUseCase.execute(
                        article.id(),
                        null
                )
        ).thenReturn(
                appreciationState
        );

        ExtendedModelMap model =
                new ExtendedModelMap();

        MockHttpServletRequest request =
                new MockHttpServletRequest();

        String viewName =
                controller.detailPage(
                        "character",
                        "tran-binh-an",
                        request,
                        model
                );

        assertThat(viewName)
                .isEqualTo(
                        "wiki/public/detail"
                );

        assertThat(model.getAttribute("article"))
                .isInstanceOf(PublicWikiArticleDetailView.class);
        PublicWikiArticleDetailView detailView =
                (PublicWikiArticleDetailView) model.getAttribute("article");
        assertThat(detailView.id()).isEqualTo(article.id());
        assertThat(detailView.title()).isEqualTo(article.title());
        assertThat(detailView.slug()).isEqualTo(article.slug());
        assertThat(detailView.articleType()).isEqualTo(article.articleType());
        assertThat(detailView.summary()).isEqualTo(article.summary());
        assertThat(detailView.content()).isEqualTo(article.content());
        assertThat(detailView.publishedAt()).isEqualTo(article.publishedAt());
        assertThat(detailView.updatedAt()).isEqualTo(article.updatedAt());
        assertThat(detailView.contentVersion()).isEqualTo(article.contentVersion());

        assertThat(
                model.getAttribute(
                        "articleTypePath"
                )
        ).isEqualTo(
                "character"
        );

        assertThat(
                model.getAttribute(
                        "renderedContent"
                )
        ).isEqualTo(
                renderedContent
        );

        assertThat(
                model.getAttribute(
                        "isSaved"
                )
        ).isEqualTo(
                false
        );

        assertThat(
                model.getAttribute(
                        "isAppreciationEligible"
                )
        ).isEqualTo(
                true
        );

        assertThat(
                model.getAttribute(
                        "appreciationState"
                )
        ).isEqualTo(
                appreciationState
        );

        verify(getWikiAppreciationDetailStateUseCase)
                .execute(article.id(), null);

        verify(isWikiArticleSavedUseCase, never())
                .execute(any(), any());

        verify(getPublishedArticleUseCase)
                .execute(
                        new GetPublishedWikiArticleQuery(
                                ArticleType.CHARACTER,
                                "tran-binh-an"
                        )
                );

        verify(
                wikiMarkdownRenderer
        ).render(
                article.content()
        );
    }

    @Test
    @DisplayName(
            "Chi tiết bài viết cho người dùng đã đăng nhập: isSaved = true khi bài viết đã được lưu"
    )
    void shouldExposeSavedTrueWhenUserHasSavedArticle() {
        PublishedWikiArticleDTO article =
                createPublishedDTO();

        RenderedWikiContent renderedContent =
                new RenderedWikiContent(
                        "<h1>Trần Bình An</h1>",
                        List.of()
                );

        when(
                articleTypePathMapper.fromPath(
                        "character"
                )
        ).thenReturn(
                ArticleType.CHARACTER
        );

        when(
                getPublishedArticleUseCase.execute(
                        any(GetPublishedWikiArticleQuery.class)
                )
        ).thenReturn(
                article
        );

        when(
                articleTypePathMapper.toPath(
                        ArticleType.CHARACTER
                )
        ).thenReturn(
                "character"
        );

        when(
                wikiMarkdownRenderer.render(
                        article.content()
                )
        ).thenReturn(
                renderedContent
        );

        when(
                isWikiArticleSavedUseCase.execute(
                        USER_ID,
                        ARTICLE_ID
                )
        ).thenReturn(
                true
        );

        when(
                getWikiAppreciationDetailStateUseCase.execute(
                        article.id(),
                        USER_ID
                )
        ).thenReturn(
                new WikiAppreciationDetailState(
                        new java.math.BigDecimal("4.80"),
                        10L,
                        new java.math.BigDecimal("5.0")
                )
        );

        MockHttpServletRequest request =
                new MockHttpServletRequest();
        AuthenticatedRequestIdentityTestSupport.attach(
                request,
                new AuthenticatedRequestIdentity(
                        USER_ID,
                        "reader@universe.local",
                        "Reader",
                        null,
                        "reader_user",
                        UserStatus.ACTIVE,
                        UserRole.USER
                )
        );

        ExtendedModelMap model =
                new ExtendedModelMap();

        String viewName =
                controller.detailPage(
                        "character",
                        "tran-binh-an",
                        request,
                        model
                );

        assertThat(viewName)
                .isEqualTo(
                        "wiki/public/detail"
                );

        assertThat(
                model.getAttribute(
                        "isSaved"
                )
        ).isEqualTo(
                true
        );

        verify(isWikiArticleSavedUseCase)
                .execute(USER_ID, ARTICLE_ID);
    }

    @Test
    @DisplayName(
            "Chi tiết bài viết cho người dùng đã đăng nhập: isSaved = false khi bài viết chưa được lưu"
    )
    void shouldExposeSavedFalseWhenUserHasNotSavedArticle() {
        PublishedWikiArticleDTO article =
                createPublishedDTO();

        RenderedWikiContent renderedContent =
                new RenderedWikiContent(
                        "<h1>Trần Bình An</h1>",
                        List.of()
                );

        when(
                articleTypePathMapper.fromPath(
                        "character"
                )
        ).thenReturn(
                ArticleType.CHARACTER
        );

        when(
                getPublishedArticleUseCase.execute(
                        any(GetPublishedWikiArticleQuery.class)
                )
        ).thenReturn(
                article
        );

        when(
                articleTypePathMapper.toPath(
                        ArticleType.CHARACTER
                )
        ).thenReturn(
                "character"
        );

        when(
                wikiMarkdownRenderer.render(
                        article.content()
                )
        ).thenReturn(
                renderedContent
        );

        when(
                isWikiArticleSavedUseCase.execute(
                        USER_ID,
                        ARTICLE_ID
                )
        ).thenReturn(
                false
        );

        when(
                getWikiAppreciationDetailStateUseCase.execute(
                        article.id(),
                        USER_ID
                )
        ).thenReturn(
                new WikiAppreciationDetailState(
                        new java.math.BigDecimal("4.80"),
                        10L,
                        null
                )
        );

        MockHttpServletRequest request =
                new MockHttpServletRequest();
        AuthenticatedRequestIdentityTestSupport.attach(
                request,
                new AuthenticatedRequestIdentity(
                        USER_ID,
                        "reader@universe.local",
                        "Reader",
                        null,
                        "reader_user",
                        UserStatus.ACTIVE,
                        UserRole.USER
                )
        );

        ExtendedModelMap model =
                new ExtendedModelMap();

        String viewName =
                controller.detailPage(
                        "character",
                        "tran-binh-an",
                        request,
                        model
                );

        assertThat(viewName)
                .isEqualTo(
                        "wiki/public/detail"
                );

        assertThat(
                model.getAttribute(
                        "isSaved"
                )
        ).isEqualTo(
                false
        );

        verify(isWikiArticleSavedUseCase)
                .execute(USER_ID, ARTICLE_ID);
    }

    @Test
    @DisplayName("Bài viết FACTION đủ điều kiện đánh giá -> isAppreciationEligible = true và truy vấn trạng thái")
    void shouldComposeAppreciationStateForFaction() {
        PublishedWikiArticleDTO article = new PublishedWikiArticleDTO(
                ARTICLE_ID,
                "Lạc Phách Sơn",
                "lac-phach-son",
                "FACTION",
                "Tông môn của Trần Bình An.",
                "## Giới thiệu",
                PUBLISHED_AT,
                UPDATED_AT,
                1L
        );

        when(articleTypePathMapper.fromPath("faction")).thenReturn(ArticleType.FACTION);
        when(getPublishedArticleUseCase.execute(new GetPublishedWikiArticleQuery(ArticleType.FACTION, "lac-phach-son")))
                .thenReturn(article);
        when(articleTypePathMapper.toPath(ArticleType.FACTION)).thenReturn("faction");
        when(wikiMarkdownRenderer.render(article.content()))
                .thenReturn(new RenderedWikiContent("<p>Giới thiệu</p>", List.of()));

        WikiAppreciationDetailState appreciationState =
                new WikiAppreciationDetailState(new java.math.BigDecimal("4.90"), 20L, new java.math.BigDecimal("5.0"));
        when(getWikiAppreciationDetailStateUseCase.execute(ARTICLE_ID, null))
                .thenReturn(appreciationState);

        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletRequest request = new MockHttpServletRequest();

        String viewName = controller.detailPage("faction", "lac-phach-son", request, model);

        assertThat(viewName).isEqualTo("wiki/public/detail");
        assertThat(model.getAttribute("isAppreciationEligible")).isEqualTo(true);
        assertThat(model.getAttribute("appreciationState")).isEqualTo(appreciationState);
        verify(getWikiAppreciationDetailStateUseCase).execute(ARTICLE_ID, null);
    }

    @Test
    @DisplayName("Bài viết loại ITEM không đủ điều kiện đánh giá -> isAppreciationEligible = false, KHÔNG truy vấn appreciation")
    void shouldNotComposeAppreciationStateForIneligibleArticleType() {
        PublishedWikiArticleDTO article = new PublishedWikiArticleDTO(
                ARTICLE_ID,
                "Dưỡng Kiếm Hồ",
                "duong-kiem-ho",
                "ITEM",
                "Hồ lô chứa kiếm.",
                "## Pháp bảo",
                PUBLISHED_AT,
                UPDATED_AT,
                1L
        );

        when(articleTypePathMapper.fromPath("item")).thenReturn(ArticleType.ITEM);
        when(getPublishedArticleUseCase.execute(new GetPublishedWikiArticleQuery(ArticleType.ITEM, "duong-kiem-ho")))
                .thenReturn(article);
        when(articleTypePathMapper.toPath(ArticleType.ITEM)).thenReturn("item");
        when(wikiMarkdownRenderer.render(article.content()))
                .thenReturn(new RenderedWikiContent("<p>Pháp bảo</p>", List.of()));

        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletRequest request = new MockHttpServletRequest();

        String viewName = controller.detailPage("item", "duong-kiem-ho", request, model);

        assertThat(viewName).isEqualTo("wiki/public/detail");
        assertThat(model.getAttribute("isAppreciationEligible")).isEqualTo(false);
        assertThat(model.getAttribute("appreciationState")).isNull();
        verify(getWikiAppreciationDetailStateUseCase, never()).execute(any(), any());
    }

    @Test
    @DisplayName("Bài viết contentVersion > 1 và có updatedBy -> attributionLine = Cập nhật bởi {displayName}")
    void shouldRenderUpdatedByAttributionWhenContentVersionGreaterThanOne() {
        UUID creatorId = UUID.randomUUID();
        UUID editorId = UUID.randomUUID();
        PublishedWikiArticleDTO article = new PublishedWikiArticleDTO(
                ARTICLE_ID,
                "Trần Bình An",
                "tran-binh-an",
                "CHARACTER",
                "Nhân vật chính",
                "## Giới thiệu",
                PUBLISHED_AT,
                UPDATED_AT,
                2L,
                creatorId,
                editorId
        );

        when(articleTypePathMapper.fromPath("character")).thenReturn(ArticleType.CHARACTER);
        when(getPublishedArticleUseCase.execute(any())).thenReturn(article);
        when(articleTypePathMapper.toPath(ArticleType.CHARACTER)).thenReturn("character");
        when(wikiMarkdownRenderer.render(any())).thenReturn(new RenderedWikiContent("<p>Nội dung</p>", List.of()));
        when(getWikiAppreciationDetailStateUseCase.execute(any(), any()))
                .thenReturn(new WikiAppreciationDetailState(null, 0L, null));
        when(userIdentityContract.findPublicProfilesByIds(java.util.Set.of(editorId)))
                .thenReturn(Map.of(editorId, new com.universe.identity.contracts.dto.UserPublicProfileDTO(editorId, "Biên tập viên A", null, "bien_tap_vien_a")));

        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletRequest request = new MockHttpServletRequest();

        controller.detailPage("character", "tran-binh-an", request, model);

        assertThat(model.getAttribute("attributionLine")).isEqualTo("Cập nhật bởi Biên tập viên A");
    }

    @Test
    @DisplayName("Bài viết contentVersion = 1 và có createdBy -> attributionLine = Đăng bởi {displayName}")
    void shouldRenderCreatedByAttributionWhenContentVersionIsOne() {
        UUID creatorId = UUID.randomUUID();
        PublishedWikiArticleDTO article = new PublishedWikiArticleDTO(
                ARTICLE_ID,
                "Trần Bình An",
                "tran-binh-an",
                "CHARACTER",
                "Nhân vật chính",
                "## Giới thiệu",
                PUBLISHED_AT,
                UPDATED_AT,
                1L,
                creatorId,
                null
        );

        when(articleTypePathMapper.fromPath("character")).thenReturn(ArticleType.CHARACTER);
        when(getPublishedArticleUseCase.execute(any())).thenReturn(article);
        when(articleTypePathMapper.toPath(ArticleType.CHARACTER)).thenReturn("character");
        when(wikiMarkdownRenderer.render(any())).thenReturn(new RenderedWikiContent("<p>Nội dung</p>", List.of()));
        when(getWikiAppreciationDetailStateUseCase.execute(any(), any()))
                .thenReturn(new WikiAppreciationDetailState(null, 0L, null));
        when(userIdentityContract.findPublicProfilesByIds(java.util.Set.of(creatorId)))
                .thenReturn(Map.of(creatorId, new com.universe.identity.contracts.dto.UserPublicProfileDTO(creatorId, "Tác giả gốc", null, "tac_gia_goc")));

        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletRequest request = new MockHttpServletRequest();

        controller.detailPage("character", "tran-binh-an", request, model);

        assertThat(model.getAttribute("attributionLine")).isEqualTo("Đăng bởi Tác giả gốc");
    }

    @Test
    @DisplayName("Nạp danh sách publicContributors vào Model theo article.id() hiện tại")
    void shouldPopulatePublicContributorsInModel() {
        PublishedWikiArticleDTO article = createPublishedDTO();
        when(articleTypePathMapper.fromPath("character")).thenReturn(ArticleType.CHARACTER);
        when(getPublishedArticleUseCase.execute(any())).thenReturn(article);
        when(articleTypePathMapper.toPath(ArticleType.CHARACTER)).thenReturn("character");
        when(wikiMarkdownRenderer.render(any())).thenReturn(new RenderedWikiContent("<p>Nội dung</p>", List.of()));
        when(getWikiAppreciationDetailStateUseCase.execute(any(), any()))
                .thenReturn(new WikiAppreciationDetailState(null, 0L, null));

        List<com.universe.wiki.contracts.dto.WikiPublicContributorDTO> contributors = List.of(
                new com.universe.wiki.contracts.dto.WikiPublicContributorDTO("Độc giả 1", "https://avatar.com/1.png", 2L),
                new com.universe.wiki.contracts.dto.WikiPublicContributorDTO("Độc giả 2", null, 1L)
        );
        com.universe.wiki.contracts.dto.WikiPublicContributorsResult result =
                new com.universe.wiki.contracts.dto.WikiPublicContributorsResult(contributors, true);
        when(getWikiArticlePublicContributorsUseCase.execute(ARTICLE_ID)).thenReturn(result);

        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletRequest request = new MockHttpServletRequest();

        controller.detailPage("character", "tran-binh-an", request, model);

        assertThat(model.getAttribute("publicContributors")).isEqualTo(contributors);
        assertThat(model.getAttribute("publicContributorsLimitReached")).isEqualTo(true);
        verify(getWikiArticlePublicContributorsUseCase, times(1)).execute(ARTICLE_ID);
    }

    @Test
    void listPageWithPerformanceDiagnosticEnabledExecutesCleanly() {
        PublicWikiController diagnosticController = new PublicWikiController(
                getPublicWikiCatalogOverviewUseCase,
                getPublicWikiArticleDetailUseCase,
                articleTypePathMapper,
                wikiMarkdownRenderer,
                true
        );

        PublishedWikiArticlePageDTO emptyPage = new PublishedWikiArticlePageDTO(
                List.of(), 0, 20, 0, 0, true, true
        );
        when(getPublicWikiCatalogOverviewUseCase.execute(any())).thenReturn(
                new PublicWikiCatalogOverviewResult(
                        emptyPage,
                        Map.of(),
                        Map.of(),
                        PublicWikiCatalogOverviewTimings.empty()
                )
        );

        ExtendedModelMap model = new ExtendedModelMap();
        String view = diagnosticController.listPage(null, null, 0, 20, model);

        assertThat(view).isEqualTo("wiki/public/index");
        assertThat(model.getAttribute("articlePage")).isNotNull();
    }

    @Test
    @DisplayName("PublicWikiController delegates listPage to GetPublicWikiCatalogOverviewUseCase coordinator")
    void listPageDelegatesToOverviewCoordinator() {
        GetPublicWikiCatalogOverviewUseCase mockCoordinator =
                org.mockito.Mockito.mock(GetPublicWikiCatalogOverviewUseCase.class);
        PublicWikiController customController = new PublicWikiController(
                mockCoordinator,
                getPublicWikiArticleDetailUseCase,
                articleTypePathMapper,
                wikiMarkdownRenderer,
                false
        );

        PublishedWikiArticlePageDTO pageDTO = createPageDTO();
        PublicWikiCatalogOverviewResult result =
                new PublicWikiCatalogOverviewResult(
                        pageDTO,
                        Map.of(ARTICLE_ID, WikiAppreciationSummary.empty(ARTICLE_ID)),
                        Map.of(),
                        PublicWikiCatalogOverviewTimings.empty()
                );
        when(mockCoordinator.execute(any())).thenReturn(result);

        ExtendedModelMap model = new ExtendedModelMap();
        String view = customController.listPage("keyword", null, 0, 20, model);

        assertThat(view).isEqualTo("wiki/public/index");
        assertThat(model.getAttribute("articlePage")).isNotNull();
        assertThat(model.getAttribute("appreciationSummaries")).isNotNull();
        verify(mockCoordinator).execute(any());
    }

    @Test
    @DisplayName("PublicWikiController delegates detailPage to GetPublicWikiArticleDetailUseCase coordinator")
    void detailPageDelegatesToDetailCoordinator() {
        GetPublicWikiArticleDetailUseCase mockDetailCoordinator =
                org.mockito.Mockito.mock(GetPublicWikiArticleDetailUseCase.class);
        PublicWikiController customController = new PublicWikiController(
                getPublicWikiCatalogOverviewUseCase,
                mockDetailCoordinator,
                articleTypePathMapper,
                wikiMarkdownRenderer,
                false
        );

        PublishedWikiArticleDTO article = createPublishedDTO();
        when(articleTypePathMapper.fromPath("character")).thenReturn(ArticleType.CHARACTER);
        when(articleTypePathMapper.toPath(ArticleType.CHARACTER)).thenReturn("character");
        when(wikiMarkdownRenderer.render(article.content()))
                .thenReturn(new RenderedWikiContent("<p>Nội dung</p>", List.of()));
        when(mockDetailCoordinator.execute(new GetPublicWikiArticleDetailQuery(
                ArticleType.CHARACTER,
                "tran-binh-an",
                null
        ))).thenReturn(new PublicWikiArticleDetailResult(
                article,
                false,
                null,
                true,
                new WikiAppreciationDetailState(new BigDecimal("4.8"), 5L, null),
                "Đăng bởi Tác giả",
                com.universe.wiki.contracts.dto.WikiPublicContributorsResult.empty(),
                PublicWikiArticleDetailTimings.empty()
        ));

        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletRequest request = new MockHttpServletRequest();
        String view = customController.detailPage("character", "tran-binh-an", request, model);

        assertThat(view).isEqualTo("wiki/public/detail");
        assertThat(model.getAttribute("article")).isInstanceOf(PublicWikiArticleDetailView.class);
        assertThat(model.getAttribute("attributionLine")).isEqualTo("Đăng bởi Tác giả");
        verify(mockDetailCoordinator).execute(new GetPublicWikiArticleDetailQuery(
                ArticleType.CHARACTER,
                "tran-binh-an",
                null
        ));
    }

    @Test
    void detailPageWithPerformanceDiagnosticEnabledExecutesCleanly() {
        PublicWikiController diagnosticController = new PublicWikiController(
                getPublicWikiCatalogOverviewUseCase,
                getPublicWikiArticleDetailUseCase,
                articleTypePathMapper,
                wikiMarkdownRenderer,
                true
        );

        PublishedWikiArticleDTO article = createPublishedDTO();
        when(articleTypePathMapper.fromPath("character")).thenReturn(ArticleType.CHARACTER);
        when(articleTypePathMapper.toPath(ArticleType.CHARACTER)).thenReturn("character");
        when(getPublishedArticleUseCase.execute(any())).thenReturn(article);
        when(wikiMarkdownRenderer.render(any())).thenReturn(new RenderedWikiContent("<p>Nội dung</p>", List.of()));
        when(getWikiAppreciationDetailStateUseCase.execute(any(), any()))
                .thenReturn(new WikiAppreciationDetailState(null, 0L, null));

        ExtendedModelMap model = new ExtendedModelMap();
        String view = diagnosticController.detailPage("character", "tran-binh-an", new MockHttpServletRequest(), model);

        assertThat(view).isEqualTo("wiki/public/detail");
        assertThat(model.getAttribute("article")).isNotNull();
    }

    @Test
    @DisplayName("listPage rejects negative page number with IllegalArgumentException")
    void listPageRejectsNegativePage() {
        ExtendedModelMap model = new ExtendedModelMap();
        assertThatThrownBy(() -> controller.listPage(null, null, -1, 20, model))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page không được nhỏ hơn 0.");
        verifyNoInteractions(getPublicWikiCatalogOverviewUseCase);
    }

    @Test
    @DisplayName("listPage rejects zero page size with IllegalArgumentException")
    void listPageRejectsZeroSize() {
        ExtendedModelMap model = new ExtendedModelMap();
        assertThatThrownBy(() -> controller.listPage(null, null, 0, 0, model))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page size phải lớn hơn hoặc bằng 1.");
        verifyNoInteractions(getPublicWikiCatalogOverviewUseCase);
    }

    @Test
    @DisplayName("listPage rejects negative page size with IllegalArgumentException")
    void listPageRejectsNegativeSize() {
        ExtendedModelMap model = new ExtendedModelMap();
        assertThatThrownBy(() -> controller.listPage(null, null, 0, -5, model))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page size phải lớn hơn hoặc bằng 1.");
        verifyNoInteractions(getPublicWikiCatalogOverviewUseCase);
    }

    @Test
    @DisplayName("listPage rejects page size exceeding 100 with IllegalArgumentException")
    void listPageRejectsSizeExceedingMax() {
        ExtendedModelMap model = new ExtendedModelMap();
        assertThatThrownBy(() -> controller.listPage(null, null, 0, 101, model))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page size không được vượt quá 100.");
        verifyNoInteractions(getPublicWikiCatalogOverviewUseCase);
    }

    @Test
    @DisplayName("listPage accepts maximum page size 100")
    void listPageAcceptsMaxSize100() {
        PublishedWikiArticlePageDTO emptyPage = new PublishedWikiArticlePageDTO(
                List.of(), 0, 100, 0, 0, true, true
        );
        when(getPublicWikiCatalogOverviewUseCase.execute(any())).thenReturn(
                new PublicWikiCatalogOverviewResult(emptyPage, Map.of(), Map.of(), PublicWikiCatalogOverviewTimings.empty())
        );

        ExtendedModelMap model = new ExtendedModelMap();
        String view = controller.listPage(null, null, 0, 100, model);

        assertThat(view).isEqualTo("wiki/public/index");
        verify(getPublicWikiCatalogOverviewUseCase).execute(new GetPublicWikiCatalogOverviewQuery(null, null, 0, 100));
    }

    @Test
    @DisplayName("PublicWikiController constructor rejects null GetPublicWikiCatalogOverviewUseCase")
    void constructorRejectsNullCoordinator() {
        assertThatThrownBy(() -> new PublicWikiController(
                null,
                getPublicWikiArticleDetailUseCase,
                articleTypePathMapper,
                wikiMarkdownRenderer
        )).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("GetPublicWikiCatalogOverviewUseCase không được để trống.");
    }

    @Test
    @DisplayName("PublicWikiController constructor rejects null GetPublicWikiArticleDetailUseCase")
    void constructorRejectsNullDetailCoordinator() {
        assertThatThrownBy(() -> new PublicWikiController(
                getPublicWikiCatalogOverviewUseCase,
                null,
                articleTypePathMapper,
                wikiMarkdownRenderer
        )).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("GetPublicWikiArticleDetailUseCase không được để trống.");
    }

    private PublishedWikiArticlePageDTO
            createPageDTO() {

        PublishedWikiArticleListItemDTO item =
                new PublishedWikiArticleListItemDTO(
                        ARTICLE_ID,
                        "Trần Bình An",
                        "tran-binh-an",
                        "CHARACTER",
                        "Nhân vật chính của Kiếm Lai.",
                        PUBLISHED_AT,
                        UPDATED_AT
                );

        return new PublishedWikiArticlePageDTO(
                List.of(item),
                0,
                20,
                1L,
                1,
                true,
                true
        );
    }

    private PublishedWikiArticleDTO
            createPublishedDTO() {

        return new PublishedWikiArticleDTO(
                ARTICLE_ID,
                "Trần Bình An",
                "tran-binh-an",
                "CHARACTER",
                "Nhân vật chính của Kiếm Lai.",
                """
                ## Tổng quan

                Nội dung công khai.

                ### Cảnh giới
                """,
                PUBLISHED_AT,
                UPDATED_AT,
                1L
        );
    }
}