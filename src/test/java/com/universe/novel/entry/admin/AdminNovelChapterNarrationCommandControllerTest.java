package com.universe.novel.entry.admin;

import com.universe.novel.application.exceptions.ChapterNarrationAudioNotFoundException;
import com.universe.novel.application.exceptions.ChapterNarrationSegmentInvalidStateException;
import com.universe.novel.application.exceptions.ChapterNarrationSegmentNotFoundException;
import com.universe.novel.application.exceptions.ManagedVoiceInvalidStateException;
import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.exceptions.TtsProviderException;
import com.universe.novel.application.narration.AdminGenerateChapterNarrationAudioUseCase;
import com.universe.novel.application.narration.AdminRegenerateChapterNarrationAudioUseCase;
import com.universe.novel.application.narration.GenerateChapterNarrationAudioResult;
import com.universe.novel.application.narration.NarrationAudioGenerationOutcome;
import com.universe.novel.application.narration.RegenerateChapterNarrationAudioResult;
import com.universe.novel.application.narration.RegenerateNarrationAudioOutcome;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.narration.AdminNarrationDispatchResult;
import com.universe.novel.application.narration.AdminNarrationGenerationDispatcher;
import com.universe.novel.application.narration.AdminNarrationOperationState;
import com.universe.novel.application.narration.GetAdminChapterNarrationOverviewResult;
import com.universe.novel.application.narration.GetAdminChapterNarrationOverviewUseCase;
import com.universe.novel.application.voice.dto.ManagedVoiceDTO;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.contracts.dto.VolumeDTO;
import com.universe.shared.exceptions.BaseApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminNovelChapterNarrationCommandController Unit Tests")
class AdminNovelChapterNarrationCommandControllerTest {

    private static final UUID CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SEGMENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID VOICE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID MEDIA_ASSET_1_ID = UUID.fromString("44444444-4444-4444-4444-444444444441");
    private static final UUID MEDIA_ASSET_2_ID = UUID.fromString("44444444-4444-4444-4444-444444444442");

    @Mock
    private AdminGenerateChapterNarrationAudioUseCase generateUseCase;

    @Mock
    private AdminRegenerateChapterNarrationAudioUseCase regenerateUseCase;

    @Mock
    private AdminNarrationGenerationDispatcher dispatcher;

    @Mock
    private GetAdminChapterNarrationOverviewUseCase overviewUseCase;

