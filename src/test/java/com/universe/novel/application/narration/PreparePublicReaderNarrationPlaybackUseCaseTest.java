package com.universe.novel.application.narration;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ManagedVoiceInvalidStateException;
import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.ports.ManagedVoiceRepositoryPort;
import com.universe.novel.application.ports.ReaderChapterAccessQueryPort;
import com.universe.novel.domain.narration.ManagedVoice;
import com.universe.novel.domain.narration.ManagedVoiceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PreparePublicReaderNarrationPlaybackUseCase Unit Tests (MS-04.9H.7D1A, MS-04.9H.7D1A1)")
class PreparePublicReaderNarrationPlaybackUseCaseTest {

    @Mock
    private ReaderChapterAccessQueryPort readerChapterAccessQueryPort;
    @Mock
    private ManagedVoiceRepositoryPort managedVoiceRepositoryPort;
    @Mock
    private PrepareReaderNarrationPlaybackUseCase preparePlaybackUseCase;

    private PreparePublicReaderNarrationPlaybackUseCase publicUseCase;

    private static final UUID CHAPTER_ID = UUID.randomUUID();
    private static final UUID SEGMENT_ID = UUID.randomUUID();
    private static final UUID VOICE_ID = UUID.randomUUID();
    private static final String VOICE_KEY = "kiemlai-male-01";
    private static final UUID MEDIA_ASSET_ID = UUID.randomUUID();

    private final ReaderChapterAccessQueryPort.ReadableChapterReference readableChapterRef =
            new ReaderChapterAccessQueryPort.ReadableChapterReference(CHAPTER_ID, 1);

    @BeforeEach
    void setUp() {
        publicUseCase = new PreparePublicReaderNarrationPlaybackUseCase(
                readerChapterAccessQueryPort,
                managedVoiceRepositoryPort,
                preparePlaybackUseCase
        );
    }

    private ManagedVoice createManagedVoice(ManagedVoiceStatus status) {
        return ManagedVoice.rehydrate(
                VOICE_ID,
                VOICE_KEY,
                "Minh Đức",
                "vi-VN-NamMinhNeural",
                status,
                1,
                status == ManagedVoiceStatus.ACTIVE,
                1L,
                Instant.now(),
                Instant.now()
        );
    }

    @Test
    @DisplayName("Rejects null or blank inputs")
    void rejectsNullOrBlankInputs() {
        assertThatThrownBy(() -> publicUseCase.execute((PreparePublicReaderNarrationPlaybackCommand) null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> publicUseCase.execute(null, SEGMENT_ID, VOICE_KEY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> publicUseCase.execute(CHAPTER_ID, null, VOICE_KEY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> publicUseCase.execute(CHAPTER_ID, SEGMENT_ID, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> publicUseCase.execute(CHAPTER_ID, SEGMENT_ID, "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("1. Hidden chapter + ACTIVE/valid voiceKey -> ChapterNotFoundException, zero voice lookup, zero delegation")
    void hiddenChapterWithActiveVoiceKeyThrowsChapterNotFoundWithoutVoiceLookup() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> publicUseCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_KEY))
                .isInstanceOf(ChapterNotFoundException.class);

        verify(managedVoiceRepositoryPort, never()).findByVoiceKey(any());
        verify(preparePlaybackUseCase, never()).execute(any(), any(), any());
    }

    @Test
    @DisplayName("2. Hidden chapter + unknown voiceKey -> ChapterNotFoundException (not ManagedVoiceNotFoundException)")
    void hiddenChapterWithUnknownVoiceKeyThrowsChapterNotFound() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> publicUseCase.execute(CHAPTER_ID, SEGMENT_ID, "nonexistent-voice-key"))
                .isInstanceOf(ChapterNotFoundException.class);

        verify(managedVoiceRepositoryPort, never()).findByVoiceKey(any());
        verify(preparePlaybackUseCase, never()).execute(any(), any(), any());
    }

    @Test
    @DisplayName("3. Hidden chapter + inactive voiceKey -> ChapterNotFoundException (not ManagedVoiceInvalidStateException)")
    void hiddenChapterWithInactiveVoiceKeyThrowsChapterNotFound() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> publicUseCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_KEY))
                .isInstanceOf(ChapterNotFoundException.class);

        verify(managedVoiceRepositoryPort, never()).findByVoiceKey(any());
        verify(preparePlaybackUseCase, never()).execute(any(), any(), any());
    }

    @Test
    @DisplayName("4. Public chapter + unknown voiceKey -> ManagedVoiceNotFoundException")
    void publicChapterWithUnknownVoiceKeyThrowsVoiceNotFound() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(readableChapterRef));
        when(managedVoiceRepositoryPort.findByVoiceKey("unknown-voice"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> publicUseCase.execute(CHAPTER_ID, SEGMENT_ID, "unknown-voice"))
                .isInstanceOf(ManagedVoiceNotFoundException.class);

        verify(preparePlaybackUseCase, never()).execute(any(), any(), any());
    }

    @Test
    @DisplayName("5. Public chapter + inactive voiceKey -> ManagedVoiceInvalidStateException")
    void publicChapterWithInactiveVoiceKeyThrowsInvalidState() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(readableChapterRef));
        ManagedVoice inactiveVoice = createManagedVoice(ManagedVoiceStatus.DISABLED);
        when(managedVoiceRepositoryPort.findByVoiceKey(VOICE_KEY))
                .thenReturn(Optional.of(inactiveVoice));

        assertThatThrownBy(() -> publicUseCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_KEY))
                .isInstanceOf(ManagedVoiceInvalidStateException.class);

        verify(preparePlaybackUseCase, never()).execute(any(), any(), any());
    }

    @Test
    @DisplayName("6. Public chapter + active voice -> delegates using internal managedVoiceId and returns canonical voiceKey")
    void publicChapterWithActiveVoiceDelegatesAndReturnsCanonicalVoiceKey() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(readableChapterRef));
        ManagedVoice activeVoice = createManagedVoice(ManagedVoiceStatus.ACTIVE);
        when(managedVoiceRepositoryPort.findByVoiceKey(VOICE_KEY))
                .thenReturn(Optional.of(activeVoice));

        PrepareReaderNarrationSegmentResult immediateResult = new PrepareReaderNarrationSegmentResult(
                CHAPTER_ID,
                SEGMENT_ID,
                0,
                VOICE_ID,
                ChapterNarrationAudioHealthStatus.READY,
                ReaderNarrationPreparationAction.PLAY_NOW,
                ChapterNarrationAudioHealthStatus.READY,
                PrepareReaderNarrationSegmentOutcome.PLAYABLE_CACHED,
                MEDIA_ASSET_ID,
                false,
                false
        );
        PrepareReaderNarrationPlaybackResult internalResult = new PrepareReaderNarrationPlaybackResult(
                immediateResult,
                ReaderNarrationContinuationDispatchStatus.SCHEDULED
        );

        when(preparePlaybackUseCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID))
                .thenReturn(internalResult);

        // Supply un-trimmed uppercase input to verify canonical key return
        PreparePublicReaderNarrationPlaybackResult result = publicUseCase.execute(
                new PreparePublicReaderNarrationPlaybackCommand(CHAPTER_ID, SEGMENT_ID, "  " + VOICE_KEY + "  ")
        );

        assertThat(result).isNotNull();
        assertThat(result.voiceKey()).isEqualTo(VOICE_KEY);
        assertThat(result.isPlayableNow()).isTrue();
        assertThat(result.playbackResult()).isEqualTo(internalResult);

        verify(preparePlaybackUseCase).execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID);
    }
}
