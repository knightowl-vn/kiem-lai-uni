package com.universe.novel.application.narration;

import com.universe.novel.application.exceptions.ChapterNarrationSegmentInvalidStateException;
import com.universe.novel.application.exceptions.ChapterNarrationSegmentNotFoundException;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ManagedVoiceInvalidStateException;
import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.ports.ChapterNarrationAudioFailureRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationAudioRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationManifestRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationSegmentRepositoryPort;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.application.ports.ManagedVoiceRepositoryPort;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.ChapterStatus;
import com.universe.novel.domain.Slug;
import com.universe.novel.domain.narration.ChapterNarrationAudio;
import com.universe.novel.domain.narration.ChapterNarrationAudioFailure;
import com.universe.novel.domain.narration.ChapterNarrationManifest;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExecuteReaderNarrationContinuationUseCase Unit Tests (MS-04.9H.7C2C2)")
class ExecuteReaderNarrationContinuationUseCaseTest {

    @Mock
    private ChapterRepositoryPort chapterRepositoryPort;
    @Mock
    private ManagedVoiceRepositoryPort managedVoiceRepositoryPort;
    @Mock
    private ChapterNarrationManifestRepositoryPort manifestRepositoryPort;
    @Mock
    private ChapterNarrationSegmentRepositoryPort segmentRepositoryPort;
    @Mock
    private ChapterNarrationAudioRepositoryPort audioRepositoryPort;
    @Mock
    private ChapterNarrationAudioFailureRepositoryPort failureRepositoryPort;
    @Mock
    private GenerateChapterNarrationAudioUseCase generateChapterNarrationAudioUseCase;
    @Mock
    private RegenerateChapterNarrationAudioUseCase regenerateChapterNarrationAudioUseCase;

    private ExecuteReaderNarrationContinuationUseCase useCase;

    private static final UUID CHAPTER_ID = UUID.randomUUID();
    private static final UUID VOLUME_ID = UUID.randomUUID();
    private static final UUID VOICE_ID = UUID.randomUUID();
    private static final UUID REQ_SEGMENT_ID = UUID.randomUUID();
    private static final String MANIFEST_HASH = "a".repeat(64);
    private static final String CHANGED_MANIFEST_HASH = "b".repeat(64);
    private static final long SYNTHESIS_REVISION = 2L;

    @BeforeEach
    void setUp() {
        useCase = new ExecuteReaderNarrationContinuationUseCase(
                chapterRepositoryPort,
                managedVoiceRepositoryPort,
                manifestRepositoryPort,
                segmentRepositoryPort,
                audioRepositoryPort,
                failureRepositoryPort,
                generateChapterNarrationAudioUseCase,
                regenerateChapterNarrationAudioUseCase
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

    private ChapterNarrationManifest createManifest(long sourceContentVersion, String hash) {
        return ChapterNarrationManifest.create(CHAPTER_ID, sourceContentVersion, hash, Instant.now());
    }

    private ChapterNarrationSegment createSegment(UUID segmentId, UUID chapterId, int index, ChapterNarrationSegmentStatus status) {
        Instant now = Instant.now();
        String text = "Paragraph " + index;
        return ChapterNarrationSegment.rehydrate(
                segmentId,
                chapterId,
                index,
                text,
                text.length(),
                NarrationTextSegment.computeSha256(text),
                status,
                now,
                now
        );
    }

    private ChapterNarrationAudio createAudio(UUID segmentId, UUID mediaAssetId, long revision) {
        Instant now = Instant.now();
        return ChapterNarrationAudio.rehydrate(
                UUID.randomUUID(),
                segmentId,
                VOICE_ID,
                mediaAssetId,
                revision,
                0L,
                now,
                now
        );
    }

    private ChapterNarrationAudioFailure createFailure(UUID segmentId) {
        return ChapterNarrationAudioFailure.create(
                UUID.randomUUID(),
                segmentId,
                VOICE_ID,
                NarrationAudioOperation.INITIAL_GENERATION,
                NarrationAudioFailureStage.TTS_SYNTHESIS,
                SYNTHESIS_REVISION,
                "PROVIDER_ERROR",
                Instant.now()
        );
    }

    private void mockPreflightSuccess() {
        when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(createChapter(ChapterStatus.PUBLISHED)));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createVoice(ManagedVoiceStatus.ACTIVE, SYNTHESIS_REVISION)));
        when(manifestRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(Optional.of(createManifest(1L, MANIFEST_HASH)));
        when(segmentRepositoryPort.findById(REQ_SEGMENT_ID)).thenReturn(Optional.of(createSegment(REQ_SEGMENT_ID, CHAPTER_ID, 2, ChapterNarrationSegmentStatus.CURRENT)));
    }

    @Nested
    @DisplayName("Preflight & Validation Tests")
    class PreflightTests {

        @Test
        @DisplayName("Rejects null plan")
        void rejectsNullPlan() {
            assertThatThrownBy(() -> useCase.execute(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Rejects missing Chapter")
        void rejectsMissingChapter() {
            ReaderNarrationContinuationPlan plan = new ReaderNarrationContinuationPlan(
                    CHAPTER_ID, VOICE_ID, REQ_SEGMENT_ID, 2, List.of()
            );
            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> useCase.execute(plan))
                    .isInstanceOf(ChapterNotFoundException.class);
        }

        @Test
        @DisplayName("Rejects non-PUBLISHED Chapter")
        void rejectsNonPublishedChapter() {
            ReaderNarrationContinuationPlan plan = new ReaderNarrationContinuationPlan(
                    CHAPTER_ID, VOICE_ID, REQ_SEGMENT_ID, 2, List.of()
            );
            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(createChapter(ChapterStatus.DRAFT)));

            assertThatThrownBy(() -> useCase.execute(plan))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("Rejects missing ManagedVoice")
        void rejectsMissingVoice() {
            ReaderNarrationContinuationPlan plan = new ReaderNarrationContinuationPlan(
                    CHAPTER_ID, VOICE_ID, REQ_SEGMENT_ID, 2, List.of()
            );
            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(createChapter(ChapterStatus.PUBLISHED)));
            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> useCase.execute(plan))
                    .isInstanceOf(ManagedVoiceNotFoundException.class);
        }

