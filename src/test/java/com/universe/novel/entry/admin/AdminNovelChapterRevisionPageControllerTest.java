package com.universe.novel.entry.admin;

import com.universe.novel.application.chapter.GetChapterDetailUseCase;
import com.universe.novel.application.chapter.revision.GetChapterRevisionDetailUseCase;
import com.universe.novel.application.chapter.revision.ListChapterRevisionsUseCase;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ChapterRevisionNotFoundException;
import com.universe.novel.application.volume.GetVolumeDetailUseCase;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.contracts.dto.VolumeDTO;
import com.universe.novel.contracts.dto.revision.ChapterRevisionDetailDTO;
import com.universe.novel.contracts.dto.revision.ChapterRevisionListItemDTO;
import com.universe.novel.contracts.dto.revision.ChapterRevisionListPageDTO;
import com.universe.novel.domain.ChapterStatus;
import com.universe.novel.domain.Slug;
import com.universe.novel.domain.revision.ChapterRevisionChangeType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminNovelChapterRevisionPageControllerTest {

    private static final UUID CHAPTER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID VOLUME_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID ACTOR_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final Instant NOW =
            Instant.parse("2026-08-20T10:00:00Z");

    @Mock
    private ListChapterRevisionsUseCase listChapterRevisionsUseCase;

    @Mock
    private GetChapterRevisionDetailUseCase getChapterRevisionDetailUseCase;

    @Mock
    private GetChapterDetailUseCase getChapterDetailUseCase;

    @Mock
    private GetVolumeDetailUseCase getVolumeDetailUseCase;

    private AdminNovelChapterRevisionPageController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminNovelChapterRevisionPageController(
                listChapterRevisionsUseCase,
                getChapterRevisionDetailUseCase,
                getChapterDetailUseCase,
                getVolumeDetailUseCase
        );
    }

    @Test
    @DisplayName("Hiển thị trang danh sách revision với no-cache headers và model hợp lệ")
    void shouldRenderRevisionListPage() {
        ChapterDTO chapter = createChapterDTO();
        VolumeDTO volume = createVolumeDTO();

        ChapterRevisionListItemDTO item = new ChapterRevisionListItemDTO(
                UUID.randomUUID(),
                CHAPTER_ID,
                1L,
                1L,
                1,
                "Chương Một",
                ChapterStatus.DRAFT,
                ChapterRevisionChangeType.CREATE_DRAFT,
                null,
                ACTOR_ID,
                NOW
        );

        ChapterRevisionListPageDTO pageResult = new ChapterRevisionListPageDTO(
                List.of(item),
                1,
                20,
                1L,
                1,
                false,
                false
        );

        when(getChapterDetailUseCase.execute(CHAPTER_ID)).thenReturn(chapter);
        when(getVolumeDetailUseCase.execute(VOLUME_ID)).thenReturn(volume);
        when(listChapterRevisionsUseCase.execute(CHAPTER_ID, 1, 20)).thenReturn(pageResult);

        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        String view = controller.listRevisionsPage(CHAPTER_ID, 1, 20, model, response);

        assertThat(view).isEqualTo("admin/novel/chapter-revisions");
        assertThat(model.get("chapter")).isEqualTo(chapter);
        assertThat(model.get("volume")).isEqualTo(volume);
        assertThat(model.get("pageResult")).isEqualTo(pageResult);
        assertThat(model.get("pageTitle")).isEqualTo("Lịch sử chỉnh sửa");
        assertThat(model.get("activeMenu")).isEqualTo("novel");

        assertThat(response.getHeader("Cache-Control"))
                .contains("no-store, no-cache, must-revalidate");
        assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");

        verify(getChapterDetailUseCase).execute(CHAPTER_ID);
        verify(getVolumeDetailUseCase).execute(VOLUME_ID);
        verify(listChapterRevisionsUseCase).execute(CHAPTER_ID, 1, 20);
    }

    @Test
    @DisplayName("Hiển thị trang chi tiết revision với rendered HTML và model hợp lệ")
    void shouldRenderRevisionDetailPage() {
        ChapterDTO chapter = createChapterDTO();
        VolumeDTO volume = createVolumeDTO();

        ChapterRevisionDetailDTO revision = new ChapterRevisionDetailDTO(
                UUID.randomUUID(),
                CHAPTER_ID,
                VOLUME_ID,
                2L,
                1L,
                1,
                "Chương Một",
                new Slug("chuong-mot"),
                "Tóm tắt",
                "## Tiêu đề Markdown",
                "<h2>Tiêu đề Markdown</h2>",
                ChapterStatus.DRAFT,
                ChapterRevisionChangeType.UPDATE_DRAFT,
                "Sửa tiêu đề",
                ACTOR_ID,
                NOW
        );

        when(getChapterDetailUseCase.execute(CHAPTER_ID)).thenReturn(chapter);
        when(getVolumeDetailUseCase.execute(VOLUME_ID)).thenReturn(volume);
        when(getChapterRevisionDetailUseCase.execute(CHAPTER_ID, 2L)).thenReturn(revision);

        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        String view = controller.revisionDetailPage(CHAPTER_ID, 2L, model, response);

        assertThat(view).isEqualTo("admin/novel/chapter-revision-detail");
        assertThat(model.get("chapter")).isEqualTo(chapter);
        assertThat(model.get("volume")).isEqualTo(volume);
        assertThat(model.get("revision")).isEqualTo(revision);
        assertThat(model.get("pageTitle")).isEqualTo("Chi tiết phiên bản");
        assertThat(model.get("activeMenu")).isEqualTo("novel");

        assertThat(response.getHeader("Cache-Control"))
                .contains("no-store, no-cache, must-revalidate");
        assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");

        verify(getChapterDetailUseCase).execute(CHAPTER_ID);
        verify(getVolumeDetailUseCase).execute(VOLUME_ID);
        verify(getChapterRevisionDetailUseCase).execute(CHAPTER_ID, 2L);
    }

    @Test
    @DisplayName("Ném ChapterNotFoundException khi truy cập danh sách revision của Chapter không tồn tại")
    void shouldThrowWhenChapterNotFoundInListPage() {
        when(getChapterDetailUseCase.execute(CHAPTER_ID))
                .thenThrow(new ChapterNotFoundException(CHAPTER_ID));

        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> controller.listRevisionsPage(CHAPTER_ID, 1, 20, model, response))
                .isInstanceOf(ChapterNotFoundException.class);
    }

    @Test
    @DisplayName("Ném ChapterRevisionNotFoundException khi truy cập phiên bản không tồn tại")
    void shouldThrowWhenRevisionNotFoundInDetailPage() {
        ChapterDTO chapter = createChapterDTO();
        VolumeDTO volume = createVolumeDTO();

        when(getChapterDetailUseCase.execute(CHAPTER_ID)).thenReturn(chapter);
        when(getVolumeDetailUseCase.execute(VOLUME_ID)).thenReturn(volume);
        when(getChapterRevisionDetailUseCase.execute(CHAPTER_ID, 99L))
                .thenThrow(new ChapterRevisionNotFoundException(CHAPTER_ID, 99L));

        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> controller.revisionDetailPage(CHAPTER_ID, 99L, model, response))
                .isInstanceOf(ChapterRevisionNotFoundException.class);
    }

    private ChapterDTO createChapterDTO() {
        return new ChapterDTO(
                CHAPTER_ID,
                VOLUME_ID,
                1,
                "Chương Một",
                "chuong-mot",
                "Tóm tắt",
                "Nội dung",
                "DRAFT",
                ACTOR_ID,
                ACTOR_ID,
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

    private VolumeDTO createVolumeDTO() {
        return new VolumeDTO(
                VOLUME_ID,
                "Quyển Một",
                "quyen-mot",
                "Tóm tắt quyển",
                1,
                "DRAFT",
                ACTOR_ID,
                ACTOR_ID,
                null,
                null,
                NOW,
                NOW,
                null,
                null,
                1L
        );
    }
}
