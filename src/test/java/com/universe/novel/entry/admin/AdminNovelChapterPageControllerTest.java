package com.universe.novel.entry.admin;

import com.universe.novel.application.chapter.GetChapterDetailUseCase;
import com.universe.novel.application.chapter.GetChapterListUseCase;
import com.universe.novel.application.chapter.render.NovelMarkdownRenderer;
import com.universe.novel.application.volume.GetVolumeDetailUseCase;
import com.universe.novel.application.volume.GetVolumeListUseCase;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.contracts.dto.ChapterListItemDTO;
import com.universe.novel.contracts.dto.ChapterListPageDTO;
import com.universe.novel.contracts.dto.VolumeDTO;
import com.universe.novel.entry.admin.form.CreateChapterForm;
import com.universe.novel.entry.admin.form.EditChapterForm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminNovelChapterPageControllerTest {

    private static final UUID VOLUME_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID CHAPTER_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "33333333-3333-3333-3333-333333333333"
            );

    private static final Instant NOW =
            Instant.parse(
                    "2026-08-20T10:00:00Z"
            );

    private static final int PAGE =
            1;

    private static final int SECOND_PAGE =
            2;

    private static final int SIZE =
            50;

    @Mock
    private GetChapterListUseCase
            getChapterListUseCase;

    @Mock
    private GetChapterDetailUseCase
            getChapterDetailUseCase;

    @Mock
    private GetVolumeDetailUseCase
            getVolumeDetailUseCase;

    @Mock
    private GetVolumeListUseCase
            getVolumeListUseCase;

    @Mock
    private NovelMarkdownRenderer
            novelMarkdownRenderer;

    private AdminNovelChapterPageController
            controller;

    @BeforeEach
    void setUp() {
        controller =
                new AdminNovelChapterPageController(
                        getChapterListUseCase,
                        getChapterDetailUseCase,
                        getVolumeDetailUseCase,
                        getVolumeListUseCase,
                        novelMarkdownRenderer
                );
    }

    @Test
    @DisplayName(
            "Hiển thị trang danh sách Chapter của Volume"
    )
    void shouldShowChapterListPage() {

        VolumeDTO volume =
                volumeDto();

        List<ChapterListItemDTO> chapters =
                List.of(
                        chapterListItemDto(
                                "DRAFT"
                        )
                );

        ChapterListPageDTO pageResult =
                new ChapterListPageDTO(
                        chapters,
                        PAGE,
                        SIZE,
                        1L,
                        1,
                        false,
                        false
                );

        when(
                getVolumeDetailUseCase.execute(
                        VOLUME_ID
                )
        ).thenReturn(
                volume
        );

        when(
                getChapterListUseCase.execute(
                        VOLUME_ID,
                        "",
                        "",
                        PAGE,
                        SIZE
                )
        ).thenReturn(
                pageResult
        );

        when(
                getVolumeListUseCase.execute()
        ).thenReturn(
                List.of(
                        volume
                )
        );

        ExtendedModelMap model =
                new ExtendedModelMap();

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        String viewName =
                controller.listPage(
                        VOLUME_ID,
                        null,
                        null,
                        PAGE,
                        model,
                        response
                );

        assertThat(
                viewName
        ).isEqualTo(
                "admin/novel/chapters"
        );

        assertThat(
                model.getAttribute(
                        "volume"
                )
        ).isEqualTo(
                volume
        );

        assertThat(
                model.getAttribute(
                        "chapters"
                )
        ).isEqualTo(
                chapters
        );

        assertThat(
                model.getAttribute(
                        "pageResult"
                )
        ).isEqualTo(
                pageResult
        );

        assertThat(
                model.getAttribute(
                        "volumes"
                )
        ).isEqualTo(
                List.of(
                        volume
                )
        );

        assertThat(
                model.getAttribute(
                        "keyword"
                )
        ).isEqualTo(
                ""
        );

        assertThat(
                model.getAttribute(
                        "selectedStatus"
                )
        ).isEqualTo(
                ""
        );

        assertThat(
                model.getAttribute(
                        "pageTitle"
                )
        ).isEqualTo(
                "Quản lý chương"
        );

        assertThat(
                model.getAttribute(
                        "activeMenu"
                )
        ).isEqualTo(
                "novel"
        );

        assertNoCacheHeaders(
                response
        );

        verify(
                getChapterListUseCase
        ).execute(
                VOLUME_ID,
                "",
                "",
                PAGE,
                SIZE
        );
    }

    @Test
    @DisplayName(
            "Lọc danh sách Chapter theo keyword, status và page"
    )
    void shouldFilterChapterListByKeywordStatusAndPage() {

        VolumeDTO volume =
                volumeDto();

        ChapterListItemDTO draft =
                chapterListItemDto(
                        "DRAFT"
                );

        List<ChapterListItemDTO> chapters =
                List.of(
                        draft
                );

        ChapterListPageDTO pageResult =
                new ChapterListPageDTO(
                        chapters,
                        SECOND_PAGE,
                        SIZE,
                        51L,
                        2,
                        true,
                        false
                );

        when(
                getVolumeDetailUseCase.execute(
                        VOLUME_ID
                )
        ).thenReturn(
                volume
        );

        when(
                getChapterListUseCase.execute(
                        VOLUME_ID,
                        "Chương Một",
                        "DRAFT",
                        SECOND_PAGE,
                        SIZE
                )
        ).thenReturn(
                pageResult
        );

        when(
                getVolumeListUseCase.execute()
        ).thenReturn(
                List.of(
                        volume
                )
        );

        ExtendedModelMap model =
                new ExtendedModelMap();

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        String viewName =
                controller.listPage(
                        VOLUME_ID,
                        "Chương Một",
                        "DRAFT",
                        SECOND_PAGE,
                        model,
                        response
                );

        assertThat(
                viewName
        ).isEqualTo(
                "admin/novel/chapters"
        );

        assertThat(
                model.getAttribute(
                        "chapters"
                )
        ).isEqualTo(
                chapters
        );

        assertThat(
                model.getAttribute(
                        "pageResult"
                )
        ).isEqualTo(
                pageResult
        );

        assertThat(
                model.getAttribute(
                        "keyword"
                )
        ).isEqualTo(
                "Chương Một"
        );

        assertThat(
                model.getAttribute(
                        "selectedStatus"
                )
        ).isEqualTo(
                "DRAFT"
        );

        assertThat(
                ((ChapterListPageDTO) model.getAttribute(
                        "pageResult"
                )).page()
        ).isEqualTo(
                SECOND_PAGE
        );

        verify(
                getChapterListUseCase
        ).execute(
                VOLUME_ID,
                "Chương Một",
                "DRAFT",
                SECOND_PAGE,
                SIZE
        );
    }

    @Test
    @DisplayName(
            "Preview Markdown Chapter trả HTML từ renderer"
    )
    void shouldPreviewChapterMarkdown() {

        when(
                novelMarkdownRenderer.renderToHtml(
                        "## Hello"
                )
        ).thenReturn(
                "<h2>Hello</h2>\n"
        );

        String html =
                controller.previewChapterContent(
                        "## Hello"
                );

        assertThat(
                html
        ).isEqualTo(
                "<h2>Hello</h2>\n"
        );

        verify(
                novelMarkdownRenderer
        ).renderToHtml(
                "## Hello"
        );
    }

    @Test
    @DisplayName(
            "Hiển thị trang tạo Chapter"
    )
    void shouldShowCreateChapterPage() {

        when(
                getVolumeDetailUseCase.execute(
                        VOLUME_ID
                )
        ).thenReturn(
                volumeDto()
        );

        ExtendedModelMap model =
                new ExtendedModelMap();

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        String viewName =
                controller.createPage(
                        VOLUME_ID,
                        model,
                        response
                );

        assertThat(
                viewName
        ).isEqualTo(
                "admin/novel/chapter-create"
        );

        assertThat(
                model.getAttribute(
                        "form"
                )
        ).isInstanceOf(
                CreateChapterForm.class
        );

        assertThat(
                model.getAttribute(
                        "pageTitle"
                )
        ).isEqualTo(
                "Tạo Chapter"
        );

        assertThat(
                model.getAttribute(
                        "activeMenu"
                )
        ).isEqualTo(
                "novel"
        );

        assertNoCacheHeaders(
                response
        );
    }

    @Test
    @DisplayName(
            "Hiển thị trang chi tiết Chapter"
    )
    void shouldShowChapterDetailPage() {

        ChapterDTO chapter =
                chapterDto(
                        "DRAFT"
                );

        VolumeDTO volume =
                volumeDto();

        when(
                getChapterDetailUseCase.execute(
                        CHAPTER_ID
                )
        ).thenReturn(
                chapter
        );

        when(
                getVolumeDetailUseCase.execute(
                        VOLUME_ID
                )
        ).thenReturn(
                volume
        );

        ExtendedModelMap model =
                new ExtendedModelMap();

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        String viewName =
                controller.detailPage(
                        CHAPTER_ID,
                        model,
                        response
                );

        assertThat(
                viewName
        ).isEqualTo(
                "admin/novel/chapter-detail"
        );

        assertThat(
                model.getAttribute(
                        "chapter"
                )
        ).isEqualTo(
                chapter
        );

        assertThat(
                model.getAttribute(
                        "volume"
                )
        ).isEqualTo(
                volume
        );

        assertThat(
                model.getAttribute(
                        "moveForm"
                )
        ).isNull();

        assertThat(
                model.getAttribute(
                        "volumes"
                )
        ).isNull();

        assertThat(
                model.getAttribute(
                        "pageTitle"
                )
        ).isEqualTo(
                "Chi tiết Chapter"
        );

        assertThat(
                ((ChapterDTO) model.getAttribute(
                        "chapter"
                )).status()
        ).isEqualTo(
                "DRAFT"
        );

        assertNoCacheHeaders(
                response
        );
    }

    @Test
    @DisplayName(
            "Hiển thị trang chỉnh sửa Chapter DRAFT"
    )
    void shouldShowEditDraftChapterPage() {

        ChapterDTO chapter =
                chapterDto(
                        "DRAFT"
                );

        when(
                getChapterDetailUseCase.execute(
                        CHAPTER_ID
                )
        ).thenReturn(
                chapter
        );

        when(
                getVolumeDetailUseCase.execute(
                        VOLUME_ID
                )
        ).thenReturn(
                volumeDto()
        );

        ExtendedModelMap model =
                new ExtendedModelMap();

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        RedirectAttributesModelMap redirectAttributes =
                new RedirectAttributesModelMap();

        String viewName =
                controller.editPage(
                        CHAPTER_ID,
                        model,
                        response,
                        redirectAttributes
                );

        assertThat(
                viewName
        ).isEqualTo(
                "admin/novel/chapter-edit"
        );

        assertThat(
                model.getAttribute(
                        "form"
                )
        ).isInstanceOf(
                EditChapterForm.class
        );

        EditChapterForm form =
                (EditChapterForm) model.getAttribute(
                        "form"
                );

        assertThat(
                form.getChapterNumber()
        ).isEqualTo(
                1
        );

        assertThat(
                form.getTitle()
        ).isEqualTo(
                "Chương Một"
        );

        assertThat(
                form.getSummary()
        ).isEqualTo(
                "Tóm tắt."
        );

        assertThat(
                form.getContent()
        ).isEqualTo(
                "Nội dung."
        );

        assertNoCacheHeaders(
                response
        );
    }

    @Test
    @DisplayName(
            "Redirect chi tiết khi mở trang chỉnh sửa Chapter PUBLISHED"
    )
    void shouldRedirectEditPageWhenPublished() {

        when(
                getChapterDetailUseCase.execute(
                        CHAPTER_ID
                )
        ).thenReturn(
                chapterDto(
                        "PUBLISHED"
                )
        );

        ExtendedModelMap model =
                new ExtendedModelMap();

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        RedirectAttributesModelMap redirectAttributes =
                new RedirectAttributesModelMap();

        String viewName =
                controller.editPage(
                        CHAPTER_ID,
                        model,
                        response,
                        redirectAttributes
                );

        assertThat(
                viewName
        ).isEqualTo(
                "redirect:/admin/novel/chapters/"
                        + CHAPTER_ID
        );

        assertThat(
                redirectAttributes
                        .getFlashAttributes()
                        .get(
                                "errorMessage"
                        )
        ).isEqualTo(
                "Chapter không còn ở trạng thái DRAFT nên không thể chỉnh sửa."
        );

        assertThat(
                model.getAttribute(
                        "form"
                )
        ).isNull();

        assertNoCacheHeaders(
                response
        );

        verify(
                getVolumeDetailUseCase,
                never()
        ).execute(
                VOLUME_ID
        );
    }

    @Test
    @DisplayName(
            "Redirect chi tiết khi mở trang chỉnh sửa Chapter ARCHIVED"
    )
    void shouldRedirectEditPageWhenArchived() {

        when(
                getChapterDetailUseCase.execute(
                        CHAPTER_ID
                )
        ).thenReturn(
                chapterDto(
                        "ARCHIVED"
                )
        );

        ExtendedModelMap model =
                new ExtendedModelMap();

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        RedirectAttributesModelMap redirectAttributes =
                new RedirectAttributesModelMap();

        String viewName =
                controller.editPage(
                        CHAPTER_ID,
                        model,
                        response,
                        redirectAttributes
                );

        assertThat(
                viewName
        ).isEqualTo(
                "redirect:/admin/novel/chapters/"
                        + CHAPTER_ID
        );

        assertThat(
                redirectAttributes
                        .getFlashAttributes()
                        .get(
                                "errorMessage"
                        )
        ).isEqualTo(
                "Chapter không còn ở trạng thái DRAFT nên không thể chỉnh sửa."
        );

        assertThat(
                model.getAttribute(
                        "form"
                )
        ).isNull();

        assertNoCacheHeaders(
                response
        );
    }

    private void assertNoCacheHeaders(
            MockHttpServletResponse response
    ) {
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

    private VolumeDTO volumeDto() {
        return new VolumeDTO(
                VOLUME_ID,
                "Quyển Một - Lung Trung Tước",
                "quyen-1",
                "Mô tả.",
                1,
                "PUBLISHED",
                ADMIN_ID,
                ADMIN_ID,
                ADMIN_ID,
                null,
                NOW,
                NOW,
                NOW,
                null,
                2L
        );
    }

    private ChapterDTO chapterDto(
            String status
    ) {
        return new ChapterDTO(
                CHAPTER_ID,
                VOLUME_ID,
                1,
                "Chương Một",
                "quyen-1-chuong-1",
                "Tóm tắt.",
                "Nội dung.",
                status,
                ADMIN_ID,
                ADMIN_ID,
                null,
                null,
                NOW,
                NOW,
                null,
                null,
                1L,
                1L
        );
    }

    private ChapterListItemDTO chapterListItemDto(
            String status
    ) {
        return new ChapterListItemDTO(
                CHAPTER_ID,
                1,
                "Chương Một",
                "quyen-1-chuong-1",
                status,
                NOW
        );
    }
}