    private AdminNovelChapterNarrationCommandController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminNovelChapterNarrationCommandController(
                generateUseCase, regenerateUseCase, dispatcher, overviewUseCase
        );
    }

    @Test
    @DisplayName("1. Generate POST accepts managedVoiceId, triggers generation, sets success flash on GENERATED, and redirects to ?voiceId=")
    void shouldGenerateAudioAndRedirectWithSuccessFlash() {
        GenerateChapterNarrationAudioResult result = new GenerateChapterNarrationAudioResult(
                UUID.randomUUID(), SEGMENT_ID, VOICE_ID, MEDIA_ASSET_1_ID, 1L,
                NarrationAudioGenerationOutcome.GENERATED
        );
        when(generateUseCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID)).thenReturn(result);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.generateAudio(CHAPTER_ID, SEGMENT_ID, VOICE_ID, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID + "/narration?voiceId=" + VOICE_ID);
        assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
                .isEqualTo("Tạo audio thuyết minh cho phân đoạn thành công.");
        verify(generateUseCase).execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID);
    }

    @Test
    @DisplayName("2. Generate POST handles REUSED race/no-op gracefully")
    void shouldHandleGenerateReusedOutcome() {
        GenerateChapterNarrationAudioResult result = new GenerateChapterNarrationAudioResult(
                UUID.randomUUID(), SEGMENT_ID, VOICE_ID, MEDIA_ASSET_1_ID, 1L,
                NarrationAudioGenerationOutcome.REUSED
        );
        when(generateUseCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID)).thenReturn(result);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.generateAudio(CHAPTER_ID, SEGMENT_ID, VOICE_ID, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID + "/narration?voiceId=" + VOICE_ID);
        assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
                .isEqualTo("Phân đoạn đã có audio thuyết minh tương thích.");
    }

    @Test
    @DisplayName("3. Generate POST handles STALE race/no-op gracefully")
    void shouldHandleGenerateStaleOutcome() {
        GenerateChapterNarrationAudioResult result = new GenerateChapterNarrationAudioResult(
                UUID.randomUUID(), SEGMENT_ID, VOICE_ID, MEDIA_ASSET_1_ID, 1L,
                NarrationAudioGenerationOutcome.STALE
        );
        when(generateUseCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID)).thenReturn(result);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.generateAudio(CHAPTER_ID, SEGMENT_ID, VOICE_ID, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID + "/narration?voiceId=" + VOICE_ID);
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                .isEqualTo("Phân đoạn đã có audio nhưng cần tái tạo do thay đổi cấu hình giọng đọc.");
    }

    @Test
    @DisplayName("4. Regenerate POST accepts managedVoiceId, triggers regeneration, sets success flash on REGENERATED, and redirects to ?voiceId=")
    void shouldRegenerateAudioAndRedirectWithSuccessFlash() {
        RegenerateChapterNarrationAudioResult result = new RegenerateChapterNarrationAudioResult(
                UUID.randomUUID(), SEGMENT_ID, VOICE_ID, MEDIA_ASSET_1_ID, MEDIA_ASSET_2_ID, 2L,
                RegenerateNarrationAudioOutcome.REGENERATED
        );
        when(regenerateUseCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID)).thenReturn(result);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.regenerateAudio(CHAPTER_ID, SEGMENT_ID, VOICE_ID, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID + "/narration?voiceId=" + VOICE_ID);
        assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
                .isEqualTo("Tái tạo audio thuyết minh cho phân đoạn thành công.");
        verify(regenerateUseCase).execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID);
    }

    @Test
    @DisplayName("5. Regenerate POST handles ALREADY_CURRENT race/no-op gracefully")
    void shouldHandleRegenerateAlreadyCurrentOutcome() {
        RegenerateChapterNarrationAudioResult result = new RegenerateChapterNarrationAudioResult(
                UUID.randomUUID(), SEGMENT_ID, VOICE_ID, MEDIA_ASSET_1_ID, MEDIA_ASSET_1_ID, 1L,
                RegenerateNarrationAudioOutcome.ALREADY_CURRENT
        );
        when(regenerateUseCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID)).thenReturn(result);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.regenerateAudio(CHAPTER_ID, SEGMENT_ID, VOICE_ID, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID + "/narration?voiceId=" + VOICE_ID);
        assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
                .isEqualTo("Audio thuyết minh của phân đoạn đã ở phiên bản mới nhất.");
    }

    @Test
    @DisplayName("6. Chapter/segment ownership mismatch uses typed exception and renders fixed safe Vietnamese flash")
    void shouldHandleOwnershipMismatchExceptionWithFixedSafeCopy() {
        when(generateUseCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID))
                .thenThrow(new ChapterNarrationSegmentNotFoundException(SEGMENT_ID, CHAPTER_ID));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.generateAudio(CHAPTER_ID, SEGMENT_ID, VOICE_ID, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID + "/narration?voiceId=" + VOICE_ID);
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                .isEqualTo("Không tìm thấy phân đoạn thuyết minh hoặc phân đoạn không thuộc chương này.");
    }

    @Test
    @DisplayName("7. Invalid segment state renders fixed safe Vietnamese flash")
    void shouldHandleInvalidSegmentStateWithFixedSafeCopy() {
        when(generateUseCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID))
                .thenThrow(new ChapterNarrationSegmentInvalidStateException("Segment is ARCHIVED"));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.generateAudio(CHAPTER_ID, SEGMENT_ID, VOICE_ID, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID + "/narration?voiceId=" + VOICE_ID);
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                .isEqualTo("Phân đoạn thuyết minh không ở trạng thái hợp lệ để tạo audio.");
    }

    @Test
    @DisplayName("8. Voice not found renders fixed safe Vietnamese flash")
    void shouldHandleVoiceNotFoundWithFixedSafeCopy() {
        when(generateUseCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID))
                .thenThrow(new ManagedVoiceNotFoundException(VOICE_ID));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.generateAudio(CHAPTER_ID, SEGMENT_ID, VOICE_ID, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID + "/narration?voiceId=" + VOICE_ID);
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                .isEqualTo("Không tìm thấy giọng đọc được chỉ định.");
    }

    @Test
    @DisplayName("9. Disabled voice rejection is caught and reported with fixed safe Vietnamese flash")
    void shouldHandleDisabledVoiceRejectionWithFixedSafeCopy() {
        when(generateUseCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID))
                .thenThrow(new ManagedVoiceInvalidStateException("Managed voice is not in ACTIVE status: DISABLED"));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.generateAudio(CHAPTER_ID, SEGMENT_ID, VOICE_ID, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID + "/narration?voiceId=" + VOICE_ID);
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                .isEqualTo("Giọng đọc đang ở trạng thái không thể tạo audio.");
    }

    @Test
    @DisplayName("10. Audio not found on regenerate renders fixed safe Vietnamese flash")
    void shouldHandleAudioNotFoundOnRegenerateWithFixedSafeCopy() {
        when(regenerateUseCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID))
                .thenThrow(new ChapterNarrationAudioNotFoundException(SEGMENT_ID, VOICE_ID));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.regenerateAudio(CHAPTER_ID, SEGMENT_ID, VOICE_ID, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID + "/narration?voiceId=" + VOICE_ID);
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                .isEqualTo("Không tìm thấy audio phân đoạn hiện tại để thực hiện tái tạo.");
    }

    @Test
    @DisplayName("11. Expected TtsProviderException operational failure redirects with safe Vietnamese message without raw provider details")
    void shouldHandleTtsFailureWithSafeErrorMessageWithoutRawProviderDetails() {
        when(generateUseCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID))
                .thenThrow(new TtsProviderException("VieNeu HTTP 500: Internal synthesis GPU memory exhaustion at http://192.168.1.50:9000/tts"));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.generateAudio(CHAPTER_ID, SEGMENT_ID, VOICE_ID, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID + "/narration?voiceId=" + VOICE_ID);
        String errorMessage = (String) redirectAttributes.getFlashAttributes().get("errorMessage");
        assertThat(errorMessage).isEqualTo("Tạo audio thuyết minh thất bại do lỗi xử lý. Vui lòng kiểm tra chi tiết lỗi trong bảng phân đoạn.");
        assertThat(errorMessage).doesNotContain("VieNeu");
        assertThat(errorMessage).doesNotContain("GPU");
        assertThat(errorMessage).doesNotContain("192.168.1.50");
    }

    @Test
    @DisplayName("12. Another expected BaseApplicationException operational failure is safely caught, logs, and redirects preserving voiceId")
    void shouldHandleOtherBaseApplicationExceptionSafely() {
        BaseApplicationException customOperationalException = new BaseApplicationException(
                "MEDIA_STORAGE_ERROR",
                "Underlying storage failed with I/O error at /var/data/storage/audio.mp3"
        ) {};
        when(regenerateUseCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID))
                .thenThrow(customOperationalException);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.regenerateAudio(CHAPTER_ID, SEGMENT_ID, VOICE_ID, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID + "/narration?voiceId=" + VOICE_ID);
        String errorMessage = (String) redirectAttributes.getFlashAttributes().get("errorMessage");
        assertThat(errorMessage).isEqualTo("Tái tạo audio thuyết minh thất bại do lỗi xử lý. Vui lòng kiểm tra chi tiết lỗi trong bảng phân đoạn.");
        assertThat(errorMessage).doesNotContain("MEDIA_STORAGE_ERROR");
        assertThat(errorMessage).doesNotContain("/var/data/storage");
    }

    @Test
    @DisplayName("13. Plain unexpected RuntimeException propagates instead of being swallowed into flash redirect")
    void shouldPropagateUnexpectedRuntimeException() {
        when(generateUseCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID))
                .thenThrow(new NullPointerException("Unexpected NPE in domain logic"));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        assertThatThrownBy(() -> controller.generateAudio(CHAPTER_ID, SEGMENT_ID, VOICE_ID, redirectAttributes))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Unexpected NPE in domain logic");

        when(regenerateUseCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID))
                .thenThrow(new IllegalStateException("Unexpected illegal state"));

        assertThatThrownBy(() -> controller.regenerateAudio(CHAPTER_ID, SEGMENT_ID, VOICE_ID, redirectAttributes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unexpected illegal state");
    }

    @Test
    @DisplayName("14. Null managedVoiceId parameter on generate returns error flash without calling use cases")
    void shouldRejectNullVoiceIdOnGenerate() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.generateAudio(CHAPTER_ID, SEGMENT_ID, null, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID + "/narration");
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                .isEqualTo("Vui lòng chọn một giọng đọc hợp lệ để tạo audio.");
        verify(generateUseCase, never()).execute(CHAPTER_ID, SEGMENT_ID, null);
    }

    @Test
    @DisplayName("15. Null managedVoiceId parameter on regenerate returns error flash without calling use cases")
    void shouldRejectNullVoiceIdOnRegenerate() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.regenerateAudio(CHAPTER_ID, SEGMENT_ID, null, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID + "/narration");
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                .isEqualTo("Vui lòng chọn một giọng đọc hợp lệ để tái tạo audio.");
        verify(regenerateUseCase, never()).execute(CHAPTER_ID, SEGMENT_ID, null);
    }

    private GetAdminChapterNarrationOverviewResult createOverview(String chapterStatus, String voiceStatus) {
        ChapterDTO chapter = new ChapterDTO(
                CHAPTER_ID,
                UUID.randomUUID(),
                1,
                "Chương Một",
                "chuong-mot",
                "Tóm tắt",
                "Nội dung",
                chapterStatus,
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                null,
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                1L,
                1L
        );
        VolumeDTO volume = new VolumeDTO(
                UUID.randomUUID(),
                "Quyển Một",
                "quyen-mot",
                "Tóm tắt",
                1,
                "PUBLISHED",
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                null,
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                1L
        );
        ManagedVoiceDTO voice = new ManagedVoiceDTO(
                VOICE_ID,
                "kiemlai-male-01",
                "Minh Đức",
                "minh-duc",
                voiceStatus,
                1,
                true,
                1L,
                Instant.now(),
                Instant.now()
        );

        return new GetAdminChapterNarrationOverviewResult(
                chapter,
                volume,
                List.of(voice),
                voice,
                List.of(),
                1,
                0,
                1,
                0,
                0,
                1,
                0,
                0,
                0,
                false,
                com.universe.novel.application.narration.AdminChapterNarrationPlaybackDTO.missing()
        );
    }

    @Test
    @DisplayName("16. Valid PUBLISHED chapter + ACTIVE voice dispatches async generation, sets success flash on STARTED, and redirects to ?voiceId=")
    void shouldDispatchWholeChapterGenerationWhenPublishedAndVoiceActive() {
        GetAdminChapterNarrationOverviewResult overview = createOverview("PUBLISHED", "ACTIVE");
        when(overviewUseCase.execute(CHAPTER_ID, VOICE_ID)).thenReturn(overview);

        AdminNarrationOperationState runningState = AdminNarrationOperationState.running(CHAPTER_ID, VOICE_ID, Instant.now());
        AdminNarrationDispatchResult dispatchResult = AdminNarrationDispatchResult.started(runningState);
        when(dispatcher.dispatch(CHAPTER_ID, VOICE_ID)).thenReturn(dispatchResult);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.generateAllAudio(CHAPTER_ID, VOICE_ID, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID + "/narration?voiceId=" + VOICE_ID);
        assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
                .isEqualTo(dispatchResult.message());
        verify(overviewUseCase).execute(CHAPTER_ID, VOICE_ID);
        verify(dispatcher).dispatch(CHAPTER_ID, VOICE_ID);
    }

    @Test
    void shouldDispatchLegacyAllReadyChapterThroughExistingOperationBoundary() {
        GetAdminChapterNarrationOverviewResult base = createOverview("PUBLISHED", "ACTIVE");
        GetAdminChapterNarrationOverviewResult ready = new GetAdminChapterNarrationOverviewResult(
                base.chapter(), base.volume(), base.voices(), base.selectedVoice(), List.of(),
                3, 3, 0, 0, 0, 0, 0, 0, 0, false,
                com.universe.novel.application.narration.AdminChapterNarrationPlaybackDTO.missing()
        );
        when(overviewUseCase.execute(CHAPTER_ID, VOICE_ID)).thenReturn(ready);
        AdminNarrationDispatchResult started = AdminNarrationDispatchResult.started(
                AdminNarrationOperationState.running(CHAPTER_ID, VOICE_ID, Instant.now()));
        when(dispatcher.dispatch(CHAPTER_ID, VOICE_ID)).thenReturn(started);

        RedirectAttributesModelMap attributes = new RedirectAttributesModelMap();
        assertThat(controller.generateAllAudio(CHAPTER_ID, VOICE_ID, attributes))
                .isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID + "/narration?voiceId=" + VOICE_ID);
        assertThat(attributes.getFlashAttributes().get("successMessage")).isEqualTo(started.message());
        verify(dispatcher).dispatch(CHAPTER_ID, VOICE_ID);
        verifyNoInteractions(generateUseCase, regenerateUseCase);
    }

    @Test
    @DisplayName("17. Non-PUBLISHED chapter is rejected before dispatch and never enters dispatcher")
    void shouldRejectGenerateAllWhenChapterNotPublished() {
        GetAdminChapterNarrationOverviewResult overview = createOverview("DRAFT", "ACTIVE");
        when(overviewUseCase.execute(CHAPTER_ID, VOICE_ID)).thenReturn(overview);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.generateAllAudio(CHAPTER_ID, VOICE_ID, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID + "/narration?voiceId=" + VOICE_ID);
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                .isEqualTo("Chỉ có thể tạo giọng đọc cho chương đã xuất bản (PUBLISHED).");
        verify(overviewUseCase).execute(CHAPTER_ID, VOICE_ID);
        verify(dispatcher, never()).dispatch(CHAPTER_ID, VOICE_ID);
    }

    @Test
    @DisplayName("18. Inactive voice is rejected before dispatch and never enters dispatcher")
    void shouldRejectGenerateAllWhenVoiceInactive() {
        GetAdminChapterNarrationOverviewResult overview = createOverview("PUBLISHED", "DISABLED");
        when(overviewUseCase.execute(CHAPTER_ID, VOICE_ID)).thenReturn(overview);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.generateAllAudio(CHAPTER_ID, VOICE_ID, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID + "/narration?voiceId=" + VOICE_ID);
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                .isEqualTo("Giọng đọc đang ở trạng thái không thể tạo audio.");
        verify(overviewUseCase).execute(CHAPTER_ID, VOICE_ID);
        verify(dispatcher, never()).dispatch(CHAPTER_ID, VOICE_ID);
    }

    @Test
    @DisplayName("19. Non-existent voice is caught from overview and never enters dispatcher")
    void shouldRejectGenerateAllWhenVoiceNotFound() {
        when(overviewUseCase.execute(CHAPTER_ID, VOICE_ID))
                .thenThrow(new ManagedVoiceNotFoundException(VOICE_ID));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.generateAllAudio(CHAPTER_ID, VOICE_ID, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID + "/narration?voiceId=" + VOICE_ID);
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                .isEqualTo("Không tìm thấy giọng đọc được chỉ định.");
        verify(dispatcher, never()).dispatch(CHAPTER_ID, VOICE_ID);
    }

    @Test
    @DisplayName("20. Non-existent chapter is caught from overview and never enters dispatcher")
    void shouldRejectGenerateAllWhenChapterNotFound() {
        when(overviewUseCase.execute(CHAPTER_ID, VOICE_ID))
                .thenThrow(new ChapterNotFoundException(CHAPTER_ID));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.generateAllAudio(CHAPTER_ID, VOICE_ID, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID + "/narration?voiceId=" + VOICE_ID);
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                .isEqualTo("Không thể khởi chạy tiến trình tạo giọng đọc do lỗi hệ thống.");
        verify(dispatcher, never()).dispatch(CHAPTER_ID, VOICE_ID);
    }

    @Test
    @DisplayName("21. Null voiceId parameter on generateAll returns error flash without calling overview or dispatcher")
    void shouldRejectNullVoiceIdOnGenerateAll() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.generateAllAudio(CHAPTER_ID, null, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID + "/narration");
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                .isEqualTo("Vui lòng chọn một giọng đọc hợp lệ để tạo audio.");
        verify(overviewUseCase, never()).execute(CHAPTER_ID, null);
        verify(dispatcher, never()).dispatch(CHAPTER_ID, null);
    }

    @Test
    @DisplayName("22. ALREADY_RUNNING dispatch result sets info flash message")
    void shouldHandleAlreadyRunningDispatchResult() {
        GetAdminChapterNarrationOverviewResult overview = createOverview("PUBLISHED", "ACTIVE");
        when(overviewUseCase.execute(CHAPTER_ID, VOICE_ID)).thenReturn(overview);

        AdminNarrationOperationState runningState = AdminNarrationOperationState.running(CHAPTER_ID, VOICE_ID, Instant.now());
        AdminNarrationDispatchResult dispatchResult = AdminNarrationDispatchResult.alreadyRunning(runningState);
        when(dispatcher.dispatch(CHAPTER_ID, VOICE_ID)).thenReturn(dispatchResult);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.generateAllAudio(CHAPTER_ID, VOICE_ID, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID + "/narration?voiceId=" + VOICE_ID);
        assertThat(redirectAttributes.getFlashAttributes().get("infoMessage"))
                .isEqualTo(dispatchResult.message());
    }

    @Test
    @DisplayName("23. REJECTED dispatch result sets error flash message")
    void shouldHandleRejectedDispatchResult() {
        GetAdminChapterNarrationOverviewResult overview = createOverview("PUBLISHED", "ACTIVE");
        when(overviewUseCase.execute(CHAPTER_ID, VOICE_ID)).thenReturn(overview);

        AdminNarrationOperationState failedState = AdminNarrationOperationState.failed(
                CHAPTER_ID, VOICE_ID, Instant.now(), Instant.now(), "Hàng đợi đầy"
        );
        AdminNarrationDispatchResult dispatchResult = AdminNarrationDispatchResult.rejected(failedState);
        when(dispatcher.dispatch(CHAPTER_ID, VOICE_ID)).thenReturn(dispatchResult);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.generateAllAudio(CHAPTER_ID, VOICE_ID, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID + "/narration?voiceId=" + VOICE_ID);
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                .isEqualTo(dispatchResult.message());
    }

    @Test
    @DisplayName("24. FAILED dispatch result sets error flash message")
    void shouldHandleFailedDispatchResult() {
        GetAdminChapterNarrationOverviewResult overview = createOverview("PUBLISHED", "ACTIVE");
        when(overviewUseCase.execute(CHAPTER_ID, VOICE_ID)).thenReturn(overview);

        AdminNarrationOperationState failedState = AdminNarrationOperationState.failed(
                CHAPTER_ID, VOICE_ID, Instant.now(), Instant.now(), "Không thể khởi chạy"
        );
        AdminNarrationDispatchResult dispatchResult = AdminNarrationDispatchResult.failed(failedState, "Không thể khởi chạy");
        when(dispatcher.dispatch(CHAPTER_ID, VOICE_ID)).thenReturn(dispatchResult);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.generateAllAudio(CHAPTER_ID, VOICE_ID, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/novel/chapters/" + CHAPTER_ID + "/narration?voiceId=" + VOICE_ID);
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                .isEqualTo("Không thể khởi chạy");
    }
}
