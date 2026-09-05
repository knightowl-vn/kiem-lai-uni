package com.universe.novel.application.narration;

import com.universe.novel.application.exceptions.ChapterNarrationSegmentInvalidStateException;
import com.universe.novel.application.exceptions.ChapterNarrationSegmentNotFoundException;
import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.ports.ChapterNarrationAudioFailureRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationAudioRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationSegmentRepositoryPort;
import com.universe.novel.application.ports.ManagedVoiceRepositoryPort;
import com.universe.novel.domain.narration.ChapterNarrationAudio;
import com.universe.novel.domain.narration.ChapterNarrationAudioFailure;
import com.universe.novel.domain.narration.ChapterNarrationSegment;
import com.universe.novel.domain.narration.ChapterNarrationSegmentStatus;
import com.universe.novel.domain.narration.ManagedVoice;
import com.universe.novel.domain.narration.ManagedVoiceStatus;
import com.universe.novel.domain.narration.NarrationAudioFailureStage;
import com.universe.novel.domain.narration.NarrationAudioOperation;
import com.universe.novel.domain.narration.NarrationTextSegment;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetChapterNarrationAudioHealthUseCase Unit Tests")
class GetChapterNarrationAudioHealthUseCaseTest {

    @Mock
    private ChapterNarrationSegmentRepositoryPort segmentRepositoryPort;

    @Mock
    private ManagedVoiceRepositoryPort managedVoiceRepositoryPort;

    @Mock
    private ChapterNarrationAudioRepositoryPort audioRepositoryPort;

    @Mock
    private ChapterNarrationAudioFailureRepositoryPort failureRepositoryPort;

    private GetChapterNarrationAudioHealthUseCase useCase;

    private static final UUID SEGMENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CHAPTER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID VOICE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID AUDIO_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID FAILURE_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID MEDIA_ASSET_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final Instant NOW = Instant.parse("2026-09-05T14:00:00Z");

    @BeforeEach
    void setUp() {
        useCase = new GetChapterNarrationAudioHealthUseCase(
                segmentRepositoryPort,
                managedVoiceRepositoryPort,
                audioRepositoryPort,
                failureRepositoryPort
        );
    }

    private ChapterNarrationSegment createCurrentSegment() {
        return ChapterNarrationSegment.create(
                SEGMENT_ID,
                CHAPTER_ID,
                0,
                "Trần Bình An cất bước ra đi.",
                NOW
        );
    }

    private ManagedVoice createVoice(long synthesisRevision, ManagedVoiceStatus status) {
        return ManagedVoice.rehydrate(
                VOICE_ID,
                "kiemlai-male-01",
                "Minh Đức",
                "minh-duc",
                status,
                1,
                status == ManagedVoiceStatus.ACTIVE,
                synthesisRevision,
                NOW,
                NOW
        );
    }

    private ChapterNarrationAudioFailure createFailure(
            NarrationAudioOperation op,
            NarrationAudioFailureStage stage,
            long attemptedRevision
    ) {
        return ChapterNarrationAudioFailure.create(
                FAILURE_ID,
                SEGMENT_ID,
                VOICE_ID,
                op,
                stage,
                attemptedRevision,
                "RuntimeException",
                NOW
        );
    }

