package com.universe.novel.application.narration;

import com.universe.novel.application.exceptions.ChapterNarrationSegmentInvalidStateException;
import com.universe.novel.application.exceptions.ChapterNarrationSegmentNotFoundException;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ManagedVoiceInvalidStateException;
import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.ports.ChapterNarrationAudioFailureRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationAudioRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationSegmentRepositoryPort;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.application.ports.ManagedVoiceRepositoryPort;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.ChapterStatus;
import com.universe.novel.domain.Slug;
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
import org.junit.jupiter.api.Nested;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PrepareReaderNarrationSegmentUseCase Unit Tests (MS-04.9H.7C2B)")
class PrepareReaderNarrationSegmentUseCaseTest {

    private static final UUID CHAPTER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_CHAPTER_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");
    private static final UUID VOLUME_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID SEGMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID VOICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID MEDIA_ASSET_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID MEDIA_ASSET_2_ID = UUID.fromString("00000000-0000-0000-0000-000000000006");
    private static final long SYNTHESIS_REVISION = 1L;

    @Mock
    private ChapterRepositoryPort chapterRepositoryPort;

    @Mock
    private ManagedVoiceRepositoryPort managedVoiceRepositoryPort;

    @Mock
    private ChapterNarrationSegmentRepositoryPort segmentRepositoryPort;

    @Mock
    private ChapterNarrationAudioRepositoryPort audioRepositoryPort;

    @Mock
    private ChapterNarrationAudioFailureRepositoryPort failureRepositoryPort;

    @Mock
    private GenerateChapterNarrationAudioUseCase generateChapterNarrationAudioUseCase;

    private ReaderNarrationPreparationDecisionPlanner decisionPlanner;
    private PrepareReaderNarrationSegmentUseCase useCase;

    @BeforeEach
    void setUp() {
        decisionPlanner = new ReaderNarrationPreparationDecisionPlanner();
        useCase = new PrepareReaderNarrationSegmentUseCase(
                chapterRepositoryPort,
                managedVoiceRepositoryPort,
                segmentRepositoryPort,
                audioRepositoryPort,
                failureRepositoryPort,
                decisionPlanner,
                generateChapterNarrationAudioUseCase
        );
    }

    private Chapter createChapter(ChapterStatus status) {
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();
        return Chapter.rehydrate(
                CHAPTER_ID,
                VOLUME_ID,
                1,
                "Chương 1",
                new Slug("chuong-1"),
                "Tóm tắt",
                "Nội dung chương",
                status,
                userId,
                userId,
                status == ChapterStatus.PUBLISHED ? userId : null,
                status == ChapterStatus.ARCHIVED ? userId : null,
                now,
                now,
                status == ChapterStatus.PUBLISHED ? now : null,
                status == ChapterStatus.ARCHIVED ? now : null,
                1L,
                1L
        );
    }

    private ManagedVoice createVoice(ManagedVoiceStatus status, long revision) {
        Instant now = Instant.now();
        return ManagedVoice.rehydrate(
                VOICE_ID,
                "voice-key",
                "Voice Name",
                "provider-voice-id",
                status,
                1,
                false,
                revision,
                now,
                now
        );
    }

    private ChapterNarrationSegment createSegment(UUID chapterId, ChapterNarrationSegmentStatus status) {
        Instant now = Instant.now();
        String text = "Văn bản phân đoạn test reader";
        return ChapterNarrationSegment.rehydrate(
                SEGMENT_ID,
                chapterId,
                0,
                text,
                text.length(),
                NarrationTextSegment.computeSha256(text),
                status,
                now,
                now
        );
    }

    private ChapterNarrationAudio createAudio(UUID mediaAssetId, long revision) {
        Instant now = Instant.now();
        return ChapterNarrationAudio.rehydrate(
                UUID.randomUUID(),
                SEGMENT_ID,
                VOICE_ID,
                mediaAssetId,
                revision,
                0L,
                now,
                now
        );
    }

