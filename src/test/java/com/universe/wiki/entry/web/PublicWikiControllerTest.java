package com.universe.wiki.entry.web;

import com.universe.wiki.application.article.query.published
        .GetPublishedWikiArticleQuery;
import com.universe.wiki.application.article.query.published
        .GetPublishedWikiArticleUseCase;
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

import com.universe.wiki.entry.web.support
        .ArticleTypePathMapper;

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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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

    private PublicWikiController
            controller;

    @BeforeEach
    void setUp() {
        controller =
                new PublicWikiController(
                        listPublishedArticlesUseCase,
                        getPublishedArticleUseCase,
                        articleTypePathMapper,
                        wikiMarkdownRenderer,
                        isWikiArticleSavedUseCase,
                        getWikiAppreciationDetailStateUseCase,
                        getWikiAppreciationSummariesUseCase,
                        userIdentityContract,
                        getWikiArticlePublicContributorsUseCase
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
                listPublishedArticlesUseCase.execute(
                        new ListPublishedWikiArticlesQuery(
                                "Trần Bình",
                                ArticleType.CHARACTER,
                                0,
                                20
                        )
                )
        ).thenReturn(
                articlePage
        );

        when(
                getWikiAppreciationSummariesUseCase.execute(
                        List.of(ARTICLE_ID)
                )
        ).thenReturn(
                Map.of(ARTICLE_ID, WikiAppreciationSummary.empty(ARTICLE_ID))
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

        assertThat(
                model.getAttribute(
                        "articlePage"
                )
        ).isEqualTo(
                articlePage
        );

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

        verify(getWikiAppreciationSummariesUseCase, times(1))
                .execute(List.of(ARTICLE_ID));

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

        verify(listPublishedArticlesUseCase)
                .execute(
                        new ListPublishedWikiArticlesQuery(
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

        when(listPublishedArticlesUseCase.execute(any())).thenReturn(mixedPage);

        Map<UUID, WikiAppreciationSummary> expectedSummaries = Map.of(
                charId, new WikiAppreciationSummary(charId, new java.math.BigDecimal("4.7"), 128L),
                factionId, WikiAppreciationSummary.empty(factionId)
        );
        when(getWikiAppreciationSummariesUseCase.execute(List.of(charId, factionId)))
                .thenReturn(expectedSummaries);

        ExtendedModelMap model = new ExtendedModelMap();
        String viewName = controller.listPage(null, null, 0, 20, model);

        assertThat(viewName).isEqualTo("wiki/public/index");
        assertThat(model.getAttribute("articlePage")).isEqualTo(mixedPage);
        assertThat(model.getAttribute("appreciationSummaries")).isEqualTo(expectedSummaries);

        // Verification: called exactly once, and only for eligible IDs (no ITEM or REALM)
        verify(getWikiAppreciationSummariesUseCase, times(1))
                .execute(List.of(charId, factionId));
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

        when(listPublishedArticlesUseCase.execute(any())).thenReturn(ineligiblePage);

        ExtendedModelMap model = new ExtendedModelMap();
        String viewName = controller.listPage(null, "item", 0, 20, model);

        assertThat(viewName).isEqualTo("wiki/public/index");
        assertThat(model.getAttribute("articlePage")).isEqualTo(ineligiblePage);
        assertThat(model.getAttribute("appreciationSummaries")).isEqualTo(Map.of());

        // Verification: query use case is NEVER invoked
        verify(getWikiAppreciationSummariesUseCase, never()).execute(any());
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
                listPublishedArticlesUseCase.execute(
                        new ListPublishedWikiArticlesQuery(
                                null,
                                null,
                                0,
                                20
                        )
                )
        ).thenReturn(
                emptyPage
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

        verify(listPublishedArticlesUseCase)
                .execute(
                        new ListPublishedWikiArticlesQuery(
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

        assertThat(
                model.getAttribute(
                        "article"
                )
        ).isEqualTo(
                article
        );

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
                .thenReturn(Map.of(editorId, new com.universe.identity.contracts.dto.UserPublicProfileDTO(editorId, "Biên tập viên A", null)));

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
                .thenReturn(Map.of(creatorId, new com.universe.identity.contracts.dto.UserPublicProfileDTO(creatorId, "Tác giả gốc", null)));

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