    @Test
    @DisplayName("1. Derives MISSING when no assignment and no failure record exist")
    void shouldDeriveMissingWhenNoAssignmentAndNoFailure() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createVoice(1L, ManagedVoiceStatus.ACTIVE);

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty());
        when(failureRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty());

        GetChapterNarrationAudioHealthResult result = useCase.execute(new GetChapterNarrationAudioHealthQuery(SEGMENT_ID, VOICE_ID));

        assertThat(result.segmentId()).isEqualTo(SEGMENT_ID);
        assertThat(result.managedVoiceId()).isEqualTo(VOICE_ID);
        assertThat(result.status()).isEqualTo(ChapterNarrationAudioHealthStatus.MISSING);
        assertThat(result.assignmentId()).isNull();
        assertThat(result.mediaAssetId()).isNull();
        assertThat(result.generatedSynthesisRevision()).isNull();
        assertThat(result.currentSynthesisRevision()).isEqualTo(1L);
        assertThat(result.lastFailure()).isNull();
    }

    @Test
    @DisplayName("2. Derives FAILED when no assignment exists and unresolved failure record is present")
    void shouldDeriveFailedWhenNoAssignmentAndFailurePresent() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createVoice(2L, ManagedVoiceStatus.ACTIVE);
        ChapterNarrationAudioFailure failure = createFailure(
                NarrationAudioOperation.INITIAL_GENERATION,
                NarrationAudioFailureStage.TTS_SYNTHESIS,
                2L
        );

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty());
        when(failureRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(failure));

        GetChapterNarrationAudioHealthResult result = useCase.execute(SEGMENT_ID, VOICE_ID);

        assertThat(result.segmentId()).isEqualTo(SEGMENT_ID);
        assertThat(result.managedVoiceId()).isEqualTo(VOICE_ID);
        assertThat(result.status()).isEqualTo(ChapterNarrationAudioHealthStatus.FAILED);
        assertThat(result.assignmentId()).isNull();
        assertThat(result.mediaAssetId()).isNull();
        assertThat(result.generatedSynthesisRevision()).isNull();
        assertThat(result.currentSynthesisRevision()).isEqualTo(2L);
        assertThat(result.lastFailure()).isNotNull();
        assertThat(result.lastFailure().operation()).isEqualTo(NarrationAudioOperation.INITIAL_GENERATION);
        assertThat(result.lastFailure().stage()).isEqualTo(NarrationAudioFailureStage.TTS_SYNTHESIS);
        assertThat(result.lastFailure().attemptedSynthesisRevision()).isEqualTo(2L);
        assertThat(result.lastFailure().failureCount()).isEqualTo(1);
        assertThat(result.lastFailure().errorType()).isEqualTo("RuntimeException");
        assertThat(result.lastFailure().errorMessage()).isEqualTo("Narration TTS synthesis failed.");
    }

    @Test
    @DisplayName("3. Derives READY when assignment revision matches voice synthesis revision")
    void shouldDeriveReadyWhenAssignmentRevisionMatchesVoice() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createVoice(2L, ManagedVoiceStatus.ACTIVE);
        ChapterNarrationAudio audio = ChapterNarrationAudio.create(
                AUDIO_ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 2L, NOW
        );

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(audio));
        when(failureRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty());

        GetChapterNarrationAudioHealthResult result = useCase.execute(SEGMENT_ID, VOICE_ID);

        assertThat(result.segmentId()).isEqualTo(SEGMENT_ID);
        assertThat(result.managedVoiceId()).isEqualTo(VOICE_ID);
        assertThat(result.status()).isEqualTo(ChapterNarrationAudioHealthStatus.READY);
        assertThat(result.assignmentId()).isEqualTo(AUDIO_ID);
        assertThat(result.mediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
        assertThat(result.generatedSynthesisRevision()).isEqualTo(2L);
        assertThat(result.currentSynthesisRevision()).isEqualTo(2L);
        assertThat(result.lastFailure()).isNull();
    }

    @Test
    @DisplayName("4. Derives READY (compatible wins) even if stale failure record exists")
    void shouldDeriveReadyWhenCompatibleEvenWithStaleFailureRecord() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createVoice(2L, ManagedVoiceStatus.ACTIVE);
        ChapterNarrationAudio audio = ChapterNarrationAudio.create(
                AUDIO_ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 2L, NOW
        );
        ChapterNarrationAudioFailure staleFailure = createFailure(
                NarrationAudioOperation.REGENERATION,
                NarrationAudioFailureStage.MEDIA_UPLOAD,
                1L
        );

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(audio));
        when(failureRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(staleFailure));

        GetChapterNarrationAudioHealthResult result = useCase.execute(SEGMENT_ID, VOICE_ID);

        assertThat(result.status()).isEqualTo(ChapterNarrationAudioHealthStatus.READY);
        assertThat(result.assignmentId()).isEqualTo(AUDIO_ID);
        assertThat(result.lastFailure()).isNotNull();
        assertThat(result.lastFailure().attemptedSynthesisRevision()).isEqualTo(1L);
    }

    @Test
    @DisplayName("5. Derives OUTDATED when assignment revision differs from current voice synthesis revision")
    void shouldDeriveOutdatedWhenAssignmentRevisionDiffers() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createVoice(3L, ManagedVoiceStatus.ACTIVE); // Current voice is at revision 3
        ChapterNarrationAudio audio = ChapterNarrationAudio.create(
                AUDIO_ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 1L, NOW // Assignment is at revision 1
        );

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(audio));
        when(failureRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty());

        GetChapterNarrationAudioHealthResult result = useCase.execute(SEGMENT_ID, VOICE_ID);

        assertThat(result.status()).isEqualTo(ChapterNarrationAudioHealthStatus.OUTDATED);
        assertThat(result.assignmentId()).isEqualTo(AUDIO_ID);
        assertThat(result.generatedSynthesisRevision()).isEqualTo(1L);
        assertThat(result.currentSynthesisRevision()).isEqualTo(3L);
        assertThat(result.lastFailure()).isNull();
    }

    @Test
    @DisplayName("6. Derives OUTDATED with diagnostics when regeneration attempt failed for stale assignment")
    void shouldDeriveOutdatedWithDiagnosticsWhenRegenerationFailed() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createVoice(3L, ManagedVoiceStatus.ACTIVE);
        ChapterNarrationAudio audio = ChapterNarrationAudio.create(
                AUDIO_ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 1L, NOW
        );
        ChapterNarrationAudioFailure regenerationFailure = createFailure(
                NarrationAudioOperation.REGENERATION,
                NarrationAudioFailureStage.TTS_SYNTHESIS,
                3L
        );

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(audio));
        when(failureRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(regenerationFailure));

        GetChapterNarrationAudioHealthResult result = useCase.execute(SEGMENT_ID, VOICE_ID);

        assertThat(result.status()).isEqualTo(ChapterNarrationAudioHealthStatus.OUTDATED);
        assertThat(result.lastFailure()).isNotNull();
        assertThat(result.lastFailure().operation()).isEqualTo(NarrationAudioOperation.REGENERATION);
        assertThat(result.lastFailure().attemptedSynthesisRevision()).isEqualTo(3L);
    }

    @Test
    @DisplayName("7. Inspecting health succeeds for DISABLED voice without requiring ACTIVE status")
    void shouldAllowHealthInspectionForDisabledVoice() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice disabledVoice = createVoice(2L, ManagedVoiceStatus.DISABLED);
        ChapterNarrationAudio audio = ChapterNarrationAudio.create(
                AUDIO_ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 2L, NOW
        );

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(disabledVoice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(audio));
        when(failureRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty());

        GetChapterNarrationAudioHealthResult result = useCase.execute(SEGMENT_ID, VOICE_ID);

        assertThat(result.status()).isEqualTo(ChapterNarrationAudioHealthStatus.READY);
    }

    @Test
    @DisplayName("8. Rejects when segment does not exist: Throws ChapterNarrationSegmentNotFoundException")
    void shouldThrowWhenSegmentNotFound() {
        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isInstanceOf(ChapterNarrationSegmentNotFoundException.class);

        verifyNoInteractions(managedVoiceRepositoryPort);
        verifyNoInteractions(audioRepositoryPort);
        verifyNoInteractions(failureRepositoryPort);
    }

    @Test
    @DisplayName("9. Rejects when segment is not CURRENT: Throws ChapterNarrationSegmentInvalidStateException")
    void shouldThrowWhenSegmentNotCurrent() {
        ChapterNarrationSegment retiredSegment = ChapterNarrationSegment.rehydrate(
                SEGMENT_ID,
                CHAPTER_ID,
                0,
                "Văn bản cũ.",
                "Văn bản cũ.".length(),
                NarrationTextSegment.computeSha256("Văn bản cũ."),
                ChapterNarrationSegmentStatus.RETIRED,
                NOW,
                NOW
        );

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(retiredSegment));

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isInstanceOf(ChapterNarrationSegmentInvalidStateException.class)
                .hasMessageContaining("CURRENT");

        verifyNoInteractions(managedVoiceRepositoryPort);
        verifyNoInteractions(audioRepositoryPort);
        verifyNoInteractions(failureRepositoryPort);
    }

    @Test
    @DisplayName("10. Rejects when voice does not exist: Throws ManagedVoiceNotFoundException")
    void shouldThrowWhenVoiceNotFound() {
        ChapterNarrationSegment segment = createCurrentSegment();
        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isInstanceOf(ManagedVoiceNotFoundException.class);

        verifyNoInteractions(audioRepositoryPort);
        verifyNoInteractions(failureRepositoryPort);
    }

    @Test
    @DisplayName("11. Validates null inputs")
    void shouldRejectNullInputs() {
        assertThatThrownBy(() -> useCase.execute((GetChapterNarrationAudioHealthQuery) null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> useCase.execute(null, VOICE_ID))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