    private ChapterNarrationAudioFailure createFailure() {
        return ChapterNarrationAudioFailure.create(
                UUID.randomUUID(),
                SEGMENT_ID,
                VOICE_ID,
                NarrationAudioOperation.INITIAL_GENERATION,
                NarrationAudioFailureStage.TTS_SYNTHESIS,
                SYNTHESIS_REVISION,
                "PROVIDER_ERROR",
                Instant.now()
        );
    }

    @Nested
    @DisplayName("Preflight Validation Tests")
    class PreflightTests {

        @Test
        @DisplayName("11. Rejects segment belonging to another chapter")
        void rejectsSegmentBelongingToAnotherChapter() {
            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(createChapter(ChapterStatus.PUBLISHED)));
            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createVoice(ManagedVoiceStatus.ACTIVE, SYNTHESIS_REVISION)));
            when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(createSegment(OTHER_CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT)));

            assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not belong to chapter");

            verify(generateChapterNarrationAudioUseCase, never()).execute(any(), any());
        }

        @Test
        @DisplayName("12. Rejects non-PUBLISHED chapter")
        void rejectsNonPublishedChapter() {
            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(createChapter(ChapterStatus.DRAFT)));

            assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Chỉ có thể chuẩn bị narration cho chapter ở trạng thái PUBLISHED");

            verify(generateChapterNarrationAudioUseCase, never()).execute(any(), any());
        }

        @Test
        @DisplayName("13. Rejects inactive managed voice")
        void rejectsInactiveVoice() {
            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(createChapter(ChapterStatus.PUBLISHED)));
            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createVoice(ManagedVoiceStatus.DISABLED, SYNTHESIS_REVISION)));

            assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID))
                    .isInstanceOf(ManagedVoiceInvalidStateException.class)
                    .hasMessageContaining("Managed voice is not in ACTIVE status");

            verify(generateChapterNarrationAudioUseCase, never()).execute(any(), any());
        }

        @Test
        @DisplayName("Rejects non-CURRENT segment during preflight")
        void rejectsNonCurrentSegment() {
            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(createChapter(ChapterStatus.PUBLISHED)));
            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createVoice(ManagedVoiceStatus.ACTIVE, SYNTHESIS_REVISION)));
            when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(createSegment(CHAPTER_ID, ChapterNarrationSegmentStatus.RETIRED)));

            assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID))
                    .isInstanceOf(ChapterNarrationSegmentInvalidStateException.class)
                    .hasMessageContaining("Chapter narration segment is not in CURRENT status");

            verify(generateChapterNarrationAudioUseCase, never()).execute(any(), any());
        }

        @Test
        @DisplayName("Rejects null arguments")
        void rejectsNullArguments() {
            assertThatThrownBy(() -> useCase.execute(null, SEGMENT_ID, VOICE_ID))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, null, VOICE_ID))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, SEGMENT_ID, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> useCase.execute((PrepareReaderNarrationSegmentCommand) null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Cached Playback Tests (READY & OUTDATED)")
    class CachedPlaybackTests {

        @Test
        @DisplayName("1. READY -> PLAYABLE_CACHED, current mediaAssetId returned, no generation called")
        void readyReturnsPlayableCachedWithoutGeneration() {
            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(createChapter(ChapterStatus.PUBLISHED)));
            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createVoice(ManagedVoiceStatus.ACTIVE, SYNTHESIS_REVISION)));
            when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(createSegment(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT)));

            ChapterNarrationAudio audio = createAudio(MEDIA_ASSET_ID, SYNTHESIS_REVISION);
            when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID)).thenReturn(Optional.of(audio));
            when(failureRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID)).thenReturn(Optional.empty());

            PrepareReaderNarrationSegmentResult result = useCase.execute(new PrepareReaderNarrationSegmentCommand(CHAPTER_ID, SEGMENT_ID, VOICE_ID));

            assertThat(result.initialHealth()).isEqualTo(ChapterNarrationAudioHealthStatus.READY);
            assertThat(result.initialAction()).isEqualTo(ReaderNarrationPreparationAction.PLAY_NOW);
            assertThat(result.finalHealth()).isEqualTo(ChapterNarrationAudioHealthStatus.READY);
            assertThat(result.outcome()).isEqualTo(PrepareReaderNarrationSegmentOutcome.PLAYABLE_CACHED);
            assertThat(result.mediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
            assertThat(result.preparationAttempted()).isFalse();
            assertThat(result.refreshRecommended()).isFalse();
            assertThat(result.isPlayableNow()).isTrue();
            assertThat(result.blocksPlayback()).isFalse();

            verify(generateChapterNarrationAudioUseCase, never()).execute(any(), any());
        }

        @Test
        @DisplayName("2. OUTDATED -> PLAYABLE_CACHED, cached mediaAssetId returned, refreshRecommended=true, no generation called, playback not blocked")
        void outdatedReturnsPlayableCachedWithRefreshRecommendedWithoutGeneration() {
            // Voice is revision 2, audio is revision 1
            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(createChapter(ChapterStatus.PUBLISHED)));
            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createVoice(ManagedVoiceStatus.ACTIVE, 2L)));
            when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(createSegment(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT)));

            ChapterNarrationAudio audio = createAudio(MEDIA_ASSET_ID, 1L);
            when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID)).thenReturn(Optional.of(audio));
            when(failureRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID)).thenReturn(Optional.empty());

            PrepareReaderNarrationSegmentResult result = useCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID);

            assertThat(result.initialHealth()).isEqualTo(ChapterNarrationAudioHealthStatus.OUTDATED);
            assertThat(result.initialAction()).isEqualTo(ReaderNarrationPreparationAction.PLAY_NOW_AND_REFRESH);
            assertThat(result.finalHealth()).isEqualTo(ChapterNarrationAudioHealthStatus.OUTDATED);
            assertThat(result.outcome()).isEqualTo(PrepareReaderNarrationSegmentOutcome.PLAYABLE_CACHED);
            assertThat(result.mediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
            assertThat(result.preparationAttempted()).isFalse();
            assertThat(result.refreshRecommended()).isTrue();
            assertThat(result.isPlayableNow()).isTrue();
            assertThat(result.blocksPlayback()).isFalse();

            verify(generateChapterNarrationAudioUseCase, never()).execute(any(), any());
        }
    }

    @Nested
    @DisplayName("Preparation Orchestration & Race Tests (MISSING & FAILED)")
    class PreparationOrchestrationTests {

        @Test
        @DisplayName("3. MISSING -> Generate called once, fresh re-read READY -> PREPARED_AND_PLAYABLE")
        void missingCallsGenerateAndReReadsReady() {
            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(createChapter(ChapterStatus.PUBLISHED)));
            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createVoice(ManagedVoiceStatus.ACTIVE, SYNTHESIS_REVISION)));
            when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(createSegment(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT)));

            // Initial read: missing audio
            when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                    .thenReturn(Optional.empty()) // initial
                    .thenReturn(Optional.of(createAudio(MEDIA_ASSET_ID, SYNTHESIS_REVISION))); // post-read
            when(failureRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                    .thenReturn(Optional.empty());

            when(generateChapterNarrationAudioUseCase.execute(SEGMENT_ID, VOICE_ID))
                    .thenReturn(new GenerateChapterNarrationAudioResult(
                            UUID.randomUUID(), SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, SYNTHESIS_REVISION, NarrationAudioGenerationOutcome.GENERATED
                    ));

            PrepareReaderNarrationSegmentResult result = useCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID);

            assertThat(result.initialHealth()).isEqualTo(ChapterNarrationAudioHealthStatus.MISSING);
            assertThat(result.initialAction()).isEqualTo(ReaderNarrationPreparationAction.PREPARE);
            assertThat(result.finalHealth()).isEqualTo(ChapterNarrationAudioHealthStatus.READY);
            assertThat(result.outcome()).isEqualTo(PrepareReaderNarrationSegmentOutcome.PREPARED_AND_PLAYABLE);
            assertThat(result.mediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
            assertThat(result.preparationAttempted()).isTrue();
            assertThat(result.refreshRecommended()).isFalse();
            assertThat(result.isPlayableNow()).isTrue();
            assertThat(result.blocksPlayback()).isFalse();
            assertThat(result.preparationSucceeded()).isTrue();

            verify(generateChapterNarrationAudioUseCase).execute(SEGMENT_ID, VOICE_ID);
        }

        @Test
        @DisplayName("4. FAILED -> Generate called once (retry prepare), fresh re-read READY -> PREPARED_AND_PLAYABLE")
        void failedCallsGenerateAndReReadsReady() {
            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(createChapter(ChapterStatus.PUBLISHED)));
            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createVoice(ManagedVoiceStatus.ACTIVE, SYNTHESIS_REVISION)));
            when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(createSegment(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT)));

            // Initial read: failed
            ChapterNarrationAudioFailure failure = createFailure();
            when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                    .thenReturn(Optional.empty()) // initial
                    .thenReturn(Optional.of(createAudio(MEDIA_ASSET_ID, SYNTHESIS_REVISION))); // post-read
            when(failureRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                    .thenReturn(Optional.of(failure)) // initial
                    .thenReturn(Optional.empty()); // post-read failure cleared

            when(generateChapterNarrationAudioUseCase.execute(SEGMENT_ID, VOICE_ID))
                    .thenReturn(new GenerateChapterNarrationAudioResult(
                            UUID.randomUUID(), SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, SYNTHESIS_REVISION, NarrationAudioGenerationOutcome.GENERATED
                    ));

            PrepareReaderNarrationSegmentResult result = useCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID);

            assertThat(result.initialHealth()).isEqualTo(ChapterNarrationAudioHealthStatus.FAILED);
            assertThat(result.initialAction()).isEqualTo(ReaderNarrationPreparationAction.RETRY_PREPARE);
            assertThat(result.finalHealth()).isEqualTo(ChapterNarrationAudioHealthStatus.READY);
            assertThat(result.outcome()).isEqualTo(PrepareReaderNarrationSegmentOutcome.PREPARED_AND_PLAYABLE);
            assertThat(result.mediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
            assertThat(result.preparationAttempted()).isTrue();

            verify(generateChapterNarrationAudioUseCase).execute(SEGMENT_ID, VOICE_ID);
        }

        @Test
        @DisplayName("5. Generate returns REUSED / race winner -> fresh READY state wins")
        void generateReturnsReusedAndFreshReadyWins() {
            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(createChapter(ChapterStatus.PUBLISHED)));
            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createVoice(ManagedVoiceStatus.ACTIVE, SYNTHESIS_REVISION)));
            when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(createSegment(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT)));

            when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(createAudio(MEDIA_ASSET_ID, SYNTHESIS_REVISION)));
            when(failureRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                    .thenReturn(Optional.empty());

            when(generateChapterNarrationAudioUseCase.execute(SEGMENT_ID, VOICE_ID))
                    .thenReturn(new GenerateChapterNarrationAudioResult(
                            UUID.randomUUID(), SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, SYNTHESIS_REVISION, NarrationAudioGenerationOutcome.REUSED
                    ));

            PrepareReaderNarrationSegmentResult result = useCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID);

            assertThat(result.outcome()).isEqualTo(PrepareReaderNarrationSegmentOutcome.PREPARED_AND_PLAYABLE);
            assertThat(result.finalHealth()).isEqualTo(ChapterNarrationAudioHealthStatus.READY);
            assertThat(result.mediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
        }

        @Test
        @DisplayName("6. Generate returns STALE but fresh state is READY (concurrent winner) -> playable result")
        void generateReturnsStaleButFreshStateIsReady() {
            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(createChapter(ChapterStatus.PUBLISHED)));
            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createVoice(ManagedVoiceStatus.ACTIVE, SYNTHESIS_REVISION)));
            when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(createSegment(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT)));

            when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(createAudio(MEDIA_ASSET_2_ID, SYNTHESIS_REVISION)));
            when(failureRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                    .thenReturn(Optional.empty());

            when(generateChapterNarrationAudioUseCase.execute(SEGMENT_ID, VOICE_ID))
                    .thenReturn(new GenerateChapterNarrationAudioResult(
                            UUID.randomUUID(), SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, SYNTHESIS_REVISION, NarrationAudioGenerationOutcome.STALE
                    ));

            PrepareReaderNarrationSegmentResult result = useCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID);

            assertThat(result.outcome()).isEqualTo(PrepareReaderNarrationSegmentOutcome.PREPARED_AND_PLAYABLE);
            assertThat(result.finalHealth()).isEqualTo(ChapterNarrationAudioHealthStatus.READY);
            assertThat(result.mediaAssetId()).isEqualTo(MEDIA_ASSET_2_ID);
            assertThat(result.isPlayableNow()).isTrue();
        }

        @Test
        @DisplayName("7. Generate returns GENERATED but voice revision changed before final read -> OUTDATED, playable + refreshRecommended")
        void generateReturnsGeneratedButRevisionChangedBeforeFinalRead() {
            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(createChapter(ChapterStatus.PUBLISHED)));
            // 1st read: voice revision 1; 2nd read: voice revision 2
            when(managedVoiceRepositoryPort.findById(VOICE_ID))
                    .thenReturn(Optional.of(createVoice(ManagedVoiceStatus.ACTIVE, 1L)))
                    .thenReturn(Optional.of(createVoice(ManagedVoiceStatus.ACTIVE, 2L)));
            when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(createSegment(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT)));

            when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(createAudio(MEDIA_ASSET_ID, 1L)));
            when(failureRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                    .thenReturn(Optional.empty());

            when(generateChapterNarrationAudioUseCase.execute(SEGMENT_ID, VOICE_ID))
                    .thenReturn(new GenerateChapterNarrationAudioResult(
                            UUID.randomUUID(), SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 1L, NarrationAudioGenerationOutcome.GENERATED
                    ));

            PrepareReaderNarrationSegmentResult result = useCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID);

            assertThat(result.outcome()).isEqualTo(PrepareReaderNarrationSegmentOutcome.PREPARED_AND_PLAYABLE);
            assertThat(result.finalHealth()).isEqualTo(ChapterNarrationAudioHealthStatus.OUTDATED);
            assertThat(result.mediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
            assertThat(result.refreshRecommended()).isTrue();
            assertThat(result.isPlayableNow()).isTrue();
        }

        @Test
        @DisplayName("8. Generation RuntimeException + fresh state still FAILED -> RETRY_REQUIRED, safe result, no raw exception text")
        void generationThrowsExceptionAndFreshStateRemainsFailed() {
            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(createChapter(ChapterStatus.PUBLISHED)));
            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createVoice(ManagedVoiceStatus.ACTIVE, SYNTHESIS_REVISION)));
            when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(createSegment(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT)));

            ChapterNarrationAudioFailure failure = createFailure();
            when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                    .thenReturn(Optional.empty());
            when(failureRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(failure));

            when(generateChapterNarrationAudioUseCase.execute(SEGMENT_ID, VOICE_ID))
                    .thenThrow(new RuntimeException("Raw provider SQL/credentials detail that must NOT leak"));

            PrepareReaderNarrationSegmentResult result = useCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID);

            assertThat(result.outcome()).isEqualTo(PrepareReaderNarrationSegmentOutcome.RETRY_REQUIRED);
            assertThat(result.finalHealth()).isEqualTo(ChapterNarrationAudioHealthStatus.FAILED);
            assertThat(result.mediaAssetId()).isNull();
            assertThat(result.preparationAttempted()).isTrue();
            assertThat(result.isPlayableNow()).isFalse();
            assertThat(result.blocksPlayback()).isTrue();
        }

        @Test
        @DisplayName("9. Generation RuntimeException + concurrent winner makes READY -> returns playable result")
        void generationThrowsExceptionButConcurrentWinnerMakesReady() {
            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(createChapter(ChapterStatus.PUBLISHED)));
            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createVoice(ManagedVoiceStatus.ACTIVE, SYNTHESIS_REVISION)));
            when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(createSegment(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT)));

            // Post-read discovers valid audio persisted by concurrent winner
            when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(createAudio(MEDIA_ASSET_ID, SYNTHESIS_REVISION)));
            when(failureRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                    .thenReturn(Optional.empty());

            when(generateChapterNarrationAudioUseCase.execute(SEGMENT_ID, VOICE_ID))
                    .thenThrow(new RuntimeException("Lock conflict / optimistic race"));

            PrepareReaderNarrationSegmentResult result = useCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID);

            assertThat(result.outcome()).isEqualTo(PrepareReaderNarrationSegmentOutcome.PREPARED_AND_PLAYABLE);
            assertThat(result.finalHealth()).isEqualTo(ChapterNarrationAudioHealthStatus.READY);
            assertThat(result.mediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
            assertThat(result.isPlayableNow()).isTrue();
            assertThat(result.blocksPlayback()).isFalse();
        }

        @Test
        @DisplayName("10. Requested segment becomes RETIRED during preparation -> UNAVAILABLE, no retry loop")
        void segmentBecomesRetiredDuringPreparationReturnsUnavailable() {
            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(createChapter(ChapterStatus.PUBLISHED)));
            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createVoice(ManagedVoiceStatus.ACTIVE, SYNTHESIS_REVISION)));
            // 1st read: CURRENT, 2nd read: RETIRED
            when(segmentRepositoryPort.findById(SEGMENT_ID))
                    .thenReturn(Optional.of(createSegment(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT)))
                    .thenReturn(Optional.of(createSegment(CHAPTER_ID, ChapterNarrationSegmentStatus.RETIRED)));

            when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                    .thenReturn(Optional.empty());
            when(failureRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                    .thenReturn(Optional.empty());

            when(generateChapterNarrationAudioUseCase.execute(SEGMENT_ID, VOICE_ID))
                    .thenReturn(new GenerateChapterNarrationAudioResult(
                            UUID.randomUUID(), SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, SYNTHESIS_REVISION, NarrationAudioGenerationOutcome.GENERATED
                    ));

            PrepareReaderNarrationSegmentResult result = useCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID);

            assertThat(result.outcome()).isEqualTo(PrepareReaderNarrationSegmentOutcome.UNAVAILABLE);
            assertThat(result.finalHealth()).isNull();
            assertThat(result.mediaAssetId()).isNull();
            assertThat(result.preparationAttempted()).isTrue();
            assertThat(result.isPlayableNow()).isFalse();
            assertThat(result.blocksPlayback()).isTrue();
        }

        @Test
        @DisplayName("11. Chapter becomes DRAFT (concurrent unpublish) during preparation -> UNAVAILABLE, no retry, no audio/failure post-read")
        void chapterBecomesDraftDuringPreparationReturnsUnavailable() {
            // 1st read: PUBLISHED, 2nd read: DRAFT
            when(chapterRepositoryPort.findById(CHAPTER_ID))
                    .thenReturn(Optional.of(createChapter(ChapterStatus.PUBLISHED)))
                    .thenReturn(Optional.of(createChapter(ChapterStatus.DRAFT)));
            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createVoice(ManagedVoiceStatus.ACTIVE, SYNTHESIS_REVISION)));
            when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(createSegment(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT)));

            when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                    .thenReturn(Optional.empty());
            when(failureRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                    .thenReturn(Optional.empty());

            when(generateChapterNarrationAudioUseCase.execute(SEGMENT_ID, VOICE_ID))
                    .thenReturn(new GenerateChapterNarrationAudioResult(
                            UUID.randomUUID(), SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, SYNTHESIS_REVISION, NarrationAudioGenerationOutcome.GENERATED
                    ));

            PrepareReaderNarrationSegmentResult result = useCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID);

            assertThat(result.outcome()).isEqualTo(PrepareReaderNarrationSegmentOutcome.UNAVAILABLE);
            assertThat(result.finalHealth()).isNull();
            assertThat(result.mediaAssetId()).isNull();
            assertThat(result.preparationAttempted()).isTrue();
            assertThat(result.refreshRecommended()).isFalse();
            assertThat(result.isPlayableNow()).isFalse();
            assertThat(result.blocksPlayback()).isTrue();

            verify(generateChapterNarrationAudioUseCase, times(1)).execute(SEGMENT_ID, VOICE_ID);
            // Audio/failure repository must be queried only once (during initial preflight), not during post-read after Chapter becomes unavailable
            verify(audioRepositoryPort, times(1)).findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID);
            verify(failureRepositoryPort, times(1)).findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID);
        }
    }
}
