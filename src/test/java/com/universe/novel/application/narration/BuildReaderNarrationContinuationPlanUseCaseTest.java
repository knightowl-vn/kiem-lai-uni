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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BuildReaderNarrationContinuationPlanUseCase Unit Tests (MS-04.9H.7C2C3A)")
class BuildReaderNarrationContinuationPlanUseCaseTest {

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

    @Spy
    private ReaderNarrationContinuationPlanner continuationPlanner = new ReaderNarrationContinuationPlanner();

    private BuildReaderNarrationContinuationPlanUseCase useCase;

    private static final UUID CHAPTER_ID = UUID.randomUUID();
    private static final UUID VOLUME_ID = UUID.randomUUID();
    private static final UUID VOICE_ID = UUID.randomUUID();
    private static final UUID REQ_SEGMENT_ID = UUID.randomUUID();
    private static final long SYNTHESIS_REVISION = 2L;

    @BeforeEach
    void setUp() {
        useCase = new BuildReaderNarrationContinuationPlanUseCase(
                chapterRepositoryPort,
                managedVoiceRepositoryPort,
                segmentRepositoryPort,
                audioRepositoryPort,
                failureRepositoryPort,
                continuationPlanner
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

    @Nested
    @DisplayName("Preflight & Validation Tests")
    class PreflightTests {

        @Test
        @DisplayName("Rejects null command")
        void rejectsNullCommand() {
            assertThatThrownBy(() -> useCase.execute((BuildReaderNarrationContinuationPlanCommand) null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> useCase.execute(null, REQ_SEGMENT_ID, VOICE_ID))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, null, VOICE_ID))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, REQ_SEGMENT_ID, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("12. Rejects non-PUBLISHED Chapter")
        void rejectsNonPublishedChapter() {
            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(createChapter(ChapterStatus.DRAFT)));

            assertThatThrownBy(() -> useCase.execute(new BuildReaderNarrationContinuationPlanCommand(CHAPTER_ID, REQ_SEGMENT_ID, VOICE_ID)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("PUBLISHED");
        }

        @Test
        @DisplayName("13. Rejects inactive ManagedVoice")
        void rejectsInactiveVoice() {
            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(createChapter(ChapterStatus.PUBLISHED)));
            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createVoice(ManagedVoiceStatus.DISABLED, SYNTHESIS_REVISION)));

            assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, REQ_SEGMENT_ID, VOICE_ID))
                    .isInstanceOf(ManagedVoiceInvalidStateException.class);
        }

        @Test
        @DisplayName("14. Rejects missing requested segment")
        void rejectsMissingRequestedSegment() {
            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(createChapter(ChapterStatus.PUBLISHED)));
            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createVoice(ManagedVoiceStatus.ACTIVE, SYNTHESIS_REVISION)));
            when(segmentRepositoryPort.findById(REQ_SEGMENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, REQ_SEGMENT_ID, VOICE_ID))
                    .isInstanceOf(ChapterNarrationSegmentNotFoundException.class);
        }

        @Test
        @DisplayName("15. Rejects requested segment in RETIRED status")
        void rejectsRetiredRequestedSegment() {
            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(createChapter(ChapterStatus.PUBLISHED)));
            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createVoice(ManagedVoiceStatus.ACTIVE, SYNTHESIS_REVISION)));
            when(segmentRepositoryPort.findById(REQ_SEGMENT_ID)).thenReturn(Optional.of(createSegment(REQ_SEGMENT_ID, CHAPTER_ID, 2, ChapterNarrationSegmentStatus.RETIRED)));

            assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, REQ_SEGMENT_ID, VOICE_ID))
                    .isInstanceOf(ChapterNarrationSegmentInvalidStateException.class);
        }

        @Test
        @DisplayName("16. Rejects requested segment belonging to another chapter")
        void rejectsRequestedSegmentBelongingToAnotherChapter() {
            UUID otherChapterId = UUID.randomUUID();
            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(createChapter(ChapterStatus.PUBLISHED)));
            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createVoice(ManagedVoiceStatus.ACTIVE, SYNTHESIS_REVISION)));
            when(segmentRepositoryPort.findById(REQ_SEGMENT_ID)).thenReturn(Optional.of(createSegment(REQ_SEGMENT_ID, otherChapterId, 2, ChapterNarrationSegmentStatus.CURRENT)));

            assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, REQ_SEGMENT_ID, VOICE_ID))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Plan Construction & Batch Health Resolution Tests")
    class PlanConstructionTests {

        @Test
        @DisplayName("1-4, 8-11. Builds plan from CURRENT segments, excludes RETIRED, batches audio/failure (Zero N+1)")
        void buildsPlanBatchLoadingAudioAndFailures() {
            UUID seg0 = UUID.randomUUID();
            UUID seg1 = UUID.randomUUID();
            UUID seg2 = REQ_SEGMENT_ID;
            UUID seg3 = UUID.randomUUID();
            UUID seg4 = UUID.randomUUID();

            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(createChapter(ChapterStatus.PUBLISHED)));
            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createVoice(ManagedVoiceStatus.ACTIVE, SYNTHESIS_REVISION)));
            when(segmentRepositoryPort.findById(REQ_SEGMENT_ID)).thenReturn(Optional.of(createSegment(seg2, CHAPTER_ID, 2, ChapterNarrationSegmentStatus.CURRENT)));

            // 5 CURRENT segments in chapter
            List<ChapterNarrationSegment> currentSegments = List.of(
                    createSegment(seg0, CHAPTER_ID, 0, ChapterNarrationSegmentStatus.CURRENT),
                    createSegment(seg1, CHAPTER_ID, 1, ChapterNarrationSegmentStatus.CURRENT),
                    createSegment(seg2, CHAPTER_ID, 2, ChapterNarrationSegmentStatus.CURRENT),
                    createSegment(seg3, CHAPTER_ID, 3, ChapterNarrationSegmentStatus.CURRENT),
                    createSegment(seg4, CHAPTER_ID, 4, ChapterNarrationSegmentStatus.CURRENT)
            );
            when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                    .thenReturn(currentSegments);

            List<UUID> currentIds = List.of(seg0, seg1, seg2, seg3, seg4);

            // Audio batch: seg0 READY (rev 2), seg1 OUTDATED (rev 1), seg2 READY (rev 2), seg3 MISSING, seg4 FAILED
            when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(eq(currentIds), eq(VOICE_ID)))
                    .thenReturn(List.of(
                            createAudio(seg0, UUID.randomUUID(), SYNTHESIS_REVISION),
                            createAudio(seg1, UUID.randomUUID(), 1L), // outdated
                            createAudio(seg2, UUID.randomUUID(), SYNTHESIS_REVISION)
                    ));

            // Failure batch: seg4 has failure record
            when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(eq(currentIds), eq(VOICE_ID)))
                    .thenReturn(List.of(createFailure(seg4)));

            ReaderNarrationContinuationPlan plan = useCase.execute(new BuildReaderNarrationContinuationPlanCommand(
                    CHAPTER_ID, REQ_SEGMENT_ID, VOICE_ID
            ));

            // Verification:
            // Requested segment is index 2.
            // Future work:
            //   seg3 (index 3) MISSING -> Bucket 1 (GENERATE)
            //   seg4 (index 4) FAILED  -> Bucket 1 (GENERATE)
            // Past work:
            //   seg1 (index 1) OUTDATED -> Bucket 4 (REGENERATE)
            // seg0 is READY -> omitted from work items.
            // seg2 is requested -> excluded from work items.

            assertThat(plan.chapterId()).isEqualTo(CHAPTER_ID);
            assertThat(plan.managedVoiceId()).isEqualTo(VOICE_ID);
            assertThat(plan.requestedSegmentId()).isEqualTo(REQ_SEGMENT_ID);
            assertThat(plan.requestedSegmentIndex()).isEqualTo(2);

            assertThat(plan.workItems()).extracting(ReaderNarrationContinuationPlanItem::segmentIndex)
                    .containsExactly(3, 4, 1);

            assertThat(plan.workItems()).extracting(ReaderNarrationContinuationPlanItem::action)
                    .containsExactly(
                            ReaderNarrationContinuationAction.GENERATE,
                            ReaderNarrationContinuationAction.GENERATE,
                            ReaderNarrationContinuationAction.REGENERATE
                    );

            assertThat(plan.totalWorkCount()).isEqualTo(3);
            assertThat(plan.generateCount()).isEqualTo(2);
            assertThat(plan.regenerateCount()).isEqualTo(1);
            assertThat(plan.futureWorkCount()).isEqualTo(2);
            assertThat(plan.pastWorkCount()).isEqualTo(1);

            // Confirm batch queries occurred exactly once (Zero N+1)
            verify(audioRepositoryPort, times(1)).findBySegmentIdInAndManagedVoiceId(eq(currentIds), eq(VOICE_ID));
            verify(failureRepositoryPort, times(1)).findBySegmentIdInAndManagedVoiceId(eq(currentIds), eq(VOICE_ID));
            verify(audioRepositoryPort, never()).findBySegmentIdAndManagedVoiceId(any(), any());
            verify(failureRepositoryPort, never()).findBySegmentIdAndManagedVoiceId(any(), any());
        }

        @Test
        @DisplayName("3, 17. Requested segment snapshot passed to C1 planner and C1 priority ordering preserved")
        @SuppressWarnings("unchecked")
        void requestedSegmentPassedToPlannerAndPriorityPreserved() {
            UUID seg0 = UUID.randomUUID();
            UUID seg1 = REQ_SEGMENT_ID;
            UUID seg2 = UUID.randomUUID();

            when(chapterRepositoryPort.findById(CHAPTER_ID)).thenReturn(Optional.of(createChapter(ChapterStatus.PUBLISHED)));
            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createVoice(ManagedVoiceStatus.ACTIVE, SYNTHESIS_REVISION)));
            when(segmentRepositoryPort.findById(REQ_SEGMENT_ID)).thenReturn(Optional.of(createSegment(seg1, CHAPTER_ID, 1, ChapterNarrationSegmentStatus.CURRENT)));

            when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                    .thenReturn(List.of(
                            createSegment(seg0, CHAPTER_ID, 0, ChapterNarrationSegmentStatus.CURRENT),
                            createSegment(seg1, CHAPTER_ID, 1, ChapterNarrationSegmentStatus.CURRENT),
                            createSegment(seg2, CHAPTER_ID, 2, ChapterNarrationSegmentStatus.CURRENT)
                    ));

            when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(any(), eq(VOICE_ID))).thenReturn(List.of());
            when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(any(), eq(VOICE_ID))).thenReturn(List.of());

            ReaderNarrationContinuationPlan plan = useCase.execute(CHAPTER_ID, REQ_SEGMENT_ID, VOICE_ID);

            ArgumentCaptor<Collection<ReaderNarrationContinuationSegmentSnapshot>> captor = ArgumentCaptor.forClass(Collection.class);
            verify(continuationPlanner).plan(eq(CHAPTER_ID), eq(VOICE_ID), eq(REQ_SEGMENT_ID), captor.capture());

            Collection<ReaderNarrationContinuationSegmentSnapshot> suppliedSnapshots = captor.getValue();
            assertThat(suppliedSnapshots).extracting(ReaderNarrationContinuationSegmentSnapshot::segmentId)
                    .containsExactlyInAnyOrder(seg0, seg1, seg2);

            // In the returned plan, requested segment (seg1) is excluded by C1 planner
            assertThat(plan.workItems()).extracting(ReaderNarrationContinuationPlanItem::segmentId)
                    .containsExactly(seg2, seg0); // Future (seg2 at index 2) before past (seg0 at index 0)
        }
    }
}