        @Test
        @DisplayName("Rejects inactive ManagedVoice")
        void rejectsInactiveVoice() {
            ReaderNarrationContinuationPlan plan = new ReaderNarrationContinuationPlan(
                    CHAPTER_ID, VOICE_ID, REQ_SEGMENT_ID, 2, List.of()
            );
            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(createChapter(ChapterStatus.PUBLISHED)));
            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createVoice(ManagedVoiceStatus.DISABLED, SYNTHESIS_REVISION)));

            assertThatThrownBy(() -> useCase.execute(plan))
                    .isInstanceOf(ManagedVoiceInvalidStateException.class);
        }

        @Test
        @DisplayName("Rejects missing Manifest")
        void rejectsMissingManifest() {
            ReaderNarrationContinuationPlan plan = new ReaderNarrationContinuationPlan(
                    CHAPTER_ID, VOICE_ID, REQ_SEGMENT_ID, 2, List.of()
            );
            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(createChapter(ChapterStatus.PUBLISHED)));
            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createVoice(ManagedVoiceStatus.ACTIVE, SYNTHESIS_REVISION)));
            when(manifestRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> useCase.execute(plan))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("Rejects missing requested segment")
        void rejectsMissingRequestedSegment() {
            ReaderNarrationContinuationPlan plan = new ReaderNarrationContinuationPlan(
                    CHAPTER_ID, VOICE_ID, REQ_SEGMENT_ID, 2, List.of()
            );
            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(createChapter(ChapterStatus.PUBLISHED)));
            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createVoice(ManagedVoiceStatus.ACTIVE, SYNTHESIS_REVISION)));
            when(manifestRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(Optional.of(createManifest(1L, MANIFEST_HASH)));
            when(segmentRepositoryPort.findById(REQ_SEGMENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> useCase.execute(plan))
                    .isInstanceOf(ChapterNarrationSegmentNotFoundException.class);
        }

        @Test
        @DisplayName("Rejects requested segment belonging to different chapter")
        void rejectsRequestedSegmentDifferentChapter() {
            ReaderNarrationContinuationPlan plan = new ReaderNarrationContinuationPlan(
                    CHAPTER_ID, VOICE_ID, REQ_SEGMENT_ID, 2, List.of()
            );
            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(createChapter(ChapterStatus.PUBLISHED)));
            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createVoice(ManagedVoiceStatus.ACTIVE, SYNTHESIS_REVISION)));
            when(manifestRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(Optional.of(createManifest(1L, MANIFEST_HASH)));
            when(segmentRepositoryPort.findById(REQ_SEGMENT_ID)).thenReturn(Optional.of(createSegment(REQ_SEGMENT_ID, UUID.randomUUID(), 2, ChapterNarrationSegmentStatus.CURRENT)));

            assertThatThrownBy(() -> useCase.execute(plan))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Rejects non-CURRENT requested segment")
        void rejectsNonCurrentRequestedSegment() {
            ReaderNarrationContinuationPlan plan = new ReaderNarrationContinuationPlan(
                    CHAPTER_ID, VOICE_ID, REQ_SEGMENT_ID, 2, List.of()
            );
            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(createChapter(ChapterStatus.PUBLISHED)));
            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createVoice(ManagedVoiceStatus.ACTIVE, SYNTHESIS_REVISION)));
            when(manifestRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(Optional.of(createManifest(1L, MANIFEST_HASH)));
            when(segmentRepositoryPort.findById(REQ_SEGMENT_ID)).thenReturn(Optional.of(createSegment(REQ_SEGMENT_ID, CHAPTER_ID, 2, ChapterNarrationSegmentStatus.RETIRED)));

            assertThatThrownBy(() -> useCase.execute(plan))
                    .isInstanceOf(ChapterNarrationSegmentInvalidStateException.class);
        }

        @Test
        @DisplayName("Rejects malformed plan containing requestedSegmentId in workItems")
        void rejectsMalformedPlanContainingRequestedSegmentId() {
            ReaderNarrationContinuationPlan plan = new ReaderNarrationContinuationPlan(
                    CHAPTER_ID, VOICE_ID, REQ_SEGMENT_ID, 2,
                    List.of(new ReaderNarrationContinuationPlanItem(
                            REQ_SEGMENT_ID, 2, ChapterNarrationAudioHealthStatus.MISSING, ReaderNarrationContinuationAction.GENERATE
                    ))
            );
            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(createChapter(ChapterStatus.PUBLISHED)));
            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createVoice(ManagedVoiceStatus.ACTIVE, SYNTHESIS_REVISION)));
            when(manifestRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(Optional.of(createManifest(1L, MANIFEST_HASH)));
            when(segmentRepositoryPort.findById(REQ_SEGMENT_ID)).thenReturn(Optional.of(createSegment(REQ_SEGMENT_ID, CHAPTER_ID, 2, ChapterNarrationSegmentStatus.CURRENT)));

            assertThatThrownBy(() -> useCase.execute(plan))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requested segment");

            verifyNoInteractions(generateChapterNarrationAudioUseCase);
            verifyNoInteractions(regenerateChapterNarrationAudioUseCase);
        }
    }

    @Nested
    @DisplayName("Ordering & Primitive Dispatch Tests")
    class OrderingAndDispatchTests {

        @Test
        @DisplayName("1. Executes work items in exact plan order")
        void executesWorkInExactPlanOrder() {
            mockPreflightSuccess();

            UUID seg3 = UUID.randomUUID();
            UUID seg4 = UUID.randomUUID();
            UUID seg0 = UUID.randomUUID();

            ReaderNarrationContinuationPlan plan = new ReaderNarrationContinuationPlan(
                    CHAPTER_ID, VOICE_ID, REQ_SEGMENT_ID, 2,
                    List.of(
                            new ReaderNarrationContinuationPlanItem(seg3, 3, ChapterNarrationAudioHealthStatus.MISSING, ReaderNarrationContinuationAction.GENERATE),
                            new ReaderNarrationContinuationPlanItem(seg4, 4, ChapterNarrationAudioHealthStatus.OUTDATED, ReaderNarrationContinuationAction.REGENERATE),
                            new ReaderNarrationContinuationPlanItem(seg0, 0, ChapterNarrationAudioHealthStatus.MISSING, ReaderNarrationContinuationAction.GENERATE)
                    )
            );

            when(segmentRepositoryPort.findById(seg3)).thenReturn(Optional.of(createSegment(seg3, CHAPTER_ID, 3, ChapterNarrationSegmentStatus.CURRENT)));
            when(segmentRepositoryPort.findById(seg4)).thenReturn(Optional.of(createSegment(seg4, CHAPTER_ID, 4, ChapterNarrationSegmentStatus.CURRENT)));
            when(segmentRepositoryPort.findById(seg0)).thenReturn(Optional.of(createSegment(seg0, CHAPTER_ID, 0, ChapterNarrationSegmentStatus.CURRENT)));

            // Fresh read for seg3: missing -> generate; post-read -> ready
            when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(seg3, VOICE_ID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(createAudio(seg3, UUID.randomUUID(), SYNTHESIS_REVISION)));

            // Fresh read for seg4: outdated -> regenerate; post-read -> ready
            when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(seg4, VOICE_ID))
                    .thenReturn(Optional.of(createAudio(seg4, UUID.randomUUID(), 1L)))
                    .thenReturn(Optional.of(createAudio(seg4, UUID.randomUUID(), SYNTHESIS_REVISION)));

            // Fresh read for seg0: missing -> generate; post-read -> ready
            when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(seg0, VOICE_ID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(createAudio(seg0, UUID.randomUUID(), SYNTHESIS_REVISION)));

            ExecuteReaderNarrationContinuationResult result = useCase.execute(plan);

            assertThat(result.status()).isEqualTo(ReaderNarrationContinuationExecutionStatus.COMPLETED);
            assertThat(result.itemResults()).extracting(ReaderNarrationContinuationItemResult::segmentId)
                    .containsExactly(seg3, seg4, seg0);

            InOrder inOrder = inOrder(generateChapterNarrationAudioUseCase, regenerateChapterNarrationAudioUseCase);
            inOrder.verify(generateChapterNarrationAudioUseCase).execute(seg3, VOICE_ID);
            inOrder.verify(regenerateChapterNarrationAudioUseCase).execute(seg4, VOICE_ID);
            inOrder.verify(generateChapterNarrationAudioUseCase).execute(seg0, VOICE_ID);

            // Verify requested segment is NEVER passed to generation primitives
            verify(generateChapterNarrationAudioUseCase, never()).execute(REQ_SEGMENT_ID, VOICE_ID);
            verify(regenerateChapterNarrationAudioUseCase, never()).execute(REQ_SEGMENT_ID, VOICE_ID);
        }

        @Test
        @DisplayName("2. Planned GENERATE + fresh READY -> skip without calling primitive")
        void plannedGenerateFreshReadySkips() {
            mockPreflightSuccess();
            UUID seg3 = UUID.randomUUID();
            ReaderNarrationContinuationPlan plan = new ReaderNarrationContinuationPlan(
                    CHAPTER_ID, VOICE_ID, REQ_SEGMENT_ID, 2,
                    List.of(new ReaderNarrationContinuationPlanItem(seg3, 3, ChapterNarrationAudioHealthStatus.MISSING, ReaderNarrationContinuationAction.GENERATE))
            );

            when(segmentRepositoryPort.findById(seg3)).thenReturn(Optional.of(createSegment(seg3, CHAPTER_ID, 3, ChapterNarrationSegmentStatus.CURRENT)));
            when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(seg3, VOICE_ID))
                    .thenReturn(Optional.of(createAudio(seg3, UUID.randomUUID(), SYNTHESIS_REVISION)));

            ExecuteReaderNarrationContinuationResult result = useCase.execute(plan);

            assertThat(result.status()).isEqualTo(ReaderNarrationContinuationExecutionStatus.COMPLETED);
            assertThat(result.skippedCount()).isEqualTo(1);
            assertThat(result.attemptedCount()).isEqualTo(0);
            assertThat(result.itemResults().get(0).outcome()).isEqualTo(ReaderNarrationContinuationItemOutcome.SKIPPED_ALREADY_READY);
            assertThat(result.itemResults().get(0).executedAction()).isNull();

            verifyNoInteractions(generateChapterNarrationAudioUseCase);
            verifyNoInteractions(regenerateChapterNarrationAudioUseCase);
        }

        @Test
        @DisplayName("3. Planned REGENERATE + fresh READY -> skip without calling primitive")
        void plannedRegenerateFreshReadySkips() {
            mockPreflightSuccess();
            UUID seg3 = UUID.randomUUID();
            ReaderNarrationContinuationPlan plan = new ReaderNarrationContinuationPlan(
                    CHAPTER_ID, VOICE_ID, REQ_SEGMENT_ID, 2,
                    List.of(new ReaderNarrationContinuationPlanItem(seg3, 3, ChapterNarrationAudioHealthStatus.OUTDATED, ReaderNarrationContinuationAction.REGENERATE))
            );

            when(segmentRepositoryPort.findById(seg3)).thenReturn(Optional.of(createSegment(seg3, CHAPTER_ID, 3, ChapterNarrationSegmentStatus.CURRENT)));
            when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(seg3, VOICE_ID))
                    .thenReturn(Optional.of(createAudio(seg3, UUID.randomUUID(), SYNTHESIS_REVISION)));

            ExecuteReaderNarrationContinuationResult result = useCase.execute(plan);

            assertThat(result.status()).isEqualTo(ReaderNarrationContinuationExecutionStatus.COMPLETED);
            assertThat(result.skippedCount()).isEqualTo(1);
            assertThat(result.itemResults().get(0).outcome()).isEqualTo(ReaderNarrationContinuationItemOutcome.SKIPPED_ALREADY_READY);

            verifyNoInteractions(generateChapterNarrationAudioUseCase);
            verifyNoInteractions(regenerateChapterNarrationAudioUseCase);
        }

        @Test
        @DisplayName("4. Planned GENERATE + fresh OUTDATED -> remapped to REGENERATE")
        void plannedGenerateFreshOutdatedRemappedToRegenerate() {
            mockPreflightSuccess();
            UUID seg3 = UUID.randomUUID();
            ReaderNarrationContinuationPlan plan = new ReaderNarrationContinuationPlan(
                    CHAPTER_ID, VOICE_ID, REQ_SEGMENT_ID, 2,
                    List.of(new ReaderNarrationContinuationPlanItem(seg3, 3, ChapterNarrationAudioHealthStatus.MISSING, ReaderNarrationContinuationAction.GENERATE))
            );

            when(segmentRepositoryPort.findById(seg3)).thenReturn(Optional.of(createSegment(seg3, CHAPTER_ID, 3, ChapterNarrationSegmentStatus.CURRENT)));
            // Fresh: audio exists with old revision 1L (OUTDATED); Post: revision 2L (READY)
            when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(seg3, VOICE_ID))
                    .thenReturn(Optional.of(createAudio(seg3, UUID.randomUUID(), 1L)))
                    .thenReturn(Optional.of(createAudio(seg3, UUID.randomUUID(), SYNTHESIS_REVISION)));

            ExecuteReaderNarrationContinuationResult result = useCase.execute(plan);

            assertThat(result.itemResults().get(0).plannedAction()).isEqualTo(ReaderNarrationContinuationAction.GENERATE);
            assertThat(result.itemResults().get(0).freshHealthBeforeExecution()).isEqualTo(ChapterNarrationAudioHealthStatus.OUTDATED);
            assertThat(result.itemResults().get(0).executedAction()).isEqualTo(ReaderNarrationContinuationAction.REGENERATE);
            assertThat(result.itemResults().get(0).outcome()).isEqualTo(ReaderNarrationContinuationItemOutcome.COMPLETED_READY);

            verify(regenerateChapterNarrationAudioUseCase).execute(seg3, VOICE_ID);
            verify(generateChapterNarrationAudioUseCase, never()).execute(any(), any());
        }

        @Test
        @DisplayName("5. Planned REGENERATE + fresh MISSING -> remapped to GENERATE")
        void plannedRegenerateFreshMissingRemappedToGenerate() {
            mockPreflightSuccess();
            UUID seg3 = UUID.randomUUID();
            ReaderNarrationContinuationPlan plan = new ReaderNarrationContinuationPlan(
                    CHAPTER_ID, VOICE_ID, REQ_SEGMENT_ID, 2,
                    List.of(new ReaderNarrationContinuationPlanItem(seg3, 3, ChapterNarrationAudioHealthStatus.OUTDATED, ReaderNarrationContinuationAction.REGENERATE))
            );

            when(segmentRepositoryPort.findById(seg3)).thenReturn(Optional.of(createSegment(seg3, CHAPTER_ID, 3, ChapterNarrationSegmentStatus.CURRENT)));
            when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(seg3, VOICE_ID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(createAudio(seg3, UUID.randomUUID(), SYNTHESIS_REVISION)));

            ExecuteReaderNarrationContinuationResult result = useCase.execute(plan);

            assertThat(result.itemResults().get(0).plannedAction()).isEqualTo(ReaderNarrationContinuationAction.REGENERATE);
            assertThat(result.itemResults().get(0).freshHealthBeforeExecution()).isEqualTo(ChapterNarrationAudioHealthStatus.MISSING);
            assertThat(result.itemResults().get(0).executedAction()).isEqualTo(ReaderNarrationContinuationAction.GENERATE);
            assertThat(result.itemResults().get(0).outcome()).isEqualTo(ReaderNarrationContinuationItemOutcome.COMPLETED_READY);

            verify(generateChapterNarrationAudioUseCase).execute(seg3, VOICE_ID);
            verify(regenerateChapterNarrationAudioUseCase, never()).execute(any(), any());
        }
    }

    @Nested
    @DisplayName("Race & Failure Handling Tests")
    class RaceAndFailureTests {

        @Test
        @DisplayName("8. Primitive result STALE + fresh READY -> completed")
        void primitiveStaleFreshReadyCompleted() {
            mockPreflightSuccess();
            UUID seg3 = UUID.randomUUID();
            ReaderNarrationContinuationPlan plan = new ReaderNarrationContinuationPlan(
                    CHAPTER_ID, VOICE_ID, REQ_SEGMENT_ID, 2,
                    List.of(new ReaderNarrationContinuationPlanItem(seg3, 3, ChapterNarrationAudioHealthStatus.MISSING, ReaderNarrationContinuationAction.GENERATE))
            );

            when(segmentRepositoryPort.findById(seg3)).thenReturn(Optional.of(createSegment(seg3, CHAPTER_ID, 3, ChapterNarrationSegmentStatus.CURRENT)));
            when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(seg3, VOICE_ID))
                    .thenReturn(Optional.empty()) // fresh
                    .thenReturn(Optional.of(createAudio(seg3, UUID.randomUUID(), SYNTHESIS_REVISION))); // post

            when(generateChapterNarrationAudioUseCase.execute(seg3, VOICE_ID))
                    .thenReturn(new GenerateChapterNarrationAudioResult(
                            UUID.randomUUID(), seg3, VOICE_ID, UUID.randomUUID(), SYNTHESIS_REVISION, NarrationAudioGenerationOutcome.STALE
                    ));

            ExecuteReaderNarrationContinuationResult result = useCase.execute(plan);

            assertThat(result.itemResults().get(0).outcome()).isEqualTo(ReaderNarrationContinuationItemOutcome.COMPLETED_READY);
            assertThat(result.completedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("9. Primitive exception + concurrent winner READY -> completed")
        void primitiveExceptionConcurrentWinnerReadyCompleted() {
            mockPreflightSuccess();
            UUID seg3 = UUID.randomUUID();
            ReaderNarrationContinuationPlan plan = new ReaderNarrationContinuationPlan(
                    CHAPTER_ID, VOICE_ID, REQ_SEGMENT_ID, 2,
                    List.of(new ReaderNarrationContinuationPlanItem(seg3, 3, ChapterNarrationAudioHealthStatus.MISSING, ReaderNarrationContinuationAction.GENERATE))
            );

            when(segmentRepositoryPort.findById(seg3)).thenReturn(Optional.of(createSegment(seg3, CHAPTER_ID, 3, ChapterNarrationSegmentStatus.CURRENT)));
            when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(seg3, VOICE_ID))
                    .thenReturn(Optional.empty()) // fresh
                    .thenReturn(Optional.of(createAudio(seg3, UUID.randomUUID(), SYNTHESIS_REVISION))); // post

            when(generateChapterNarrationAudioUseCase.execute(seg3, VOICE_ID))
                    .thenThrow(new RuntimeException("Concurrent conflict / duplicate key"));

            ExecuteReaderNarrationContinuationResult result = useCase.execute(plan);

            assertThat(result.itemResults().get(0).outcome()).isEqualTo(ReaderNarrationContinuationItemOutcome.COMPLETED_READY);
            assertThat(result.completedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("10. Primitive exception + fresh FAILED -> item marked RETRY_REQUIRED, later item succeeds, plan status PARTIAL, isFullyCompleted false")
        void primitiveExceptionFreshFailedIsIsolatedAndLaterItemContinues() {
            mockPreflightSuccess();
            UUID seg3 = UUID.randomUUID();
            UUID seg4 = UUID.randomUUID();
            ReaderNarrationContinuationPlan plan = new ReaderNarrationContinuationPlan(
                    CHAPTER_ID, VOICE_ID, REQ_SEGMENT_ID, 2,
                    List.of(
                            new ReaderNarrationContinuationPlanItem(seg3, 3, ChapterNarrationAudioHealthStatus.MISSING, ReaderNarrationContinuationAction.GENERATE),
                            new ReaderNarrationContinuationPlanItem(seg4, 4, ChapterNarrationAudioHealthStatus.MISSING, ReaderNarrationContinuationAction.GENERATE)
                    )
            );

            when(segmentRepositoryPort.findById(seg3)).thenReturn(Optional.of(createSegment(seg3, CHAPTER_ID, 3, ChapterNarrationSegmentStatus.CURRENT)));
            when(segmentRepositoryPort.findById(seg4)).thenReturn(Optional.of(createSegment(seg4, CHAPTER_ID, 4, ChapterNarrationSegmentStatus.CURRENT)));

            // seg3 fails and records failure
            when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(seg3, VOICE_ID)).thenReturn(Optional.empty());
            when(failureRepositoryPort.findBySegmentIdAndManagedVoiceId(seg3, VOICE_ID))
                    .thenReturn(Optional.empty()) // pre
                    .thenReturn(Optional.of(createFailure(seg3))); // post
            when(generateChapterNarrationAudioUseCase.execute(seg3, VOICE_ID))
                    .thenThrow(new RuntimeException("TTS provider timeout"));

            // seg4 succeeds
            when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(seg4, VOICE_ID))
                    .thenReturn(Optional.empty()) // pre
                    .thenReturn(Optional.of(createAudio(seg4, UUID.randomUUID(), SYNTHESIS_REVISION))); // post

            ExecuteReaderNarrationContinuationResult result = useCase.execute(plan);

            assertThat(result.status()).isEqualTo(ReaderNarrationContinuationExecutionStatus.PARTIAL);
            assertThat(result.itemResults()).hasSize(2);
            assertThat(result.itemResults().get(0).outcome()).isEqualTo(ReaderNarrationContinuationItemOutcome.RETRY_REQUIRED);
            assertThat(result.itemResults().get(1).outcome()).isEqualTo(ReaderNarrationContinuationItemOutcome.COMPLETED_READY);

            assertThat(result.failedCount()).isEqualTo(1);
            assertThat(result.completedCount()).isEqualTo(1);
            assertThat(result.attemptedCount()).isEqualTo(2);
            assertThat(result.remainingCount()).isEqualTo(0);
            assertThat(result.isFullyCompleted()).isFalse();

            verify(generateChapterNarrationAudioUseCase).execute(seg3, VOICE_ID);
            verify(generateChapterNarrationAudioUseCase).execute(seg4, VOICE_ID);
        }

        @Test
        @DisplayName("10b. Item post-read MISSING (FAILED outcome) + later item succeeds -> plan status PARTIAL, isFullyCompleted false")
        void itemMissingFailedOutcomeAndLaterSucceedsResultsInPartial() {
            mockPreflightSuccess();
            UUID seg3 = UUID.randomUUID();
            UUID seg4 = UUID.randomUUID();
            ReaderNarrationContinuationPlan plan = new ReaderNarrationContinuationPlan(
                    CHAPTER_ID, VOICE_ID, REQ_SEGMENT_ID, 2,
                    List.of(
                            new ReaderNarrationContinuationPlanItem(seg3, 3, ChapterNarrationAudioHealthStatus.MISSING, ReaderNarrationContinuationAction.GENERATE),
                            new ReaderNarrationContinuationPlanItem(seg4, 4, ChapterNarrationAudioHealthStatus.MISSING, ReaderNarrationContinuationAction.GENERATE)
                    )
            );

            when(segmentRepositoryPort.findById(seg3)).thenReturn(Optional.of(createSegment(seg3, CHAPTER_ID, 3, ChapterNarrationSegmentStatus.CURRENT)));
            when(segmentRepositoryPort.findById(seg4)).thenReturn(Optional.of(createSegment(seg4, CHAPTER_ID, 4, ChapterNarrationSegmentStatus.CURRENT)));

            // seg3: generate runs, post-read audio is still missing, no failure record -> MISSING -> FAILED outcome
            when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(seg3, VOICE_ID)).thenReturn(Optional.empty());
            when(failureRepositoryPort.findBySegmentIdAndManagedVoiceId(seg3, VOICE_ID)).thenReturn(Optional.empty());

            // seg4: succeeds
            when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(seg4, VOICE_ID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(createAudio(seg4, UUID.randomUUID(), SYNTHESIS_REVISION)));

            ExecuteReaderNarrationContinuationResult result = useCase.execute(plan);

            assertThat(result.status()).isEqualTo(ReaderNarrationContinuationExecutionStatus.PARTIAL);
            assertThat(result.itemResults().get(0).outcome()).isEqualTo(ReaderNarrationContinuationItemOutcome.FAILED);
            assertThat(result.itemResults().get(1).outcome()).isEqualTo(ReaderNarrationContinuationItemOutcome.COMPLETED_READY);
            assertThat(result.failedCount()).isEqualTo(1);
            assertThat(result.isFullyCompleted()).isFalse();
        }
    }

    @Nested
    @DisplayName("Manifest Drift & Lifecycle Abort Tests")
    class ManifestAndLifecycleAbortTests {

        @Test
        @DisplayName("11. Manifest changes before an item -> aborts immediately, zero further primitive calls")
        void manifestChangesBeforeItemAbortsImmediately() {
            mockPreflightSuccess();
            UUID seg3 = UUID.randomUUID();
            UUID seg4 = UUID.randomUUID();
            ReaderNarrationContinuationPlan plan = new ReaderNarrationContinuationPlan(
                    CHAPTER_ID, VOICE_ID, REQ_SEGMENT_ID, 2,
                    List.of(
                            new ReaderNarrationContinuationPlanItem(seg3, 3, ChapterNarrationAudioHealthStatus.MISSING, ReaderNarrationContinuationAction.GENERATE),
                            new ReaderNarrationContinuationPlanItem(seg4, 4, ChapterNarrationAudioHealthStatus.MISSING, ReaderNarrationContinuationAction.GENERATE)
                    )
            );

            when(segmentRepositoryPort.findById(seg3)).thenReturn(Optional.of(createSegment(seg3, CHAPTER_ID, 3, ChapterNarrationSegmentStatus.CURRENT)));
            when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(seg3, VOICE_ID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(createAudio(seg3, UUID.randomUUID(), SYNTHESIS_REVISION)));

            // 1st manifest call: preflight (HASH); 2nd call: seg3 pre-guard (HASH); 3rd call: seg3 post-guard (HASH); 4th call: seg4 pre-guard (CHANGED_HASH)
            when(manifestRepositoryPort.findByChapterId(CHAPTER_ID))
                    .thenReturn(Optional.of(createManifest(1L, MANIFEST_HASH)))
                    .thenReturn(Optional.of(createManifest(1L, MANIFEST_HASH)))
                    .thenReturn(Optional.of(createManifest(1L, MANIFEST_HASH)))
                    .thenReturn(Optional.of(createManifest(1L, CHANGED_MANIFEST_HASH)));

            ExecuteReaderNarrationContinuationResult result = useCase.execute(plan);

            assertThat(result.status()).isEqualTo(ReaderNarrationContinuationExecutionStatus.MANIFEST_CHANGED);
            assertThat(result.itemResults()).hasSize(1);
            assertThat(result.remainingCount()).isEqualTo(1);

            verify(generateChapterNarrationAudioUseCase, times(1)).execute(seg3, VOICE_ID);
            verify(generateChapterNarrationAudioUseCase, never()).execute(seg4, VOICE_ID);
        }

        @Test
        @DisplayName("12. Manifest changes after primitive -> aborts remaining")
        void manifestChangesAfterPrimitiveAbortsRemaining() {
            mockPreflightSuccess();
            UUID seg3 = UUID.randomUUID();
            UUID seg4 = UUID.randomUUID();
            ReaderNarrationContinuationPlan plan = new ReaderNarrationContinuationPlan(
                    CHAPTER_ID, VOICE_ID, REQ_SEGMENT_ID, 2,
                    List.of(
                            new ReaderNarrationContinuationPlanItem(seg3, 3, ChapterNarrationAudioHealthStatus.MISSING, ReaderNarrationContinuationAction.GENERATE),
                            new ReaderNarrationContinuationPlanItem(seg4, 4, ChapterNarrationAudioHealthStatus.MISSING, ReaderNarrationContinuationAction.GENERATE)
                    )
            );

            when(segmentRepositoryPort.findById(seg3)).thenReturn(Optional.of(createSegment(seg3, CHAPTER_ID, 3, ChapterNarrationSegmentStatus.CURRENT)));
            when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(seg3, VOICE_ID)).thenReturn(Optional.empty());

            // 1st: preflight (HASH); 2nd: seg3 pre-guard (HASH); 3rd: seg3 post-guard (CHANGED_HASH)
            when(manifestRepositoryPort.findByChapterId(CHAPTER_ID))
                    .thenReturn(Optional.of(createManifest(1L, MANIFEST_HASH)))
                    .thenReturn(Optional.of(createManifest(1L, MANIFEST_HASH)))
                    .thenReturn(Optional.of(createManifest(1L, CHANGED_MANIFEST_HASH)));

            ExecuteReaderNarrationContinuationResult result = useCase.execute(plan);

            assertThat(result.status()).isEqualTo(ReaderNarrationContinuationExecutionStatus.MANIFEST_CHANGED);
            assertThat(result.itemResults()).hasSize(1);
            assertThat(result.itemResults().get(0).outcome()).isEqualTo(ReaderNarrationContinuationItemOutcome.STALE_CONTEXT);
            assertThat(result.remainingCount()).isEqualTo(1);

            verify(generateChapterNarrationAudioUseCase, never()).execute(seg4, VOICE_ID);
        }

        @Test
        @DisplayName("13. Chapter becomes non-PUBLISHED -> aborts remaining")
        void chapterBecomesNonPublishedAbortsRemaining() {
            mockPreflightSuccess();
            UUID seg3 = UUID.randomUUID();
            ReaderNarrationContinuationPlan plan = new ReaderNarrationContinuationPlan(
                    CHAPTER_ID, VOICE_ID, REQ_SEGMENT_ID, 2,
                    List.of(new ReaderNarrationContinuationPlanItem(seg3, 3, ChapterNarrationAudioHealthStatus.MISSING, ReaderNarrationContinuationAction.GENERATE))
            );

            // 1st call: preflight (PUBLISHED); 2nd call: seg3 pre-guard (DRAFT)
            when(chapterRepositoryPort.findById(CHAPTER_ID))
                    .thenReturn(Optional.of(createChapter(ChapterStatus.PUBLISHED)))
                    .thenReturn(Optional.of(createChapter(ChapterStatus.DRAFT)));

            ExecuteReaderNarrationContinuationResult result = useCase.execute(plan);

            assertThat(result.status()).isEqualTo(ReaderNarrationContinuationExecutionStatus.CONTEXT_UNAVAILABLE);
            assertThat(result.itemResults()).isEmpty();
            assertThat(result.remainingCount()).isEqualTo(1);

            verifyNoInteractions(generateChapterNarrationAudioUseCase);
        }

        @Test
        @DisplayName("14. Voice becomes INACTIVE -> aborts remaining")
        void voiceBecomesInactiveAbortsRemaining() {
            mockPreflightSuccess();
            UUID seg3 = UUID.randomUUID();
            ReaderNarrationContinuationPlan plan = new ReaderNarrationContinuationPlan(
                    CHAPTER_ID, VOICE_ID, REQ_SEGMENT_ID, 2,
                    List.of(new ReaderNarrationContinuationPlanItem(seg3, 3, ChapterNarrationAudioHealthStatus.MISSING, ReaderNarrationContinuationAction.GENERATE))
            );

            // 1st call: preflight (ACTIVE); 2nd call: seg3 pre-guard (INACTIVE)
            when(managedVoiceRepositoryPort.findById(VOICE_ID))
                    .thenReturn(Optional.of(createVoice(ManagedVoiceStatus.ACTIVE, SYNTHESIS_REVISION)))
                    .thenReturn(Optional.of(createVoice(ManagedVoiceStatus.DISABLED, SYNTHESIS_REVISION)));

            ExecuteReaderNarrationContinuationResult result = useCase.execute(plan);

            assertThat(result.status()).isEqualTo(ReaderNarrationContinuationExecutionStatus.CONTEXT_UNAVAILABLE);
            assertThat(result.itemResults()).isEmpty();

            verifyNoInteractions(generateChapterNarrationAudioUseCase);
        }

        @Test
        @DisplayName("15. Segment becomes RETIRED/missing -> STALE_CONTEXT recorded and aborts remaining")
        void segmentBecomesRetiredAbortsRemaining() {
            mockPreflightSuccess();
            UUID seg3 = UUID.randomUUID();
            UUID seg4 = UUID.randomUUID();
            ReaderNarrationContinuationPlan plan = new ReaderNarrationContinuationPlan(
                    CHAPTER_ID, VOICE_ID, REQ_SEGMENT_ID, 2,
                    List.of(
                            new ReaderNarrationContinuationPlanItem(seg3, 3, ChapterNarrationAudioHealthStatus.MISSING, ReaderNarrationContinuationAction.GENERATE),
                            new ReaderNarrationContinuationPlanItem(seg4, 4, ChapterNarrationAudioHealthStatus.MISSING, ReaderNarrationContinuationAction.GENERATE)
                    )
            );

            when(segmentRepositoryPort.findById(seg3)).thenReturn(Optional.of(createSegment(seg3, CHAPTER_ID, 3, ChapterNarrationSegmentStatus.RETIRED)));

            ExecuteReaderNarrationContinuationResult result = useCase.execute(plan);

            assertThat(result.status()).isEqualTo(ReaderNarrationContinuationExecutionStatus.CONTEXT_UNAVAILABLE);
            assertThat(result.itemResults()).hasSize(1);
            assertThat(result.itemResults().get(0).outcome()).isEqualTo(ReaderNarrationContinuationItemOutcome.STALE_CONTEXT);
            assertThat(result.remainingCount()).isEqualTo(1);

            verifyNoInteractions(generateChapterNarrationAudioUseCase);
        }

        @Test
        @DisplayName("16. Manifest changed remains MANIFEST_CHANGED even if earlier item failed")
        void manifestChangedRemainsManifestChangedEvenIfEarlierItemFailed() {
            mockPreflightSuccess();
            UUID seg3 = UUID.randomUUID();
            UUID seg4 = UUID.randomUUID();
            ReaderNarrationContinuationPlan plan = new ReaderNarrationContinuationPlan(
                    CHAPTER_ID, VOICE_ID, REQ_SEGMENT_ID, 2,
                    List.of(
                            new ReaderNarrationContinuationPlanItem(seg3, 3, ChapterNarrationAudioHealthStatus.MISSING, ReaderNarrationContinuationAction.GENERATE),
                            new ReaderNarrationContinuationPlanItem(seg4, 4, ChapterNarrationAudioHealthStatus.MISSING, ReaderNarrationContinuationAction.GENERATE)
                    )
            );

            when(segmentRepositoryPort.findById(seg3)).thenReturn(Optional.of(createSegment(seg3, CHAPTER_ID, 3, ChapterNarrationSegmentStatus.CURRENT)));
            // seg3 fails
            when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(seg3, VOICE_ID)).thenReturn(Optional.empty());
            when(failureRepositoryPort.findBySegmentIdAndManagedVoiceId(seg3, VOICE_ID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(createFailure(seg3)));
            when(generateChapterNarrationAudioUseCase.execute(seg3, VOICE_ID))
                    .thenThrow(new RuntimeException("TTS error"));

            // Manifest changes before seg4
            when(manifestRepositoryPort.findByChapterId(CHAPTER_ID))
                    .thenReturn(Optional.of(createManifest(1L, MANIFEST_HASH))) // preflight
                    .thenReturn(Optional.of(createManifest(1L, MANIFEST_HASH))) // seg3 pre
                    .thenReturn(Optional.of(createManifest(1L, MANIFEST_HASH))) // seg3 post
                    .thenReturn(Optional.of(createManifest(1L, CHANGED_MANIFEST_HASH))); // seg4 pre

            ExecuteReaderNarrationContinuationResult result = useCase.execute(plan);

            assertThat(result.status()).isEqualTo(ReaderNarrationContinuationExecutionStatus.MANIFEST_CHANGED);
            assertThat(result.failedCount()).isEqualTo(1);
            assertThat(result.remainingCount()).isEqualTo(1);
            assertThat(result.isFullyCompleted()).isFalse();
        }

        @Test
        @DisplayName("17. Context unavailable remains CONTEXT_UNAVAILABLE even if earlier item failed")
        void contextUnavailableRemainsContextUnavailableEvenIfEarlierItemFailed() {
            mockPreflightSuccess();
            UUID seg3 = UUID.randomUUID();
            UUID seg4 = UUID.randomUUID();
            ReaderNarrationContinuationPlan plan = new ReaderNarrationContinuationPlan(
                    CHAPTER_ID, VOICE_ID, REQ_SEGMENT_ID, 2,
                    List.of(
                            new ReaderNarrationContinuationPlanItem(seg3, 3, ChapterNarrationAudioHealthStatus.MISSING, ReaderNarrationContinuationAction.GENERATE),
                            new ReaderNarrationContinuationPlanItem(seg4, 4, ChapterNarrationAudioHealthStatus.MISSING, ReaderNarrationContinuationAction.GENERATE)
                    )
            );

            when(segmentRepositoryPort.findById(seg3)).thenReturn(Optional.of(createSegment(seg3, CHAPTER_ID, 3, ChapterNarrationSegmentStatus.CURRENT)));
            // seg3 fails
            when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(seg3, VOICE_ID)).thenReturn(Optional.empty());
            when(failureRepositoryPort.findBySegmentIdAndManagedVoiceId(seg3, VOICE_ID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(createFailure(seg3)));
            when(generateChapterNarrationAudioUseCase.execute(seg3, VOICE_ID))
                    .thenThrow(new RuntimeException("TTS error"));

            // Chapter unpublished before seg4
            when(chapterRepositoryPort.findById(CHAPTER_ID))
                    .thenReturn(Optional.of(createChapter(ChapterStatus.PUBLISHED))) // preflight
                    .thenReturn(Optional.of(createChapter(ChapterStatus.PUBLISHED))) // seg3 pre
                    .thenReturn(Optional.of(createChapter(ChapterStatus.PUBLISHED))) // seg3 post
                    .thenReturn(Optional.of(createChapter(ChapterStatus.DRAFT))); // seg4 pre

            ExecuteReaderNarrationContinuationResult result = useCase.execute(plan);

            assertThat(result.status()).isEqualTo(ReaderNarrationContinuationExecutionStatus.CONTEXT_UNAVAILABLE);
            assertThat(result.failedCount()).isEqualTo(1);
            assertThat(result.remainingCount()).isEqualTo(1);
            assertThat(result.isFullyCompleted()).isFalse();
        }
    }
}
