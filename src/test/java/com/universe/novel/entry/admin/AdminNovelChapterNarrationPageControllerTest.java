package com.universe.novel.entry.admin;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.narration.AdminChapterNarrationSegmentViewDTO;
import com.universe.novel.application.narration.AdminChapterNarrationStatusDTO;
import com.universe.novel.application.narration.AdminNarrationGenerationDispatcher;
import com.universe.novel.application.narration.AdminNarrationOperationState;
import com.universe.novel.application.narration.AdminNarrationOperationStatus;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    @Mock
    private AdminNarrationGenerationDispatcher dispatcher;

    private AdminNovelChapterNarrationPageController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminNovelChapterNarrationPageController(overviewUseCase, dispatcher);
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

    private GetAdminChapterNarrationOverviewResult createOverviewResult(ManagedVoiceDTO voice) {
        ChapterDTO chapter = createChapterDTO();
        VolumeDTO volume = createVolumeDTO();

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

        return new GetAdminChapterNarrationOverviewResult(
                chapter,
                volume,
                voice != null ? List.of(voice) : List.of(),
                voice,
                List.of(segmentView),
                1,
                1,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                false
        );
    }

    @Test
    @DisplayName("1. Renders narration overview page with correct model attributes, operation state, and no-cache headers")
    void shouldRenderNarrationOverviewPage() {
        ManagedVoiceDTO voice = createVoiceDTO();
        GetAdminChapterNarrationOverviewResult overviewResult = createOverviewResult(voice);
        AdminNarrationOperationState opState = AdminNarrationOperationState.running(CHAPTER_ID, VOICE_ID, NOW);

        when(overviewUseCase.execute(CHAPTER_ID, VOICE_ID)).thenReturn(overviewResult);
        when(dispatcher.getOperationState(CHAPTER_ID, VOICE_ID)).thenReturn(opState);

        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        String view = controller.narrationOverviewPage(CHAPTER_ID, VOICE_ID, model, response);

        assertThat(view).isEqualTo("admin/novel/chapter-narration");
        assertThat(model.get("chapter")).isEqualTo(overviewResult.chapter());
        assertThat(model.get("volume")).isEqualTo(overviewResult.volume());
        assertThat(model.get("voices")).isEqualTo(List.of(voice));
        assertThat(model.get("selectedVoice")).isEqualTo(voice);
        assertThat(model.get("segments")).isEqualTo(overviewResult.segments());
        assertThat(model.get("totalSegments")).isEqualTo(1);
        assertThat(model.get("currentSegmentCount")).isEqualTo(1);
        assertThat(model.get("readyCount")).isEqualTo(1);
        assertThat(model.get("outdatedCount")).isEqualTo(0);
        assertThat(model.get("missingCount")).isEqualTo(0);
        assertThat(model.get("failedCount")).isEqualTo(0);
        assertThat(model.get("currentGenerationRequiredCount")).isEqualTo(0);
        assertThat(model.get("retiredSegmentCount")).isEqualTo(0);
        assertThat(model.get("obsoleteRetiredSegmentCount")).isEqualTo(0);
        assertThat(model.get("obsoleteRetiredAudioCount")).isEqualTo(0);
        assertThat(model.get("contentChangeWarning")).isEqualTo(false);
        assertThat(model.get("operationState")).isEqualTo(opState);
        assertThat(model.get("isOperationRunning")).isEqualTo(true);
        assertThat(model.get("pageTitle")).isEqualTo("Quản lý giọng đọc Chapter");
        assertThat(model.get("activeMenu")).isEqualTo("novel");

        assertThat(response.getHeader("Cache-Control"))
                .contains("no-store, no-cache, must-revalidate");
        assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");
        assertThat(response.getDateHeader("Expires")).isEqualTo(0L);

        verify(overviewUseCase).execute(CHAPTER_ID, VOICE_ID);
        verify(dispatcher).getOperationState(CHAPTER_ID, VOICE_ID);
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

    @Test
    @DisplayName("4. Status JSON endpoint returns combined dispatcher state and H.8E4 health counters with no-cache headers")
    void shouldReturnNarrationStatusDTO() {
        ManagedVoiceDTO voice = createVoiceDTO();
        GetAdminChapterNarrationOverviewResult overviewResult = new GetAdminChapterNarrationOverviewResult(
                createChapterDTO(),
                createVolumeDTO(),
                List.of(voice),
                voice,
                List.of(),
                10,
                7,
                1,
                1,
                1,
                3,
                2,
                1,
                1,
                true
        );

        AdminNarrationOperationState opState = AdminNarrationOperationState.running(CHAPTER_ID, VOICE_ID, NOW);

        when(overviewUseCase.execute(CHAPTER_ID, VOICE_ID)).thenReturn(overviewResult);
        when(dispatcher.getOperationState(CHAPTER_ID, VOICE_ID)).thenReturn(opState);

        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<AdminChapterNarrationStatusDTO> responseEntity =
                controller.narrationStatus(CHAPTER_ID, VOICE_ID, response);

        assertThat(responseEntity.getStatusCode()).isEqualTo(HttpStatus.OK);
        AdminChapterNarrationStatusDTO dto = responseEntity.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.operationStatus()).isEqualTo("RUNNING");
        assertThat(dto.operationMessage()).isEqualTo(opState.message());
        assertThat(dto.startedAt()).isEqualTo(NOW);
        assertThat(dto.completedAt()).isNull();
        assertThat(dto.currentSegmentCount()).isEqualTo(10);
        assertThat(dto.readyCount()).isEqualTo(7);
        assertThat(dto.outdatedCount()).isEqualTo(1);
        assertThat(dto.missingCount()).isEqualTo(1);
        assertThat(dto.failedCount()).isEqualTo(1);
        assertThat(dto.currentGenerationRequiredCount()).isEqualTo(3);
        assertThat(dto.contentChangeWarning()).isTrue();
        assertThat(dto.obsoleteRetiredAudioCount()).isEqualTo(1);

        assertThat(response.getHeader("Cache-Control")).contains("no-store, no-cache, must-revalidate");
        assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");
    }

    @Test
    @DisplayName("5. Status JSON endpoint defaults to IDLE when no voice is selected")
    void shouldReturnIdleStatusWhenNoVoiceSelected() {
        GetAdminChapterNarrationOverviewResult overviewResult = createOverviewResult(null);

        when(overviewUseCase.execute(CHAPTER_ID, null)).thenReturn(overviewResult);

        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<AdminChapterNarrationStatusDTO> responseEntity =
                controller.narrationStatus(CHAPTER_ID, null, response);

        assertThat(responseEntity.getStatusCode()).isEqualTo(HttpStatus.OK);
        AdminChapterNarrationStatusDTO dto = responseEntity.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.operationStatus()).isEqualTo("IDLE");
        assertThat(dto.startedAt()).isNull();
        assertThat(dto.completedAt()).isNull();
    }
}

