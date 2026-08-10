package com.universe.wiki.entry.admin;

import com.universe.wiki.application.article.query.detail.GetWikiArticleDetailQuery;
import com.universe.wiki.application.article.query.detail.GetWikiArticleDetailUseCase;
import com.universe.wiki.application.article.query.list
        .ListWikiArticlesQuery;
import com.universe.wiki.application.article.query.list
        .ListWikiArticlesUseCase;
import com.universe.wiki.application.article.template
        .WikiArticleContentTemplateProvider;
import com.universe.wiki.application.revision.query.detail.GetWikiArticleRevisionDetailUseCase;
import com.universe.wiki.application.revision.query.list.ListWikiArticleRevisionsUseCase;
import com.universe.wiki.contracts.dto.WikiArticleDTO;
import com.universe.wiki.contracts.dto
        .WikiArticleListItemDTO;
import com.universe.wiki.contracts.dto
        .WikiArticlePageDTO;
import com.universe.wiki.domain.article.ArticleStatus;
import com.universe.wiki.domain.article.ArticleType;
import com.universe.wiki.entry.admin.form
        .CreateWikiArticleForm;
import com.universe.wiki.entry.admin.form.EditWikiArticleForm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.ui.ExtendedModelMap;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminWikiArticlePageControllerTest {

    private static final UUID ARTICLE_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final Instant CREATED_AT =
            Instant.parse(
                    "2026-08-07T01:00:00Z"
            );

    private static final Instant UPDATED_AT =
            Instant.parse(
                    "2026-08-07T02:00:00Z"
            );

    @Mock
    private ListWikiArticlesUseCase
            listWikiArticlesUseCase;

    @Mock
    private WikiArticleContentTemplateProvider
            contentTemplateProvider;
    
    @Mock
    private GetWikiArticleDetailUseCase
            getWikiArticleDetailUseCase;
    
    @Mock
    private ListWikiArticleRevisionsUseCase
            listWikiArticleRevisionsUseCase;

    @Mock
    private GetWikiArticleRevisionDetailUseCase
            getWikiArticleRevisionDetailUseCase;

    private AdminWikiArticlePageController
            controller;

    @BeforeEach
    void setUp() {

        controller =
                new AdminWikiArticlePageController(
                        listWikiArticlesUseCase,
                        contentTemplateProvider,
                        getWikiArticleDetailUseCase,
                        listWikiArticleRevisionsUseCase,
                        getWikiArticleRevisionDetailUseCase
                );
    }
    /*
     * =====================================================
     * LIST PAGE
     * =====================================================
     */

    @Test
    @DisplayName(
            "Hiển thị danh sách Wiki có đầy đủ bộ lọc"
    )
    void shouldShowWikiArticleListWithFilters() {
        WikiArticlePageDTO expectedPage =
                createPageDTO();

        when(
                listWikiArticlesUseCase.execute(
                        new ListWikiArticlesQuery(
                                "Trần Bình",
                                ArticleType.CHARACTER,
                                ArticleStatus.PUBLISHED,
                                0,
                                20
                        )
                )
        ).thenReturn(
                expectedPage
        );

        ExtendedModelMap model =
                new ExtendedModelMap();
        MockHttpServletResponse response =
                new MockHttpServletResponse();


        String viewName =
                controller.listPage(
                        "Trần Bình",
                        "character",
                        "published",
                        0,
                        20,
                        model, 
                        response
                );

        assertThat(viewName)
                .isEqualTo(
                        "admin/wiki/articles"
                );

        assertThat(
                model.getAttribute(
                        "articlePage"
                )
        ).isEqualTo(
                expectedPage
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
                        "selectedStatus"
                )
        ).isEqualTo(
                ArticleStatus.PUBLISHED
        );

        assertThat(
                model.getAttribute(
                        "pageTitle"
                )
        ).isEqualTo(
                "Quản lý Wiki"
        );

        assertThat(
                model.getAttribute(
                        "activeMenu"
                )
        ).isEqualTo(
                "wiki"
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

        ArticleStatus[] articleStatuses =
                (ArticleStatus[])
                        model.getAttribute(
                                "articleStatuses"
                        );

        assertThat(articleStatuses)
                .containsExactly(
                        ArticleStatus.values()
                );

        verify(listWikiArticlesUseCase)
                .execute(
                        new ListWikiArticlesQuery(
                                "Trần Bình",
                                ArticleType.CHARACTER,
                                ArticleStatus.PUBLISHED,
                                0,
                                20
                        )
                );
        assertThat(
                response.getHeader(
                        "Cache-Control"
                )
        ).isEqualTo(
                "no-store, no-cache, must-revalidate, max-age=0"
        );


        assertThat(
                response.getHeader(
                        "Pragma"
                )
        ).isEqualTo(
                "no-cache"
        );


        assertThat(
                response.getDateHeader(
                        "Expires"
                )
        ).isEqualTo(
                0L
        );
    }

    @Test
    @DisplayName(
            "Không áp dụng bộ lọc khi tham số để trống"
    )
    void shouldListArticlesWithoutFilters() {
        WikiArticlePageDTO emptyPage =
                new WikiArticlePageDTO(
                        List.of(),
                        0,
                        20,
                        0L,
                        0,
                        true,
                        true
                );

        when(
                listWikiArticlesUseCase.execute(
                        new ListWikiArticlesQuery(
                                null,
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
        MockHttpServletResponse response =
                new MockHttpServletResponse();


        String viewName =
                controller.listPage(
                        null,
                        "   ",
                        "",
                        0,
                        20,
                        model,
                        response
                );

        assertThat(viewName)
                .isEqualTo(
                        "admin/wiki/articles"
                );

        assertThat(
                model.getAttribute(
                        "keyword"
                )
        ).isEqualTo("");

        assertThat(
                model.getAttribute(
                        "selectedType"
                )
        ).isNull();

        assertThat(
                model.getAttribute(
                        "selectedStatus"
                )
        ).isNull();
        
        
        verify(listWikiArticlesUseCase)
                .execute(
                        new ListWikiArticlesQuery(
                                null,
                                null,
                                null,
                                0,
                                20
                        )
                );
    }

    @Test
    @DisplayName(
            "Từ chối article type không hợp lệ"
    )
    void shouldRejectInvalidArticleType() {
        ExtendedModelMap model =
                new ExtendedModelMap();
        MockHttpServletResponse response =
                new MockHttpServletResponse();


        assertThatThrownBy(() ->
                controller.listPage(
                        null,
                        "unknown-type",
                        null,
                        0,
                        20,
                        model, 
                        response
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Article type không hợp lệ: "
                                + "unknown-type"
                );

        verify(
                listWikiArticlesUseCase,
                never()
        ).execute(
                any(ListWikiArticlesQuery.class)
        );
    }

    /*
     * =====================================================
     * CREATE PAGE
     * =====================================================
     */

    @Test
    @DisplayName(
            "Hiển thị trang tạo bài Wiki mới"
    )
    void shouldShowCreateWikiArticlePage() {
        ExtendedModelMap model =
                new ExtendedModelMap();

        String viewName =
                controller.createPage(
                        model
                );

        assertThat(viewName)
                .isEqualTo(
                        "admin/wiki/create"
                );

        assertThat(
                model.getAttribute(
                        "form"
                )
        ).isInstanceOf(
                CreateWikiArticleForm.class
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

        assertThat(
                model.getAttribute(
                        "pageTitle"
                )
        ).isEqualTo(
                "Tạo bài Wiki"
        );

        assertThat(
                model.getAttribute(
                        "activeMenu"
                )
        ).isEqualTo(
                "wiki"
        );
    }
    
    
    /*
     * =====================================================
     * DETAIL PAGE
     * =====================================================
     */

    @Test
    @DisplayName(
            "Hiển thị trang chi tiết bài Wiki"
    )
    void shouldShowWikiArticleDetailPage() {

        WikiArticleDTO article =
                createDraftArticleDTO();


        when(
                getWikiArticleDetailUseCase.execute(
                        new GetWikiArticleDetailQuery(
                                ARTICLE_ID
                        )
                )
        ).thenReturn(
                article
        );


        ExtendedModelMap model =
                new ExtendedModelMap();


        String viewName =
                controller.detailPage(
                        ARTICLE_ID,
                        model
                );


        assertThat(viewName)
                .isEqualTo(
                        "admin/wiki/detail"
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
                        "pageTitle"
                )
        ).isEqualTo(
                "Chi tiết bài Wiki"
        );


        assertThat(
                model.getAttribute(
                        "activeMenu"
                )
        ).isEqualTo(
                "wiki"
        );


        verify(
                getWikiArticleDetailUseCase
        ).execute(
                new GetWikiArticleDetailQuery(
                        ARTICLE_ID
                )
        );
    }


    /*
     * =====================================================
     * EDIT DRAFT PAGE
     * =====================================================
     */

    @Test
    @DisplayName(
            "Hiển thị trang chỉnh sửa bài Wiki DRAFT"
    )
    void shouldShowEditDraftWikiArticlePage() {

        WikiArticleDTO article =
                createDraftArticleDTO();


        when(
                getWikiArticleDetailUseCase.execute(
                        new GetWikiArticleDetailQuery(
                                ARTICLE_ID
                        )
                )
        ).thenReturn(
                article
        );


        ExtendedModelMap model =
                new ExtendedModelMap();


        String viewName =
                controller.editPage(
                        ARTICLE_ID,
                        model
                );


        assertThat(viewName)
                .isEqualTo(
                        "admin/wiki/edit"
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
                        "draft"
                )
        ).isEqualTo(
                true
        );


        EditWikiArticleForm form =
                (EditWikiArticleForm)
                        model.getAttribute(
                                "form"
                        );


        assertThat(form)
                .isNotNull();


        assertThat(
                form.getTitle()
        ).isEqualTo(
                "Trần Bình An"
        );


        assertThat(
                form.getArticleType()
        ).isEqualTo(
                ArticleType.CHARACTER
        );


        assertThat(
                form.getSummary()
        ).isEqualTo(
                "Nhân vật chính của Kiếm Lai."
        );


        assertThat(
                form.getContent()
        ).isEqualTo(
                "Nội dung bài viết."
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


        assertThat(
                model.getAttribute(
                        "pageTitle"
                )
        ).isEqualTo(
                "Chỉnh sửa bài Wiki"
        );
    }


    /*
     * =====================================================
     * EDIT PUBLISHED PAGE
     * =====================================================
     */

    @Test
    @DisplayName(
            "Hiển thị trang chỉnh sửa bài Wiki PUBLISHED ở chế độ hạn chế"
    )
    void shouldShowEditPublishedWikiArticlePage() {

        WikiArticleDTO article =
                createPublishedArticleDTO();


        when(
                getWikiArticleDetailUseCase.execute(
                        new GetWikiArticleDetailQuery(
                                ARTICLE_ID
                        )
                )
        ).thenReturn(
                article
        );


        ExtendedModelMap model =
                new ExtendedModelMap();


        String viewName =
                controller.editPage(
                        ARTICLE_ID,
                        model
                );


        assertThat(viewName)
                .isEqualTo(
                        "admin/wiki/edit"
                );


        assertThat(
                model.getAttribute(
                        "draft"
                )
        ).isEqualTo(
                false
        );


        EditWikiArticleForm form =
                (EditWikiArticleForm)
                        model.getAttribute(
                                "form"
                        );


        assertThat(form)
                .isNotNull();


        assertThat(
                form.getTitle()
        ).isEqualTo(
                "Trần Bình An"
        );


        assertThat(
                form.getArticleType()
        ).isEqualTo(
                ArticleType.CHARACTER
        );


        assertThat(
                form.getSummary()
        ).isEqualTo(
                "Nhân vật chính của Kiếm Lai."
        );


        assertThat(
                form.getContent()
        ).isEqualTo(
                "Nội dung bài viết."
        );
    }


    /*
     * =====================================================
     * EDIT ARCHIVED PAGE
     * =====================================================
     */

    @Test
    @DisplayName(
            "Không mở trang chỉnh sửa trực tiếp cho bài Wiki ARCHIVED"
    )
    void shouldRejectEditArchivedWikiArticlePage() {

        when(
                getWikiArticleDetailUseCase.execute(
                        new GetWikiArticleDetailQuery(
                                ARTICLE_ID
                        )
                )
        ).thenReturn(
                createArchivedArticleDTO()
        );


        assertThatThrownBy(() ->
                controller.editPage(
                        ARTICLE_ID,
                        new ExtendedModelMap()
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Bài Wiki đã lưu trữ không thể chỉnh sửa trực tiếp."
                );
    }

    /*
     * =====================================================
     * CONTENT TEMPLATE
     * =====================================================
     */

    @Test
    @DisplayName(
            "Trả về content template theo ArticleType"
    )
    void shouldReturnContentTemplate() {
        String expectedTemplate =
                """
                ## Tổng quan

                ## Tu hành và năng lực

                ### Cảnh giới

                ### Công pháp
                """;

        when(
                contentTemplateProvider.getTemplate(
                        ArticleType.CHARACTER
                )
        ).thenReturn(
                expectedTemplate
        );

        String result =
                controller.contentTemplate(
                        ArticleType.CHARACTER
                );

        assertThat(result)
                .isEqualTo(
                        expectedTemplate
                );

        assertThat(result)
                .contains(
                        "## Tổng quan"
                )
                .contains(
                        "## Tu hành và năng lực"
                )
                .contains(
                        "### Cảnh giới"
                )
                .contains(
                        "### Công pháp"
                );

        verify(
                contentTemplateProvider
        ).getTemplate(
                ArticleType.CHARACTER
        );
    }

    /*
     * =====================================================
     * TEST DATA
     * =====================================================
     */

    private WikiArticlePageDTO createPageDTO() {
        WikiArticleListItemDTO item =
                new WikiArticleListItemDTO(
                        ARTICLE_ID,
                        "Trần Bình An",
                        "tran-binh-an",
                        "CHARACTER",
                        "PUBLISHED",
                        ADMIN_ID,
                        CREATED_AT,
                        UPDATED_AT,
                        3L
                );

        return new WikiArticlePageDTO(
                List.of(item),
                0,
                20,
                1L,
                1,
                true,
                true
        );
    }
    
    private WikiArticleDTO createDraftArticleDTO() {

        return new WikiArticleDTO(
                ARTICLE_ID,
                "Trần Bình An",
                "tran-binh-an",
                "CHARACTER",
                "Nhân vật chính của Kiếm Lai.",
                "Nội dung bài viết.",
                "DRAFT",
                ADMIN_ID,
                ADMIN_ID,
                null,
                null,
                CREATED_AT,
                UPDATED_AT,
                null,
                null,
                1L,
                1L
        );
    }


    private WikiArticleDTO createPublishedArticleDTO() {

        return new WikiArticleDTO(
                ARTICLE_ID,
                "Trần Bình An",
                "tran-binh-an",
                "CHARACTER",
                "Nhân vật chính của Kiếm Lai.",
                "Nội dung bài viết.",
                "PUBLISHED",
                ADMIN_ID,
                ADMIN_ID,
                ADMIN_ID,
                null,
                CREATED_AT,
                UPDATED_AT,
                UPDATED_AT,
                null,
                2L,
                1L
        );
    }


    private WikiArticleDTO createArchivedArticleDTO() {

        return new WikiArticleDTO(
                ARTICLE_ID,
                "Trần Bình An",
                "tran-binh-an",
                "CHARACTER",
                "Nhân vật chính của Kiếm Lai.",
                "Nội dung bài viết.",
                "ARCHIVED",
                ADMIN_ID,
                ADMIN_ID,
                ADMIN_ID,
                ADMIN_ID,
                CREATED_AT,
                UPDATED_AT,
                UPDATED_AT,
                UPDATED_AT,
                3L,
                1L
        );
    }
}