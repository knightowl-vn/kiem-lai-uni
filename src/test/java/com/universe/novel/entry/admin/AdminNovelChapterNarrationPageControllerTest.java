package com.universe.novel.entry.admin;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.narration.AdminChapterNarrationSegmentViewDTO;
import com.universe.novel.application.narration.ChapterNarrationAudioHealthStatus;
import com.universe.novel.application.narration.GetAdminChapterNarrationOverviewResult;
import com.universe.novel.application.narration.GetAdminChapterNarrationOverviewUseCase;
import com.universe.novel.application.voice.dto.ManagedVoiceDTO;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.contracts.dto.VolumeDTO;
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
@DisplayName("AdminNovelChapterNarrationPageController Unit Tests")
class AdminNovelChapterNarrationPageControllerTest {

    private static final UUID CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VOLUME_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID VOICE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant NOW = Instant.parse("2026-09-06T10:00:00Z");

    @Mock
    private GetAdminChapterNarrationOverviewUseCase overviewUseCase;

    private AdminNovelChapterNarrationPageController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminNovelChapterNarrationPageController(overviewUseCase);
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
                "PUBLISHED",
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                null,
                NOW,
                NOW,
                NOW,
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
                "PUBLISHED",
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                null,
                NOW,
                NOW,
                NOW,
                null,
                1L
        );
    }

    private ManagedVoiceDTO createVoiceDTO() {
        return new ManagedVoiceDTO(
                VOICE_ID,
                "kiemlai-male-01",
                "Minh Đức",
                "minh-duc",
                "ACTIVE",
                1,
                true,
                1L,
                NOW,
                NOW
        );
    }

    @Test
    @DisplayName("1. Renders narration overview page with correct model attributes and no-cache headers")
    void shouldRenderNarrationOverviewPage() {
        ChapterDTO chapter = createChapterDTO();
        VolumeDTO volume = createVolumeDTO();
        ManagedVoiceDTO voice = createVoiceDTO();

        AdminChapterNarrationSegmentViewDTO segmentView = new AdminChapterNarrationSegmentViewDTO(
                UUID.randomUUID(),
                0,
                "Trần Bình An cất bước.",
                22,
                ChapterNarrationAudioHealthStatus.READY,
                UUID.randomUUID(),
                UUID.randomUUID(),
                1L,
                1L,
                null
        );

        GetAdminChapterNarrationOverviewResult overviewResult = new GetAdminChapterNarrationOverviewResult(
                chapter,
                volume,
                List.of(voice),
                voice,
                List.of(segmentView),
                1,
                1,
                0,
                0,
                0
        );

        when(overviewUseCase.execute(CHAPTER_ID, VOICE_ID)).thenReturn(overviewResult);

        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        String view = controller.narrationOverviewPage(CHAPTER_ID, VOICE_ID, model, response);

        assertThat(view).isEqualTo("admin/novel/chapter-narration");
        assertThat(model.get("chapter")).isEqualTo(chapter);
        assertThat(model.get("volume")).isEqualTo(volume);
        assertThat(model.get("voices")).isEqualTo(List.of(voice));
        assertThat(model.get("selectedVoice")).isEqualTo(voice);
        assertThat(model.get("segments")).isEqualTo(List.of(segmentView));
        assertThat(model.get("totalSegments")).isEqualTo(1);
        assertThat(model.get("readyCount")).isEqualTo(1);
        assertThat(model.get("outdatedCount")).isEqualTo(0);
        assertThat(model.get("missingCount")).isEqualTo(0);
        assertThat(model.get("failedCount")).isEqualTo(0);
        assertThat(model.get("pageTitle")).isEqualTo("Quản lý giọng đọc Chapter");
        assertThat(model.get("activeMenu")).isEqualTo("novel");

        assertThat(response.getHeader("Cache-Control"))
                .contains("no-store, no-cache, must-revalidate");
        assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");
        assertThat(response.getDateHeader("Expires")).isEqualTo(0L);

        verify(overviewUseCase).execute(CHAPTER_ID, VOICE_ID);
    }

    @Test
    @DisplayName("2. Propagates ChapterNotFoundException when chapter does not exist")
    void shouldPropagateChapterNotFoundException() {
        when(overviewUseCase.execute(CHAPTER_ID, null))
                .thenThrow(new ChapterNotFoundException(CHAPTER_ID));

        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> controller.narrationOverviewPage(CHAPTER_ID, null, model, response))
                .isInstanceOf(ChapterNotFoundException.class);
    }

    @Test
    @DisplayName("3. Propagates ManagedVoiceNotFoundException when invalid voiceId is requested")
    void shouldPropagateManagedVoiceNotFoundException() {
        UUID nonExistentVoiceId = UUID.randomUUID();
        when(overviewUseCase.execute(CHAPTER_ID, nonExistentVoiceId))
                .thenThrow(new ManagedVoiceNotFoundException(nonExistentVoiceId));

        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> controller.narrationOverviewPage(CHAPTER_ID, nonExistentVoiceId, model, response))
                .isInstanceOf(ManagedVoiceNotFoundException.class);
    }
}
