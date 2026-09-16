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
import com.universe.wiki.application.saved.IsWikiArticleSavedUseCase;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
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
                        isWikiArticleSavedUseCase
                );
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
                UPDATED_AT
        );
    }
}