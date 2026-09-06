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
import com.universe.shared.exceptions.BaseApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    private AdminNovelChapterNarrationCommandController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminNovelChapterNarrationCommandController(generateUseCase, regenerateUseCase);
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
}
