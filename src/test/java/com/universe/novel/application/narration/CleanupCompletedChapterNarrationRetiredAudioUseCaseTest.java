package com.universe.novel.application.narration;

import com.universe.novel.application.ports.ChapterNarrationAudioFailureRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationAudioRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationManifestRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationSegmentRepositoryPort;
import com.universe.novel.application.ports.ManagedVoiceRepositoryPort;
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
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CleanupCompletedChapterNarrationRetiredAudioUseCase Unit Tests")
class CleanupCompletedChapterNarrationRetiredAudioUseCaseTest {

    private static final UUID CHAPTER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID VOICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final long SYNTHESIS_REVISION = 1L;
    private static final String VALID_MANIFEST_HASH = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2";
    private static final String OTHER_MANIFEST_HASH = "b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3";

    @Mock
    private ChapterNarrationManifestRepositoryPort manifestRepositoryPort;

    @Mock
    private ManagedVoiceRepositoryPort managedVoiceRepositoryPort;

    @Mock
    private ChapterNarrationSegmentRepositoryPort segmentRepositoryPort;

    @Mock
    private ChapterNarrationAudioRepositoryPort audioRepositoryPort;

    @Mock
    private ChapterNarrationAudioFailureRepositoryPort failureRepositoryPort;

    @Mock
    private HandoffRetiredNarrationAudioCleanupUseCase handoffUseCase;

    private CleanupCompletedChapterNarrationRetiredAudioUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new CleanupCompletedChapterNarrationRetiredAudioUseCase(
                manifestRepositoryPort,
                managedVoiceRepositoryPort,
                segmentRepositoryPort,
                audioRepositoryPort,
                failureRepositoryPort,
                handoffUseCase
        );
    }

    private ChapterNarrationManifest createManifest(long sourceContentVersion, String hash) {
        Instant now = Instant.now();
        return ChapterNarrationManifest.rehydrate(
                CHAPTER_ID,
                sourceContentVersion,
                hash,
                now,
                now
        );
    }

    private ManagedVoice createActiveVoice(UUID id, long revision) {
        Instant now = Instant.now();
        return ManagedVoice.rehydrate(
                id,
                "voice-key",
                "Voice Name",
                "provider-voice-id",
                ManagedVoiceStatus.ACTIVE,
                1,
                false,
                revision,
                now,
                now
        );
    }

    private ChapterNarrationSegment createSegment(UUID id, int index, ChapterNarrationSegmentStatus status) {
        Instant now = Instant.now();
        String text = "Segment text at index " + index + " with ID " + id;
        String hash = NarrationTextSegment.computeSha256(text);
        return ChapterNarrationSegment.rehydrate(
                id,
                CHAPTER_ID,
                index,
                text,
                text.length(),
                hash,
                status,
                now,
                now
        );
    }

    private ChapterNarrationAudio createAudio(UUID id, UUID segmentId, UUID voiceId, long revision) {
        Instant now = Instant.now();
        return ChapterNarrationAudio.rehydrate(
                id,
                segmentId,
                voiceId,
                UUID.randomUUID(),
                revision,
                51840L,
                48000,
                0L,
                now,
                now
        );
    }

    @Nested
    @DisplayName("Input Validation Tests")
    class ValidationTests {

        @Test
        @DisplayName("Should reject null chapterId")
        void shouldRejectNullChapterId() {
            assertThatThrownBy(() -> useCase.execute((UUID) null, VOICE_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("chapterId must not be null");
        }

        @Test
        @DisplayName("Should reject null managedVoiceId")
        void shouldRejectNullManagedVoiceId() {
            assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("managedVoiceId must not be null");
        }

        @Test
        @DisplayName("Should reject null command")
        void shouldRejectNullCommand() {
            assertThatThrownBy(() -> useCase.execute((CleanupCompletedChapterNarrationRetiredAudioCommand) null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("command must not be null");
        }
    }

    @Nested
    @DisplayName("Manifest-Stable Eligibility Tests (MS-04.9H.7C1C2A)")
    class ManifestStabilityTests {

        @Test
        @DisplayName("1. When ChapterNarrationManifest is absent on initial read -> returns NOT_ELIGIBLE")
        void whenManifestAbsentInitially_returnsNotEligible() {
            when(manifestRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(Optional.empty());

            ChapterNarrationCompletionCleanupSummary summary = useCase.execute(CHAPTER_ID, VOICE_ID);

            assertThat(summary.status()).isEqualTo(ChapterNarrationCompletionCleanupStatus.NOT_ELIGIBLE);
            assertThat(summary.currentNarrationReady()).isFalse();
            assertThat(summary.retiredAudioCandidateCount()).isEqualTo(0);
            assertThat(summary.cleanupComplete()).isFalse();
            verify(handoffUseCase, never()).execute(any(UUID.class));
        }

        @Test
        @DisplayName("2. When manifest is stable before and after candidate capture -> cleanup proceeds normally")
        void whenManifestStable_cleanupProceeds() {
            ChapterNarrationManifest manifest = createManifest(1L, VALID_MANIFEST_HASH);
            when(manifestRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(Optional.of(manifest));

            UUID currentSegId = UUID.randomUUID();
            ChapterNarrationSegment currentSeg = createSegment(currentSegId, 0, ChapterNarrationSegmentStatus.CURRENT);

            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createActiveVoice(VOICE_ID, SYNTHESIS_REVISION)));
            when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                    .thenReturn(List.of(currentSeg));

            ChapterNarrationAudio currentAudio = createAudio(UUID.randomUUID(), currentSegId, VOICE_ID, SYNTHESIS_REVISION);
            when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(currentSegId), VOICE_ID))
                    .thenReturn(List.of(currentAudio));
            when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(currentSegId), VOICE_ID))
                    .thenReturn(Collections.emptyList());

            UUID retSegId = UUID.randomUUID();
            ChapterNarrationSegment retSeg = createSegment(retSegId, 0, ChapterNarrationSegmentStatus.RETIRED);
            when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.RETIRED))
                    .thenReturn(List.of(retSeg));

            UUID retAudioId = UUID.randomUUID();
            ChapterNarrationAudio retAudio = createAudio(retAudioId, retSegId, VOICE_ID, SYNTHESIS_REVISION);
            when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(retSegId), VOICE_ID))
                    .thenReturn(List.of(retAudio));

            when(handoffUseCase.execute(retAudioId)).thenReturn(new HandoffRetiredNarrationAudioCleanupResult(
                    retAudioId, retSegId, UUID.randomUUID(), HandoffRetiredNarrationAudioCleanupOutcome.HANDED_OFF
            ));

            ChapterNarrationCompletionCleanupSummary summary = useCase.execute(CHAPTER_ID, VOICE_ID);

            assertThat(summary.status()).isEqualTo(ChapterNarrationCompletionCleanupStatus.COMPLETED);
            assertThat(summary.currentNarrationReady()).isTrue();
            assertThat(summary.retiredAudioCandidateCount()).isEqualTo(1);
            assertThat(summary.handedOffCount()).isEqualTo(1);
            assertThat(summary.cleanupComplete()).isTrue();
            verify(handoffUseCase).execute(retAudioId);
        }

        @Test
        @DisplayName("3. When sourceContentVersion changes on re-read -> returns MANIFEST_CHANGED and zero handoffs")
        void whenSourceContentVersionChanges_returnsManifestChangedAndZeroHandoffs() {
            ChapterNarrationManifest manifestV1 = createManifest(1L, VALID_MANIFEST_HASH);
            ChapterNarrationManifest manifestV2 = createManifest(2L, VALID_MANIFEST_HASH);
            // 1st read returns v1, 2nd read returns v2
            when(manifestRepositoryPort.findByChapterId(CHAPTER_ID))
                    .thenReturn(Optional.of(manifestV1))
                    .thenReturn(Optional.of(manifestV2));

            UUID currentSegId = UUID.randomUUID();
            ChapterNarrationSegment currentSeg = createSegment(currentSegId, 0, ChapterNarrationSegmentStatus.CURRENT);

            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createActiveVoice(VOICE_ID, SYNTHESIS_REVISION)));
            when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                    .thenReturn(List.of(currentSeg));

            ChapterNarrationAudio currentAudio = createAudio(UUID.randomUUID(), currentSegId, VOICE_ID, SYNTHESIS_REVISION);
            when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(currentSegId), VOICE_ID))
                    .thenReturn(List.of(currentAudio));
            when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(currentSegId), VOICE_ID))
                    .thenReturn(Collections.emptyList());

            UUID retSegId = UUID.randomUUID();
            ChapterNarrationSegment retSeg = createSegment(retSegId, 0, ChapterNarrationSegmentStatus.RETIRED);
            when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.RETIRED))
                    .thenReturn(List.of(retSeg));

            UUID retAudioId = UUID.randomUUID();
            ChapterNarrationAudio retAudio = createAudio(retAudioId, retSegId, VOICE_ID, SYNTHESIS_REVISION);
            when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(retSegId), VOICE_ID))
                    .thenReturn(List.of(retAudio));

            ChapterNarrationCompletionCleanupSummary summary = useCase.execute(CHAPTER_ID, VOICE_ID);

            assertThat(summary.status()).isEqualTo(ChapterNarrationCompletionCleanupStatus.MANIFEST_CHANGED);
            assertThat(summary.currentNarrationReady()).isTrue();
            assertThat(summary.retiredAudioCandidateCount()).isEqualTo(1);
            assertThat(summary.handedOffCount()).isEqualTo(0);
            assertThat(summary.cleanupAttemptedCount()).isEqualTo(0);
            assertThat(summary.cleanupComplete()).isFalse();
            verify(handoffUseCase, never()).execute(any(UUID.class));
        }

        @Test
        @DisplayName("4. When manifestHash changes on re-read -> returns MANIFEST_CHANGED and zero handoffs")
        void whenManifestHashChanges_returnsManifestChangedAndZeroHandoffs() {
            ChapterNarrationManifest manifest1 = createManifest(1L, VALID_MANIFEST_HASH);
            ChapterNarrationManifest manifest2 = createManifest(1L, OTHER_MANIFEST_HASH);
            when(manifestRepositoryPort.findByChapterId(CHAPTER_ID))
                    .thenReturn(Optional.of(manifest1))
                    .thenReturn(Optional.of(manifest2));

            UUID currentSegId = UUID.randomUUID();
            ChapterNarrationSegment currentSeg = createSegment(currentSegId, 0, ChapterNarrationSegmentStatus.CURRENT);

            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createActiveVoice(VOICE_ID, SYNTHESIS_REVISION)));
            when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                    .thenReturn(List.of(currentSeg));

            ChapterNarrationAudio currentAudio = createAudio(UUID.randomUUID(), currentSegId, VOICE_ID, SYNTHESIS_REVISION);
            when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(currentSegId), VOICE_ID))
                    .thenReturn(List.of(currentAudio));
            when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(currentSegId), VOICE_ID))
                    .thenReturn(Collections.emptyList());

            UUID retSegId = UUID.randomUUID();
            ChapterNarrationSegment retSeg = createSegment(retSegId, 0, ChapterNarrationSegmentStatus.RETIRED);
            when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.RETIRED))
                    .thenReturn(List.of(retSeg));

            UUID retAudioId = UUID.randomUUID();
            ChapterNarrationAudio retAudio = createAudio(retAudioId, retSegId, VOICE_ID, SYNTHESIS_REVISION);
            when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(retSegId), VOICE_ID))
                    .thenReturn(List.of(retAudio));

            ChapterNarrationCompletionCleanupSummary summary = useCase.execute(CHAPTER_ID, VOICE_ID);

            assertThat(summary.status()).isEqualTo(ChapterNarrationCompletionCleanupStatus.MANIFEST_CHANGED);
            assertThat(summary.currentNarrationReady()).isTrue();
            assertThat(summary.retiredAudioCandidateCount()).isEqualTo(1);
            assertThat(summary.handedOffCount()).isEqualTo(0);
            assertThat(summary.cleanupAttemptedCount()).isEqualTo(0);
            assertThat(summary.cleanupComplete()).isFalse();
            verify(handoffUseCase, never()).execute(any(UUID.class));
        }

        @Test
        @DisplayName("5. When manifest disappears on re-read -> returns MANIFEST_CHANGED and zero handoffs")
        void whenManifestDisappears_returnsManifestChangedAndZeroHandoffs() {
            ChapterNarrationManifest manifest = createManifest(1L, VALID_MANIFEST_HASH);
            when(manifestRepositoryPort.findByChapterId(CHAPTER_ID))
                    .thenReturn(Optional.of(manifest))
                    .thenReturn(Optional.empty());

            UUID currentSegId = UUID.randomUUID();
            ChapterNarrationSegment currentSeg = createSegment(currentSegId, 0, ChapterNarrationSegmentStatus.CURRENT);

            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createActiveVoice(VOICE_ID, SYNTHESIS_REVISION)));
            when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                    .thenReturn(List.of(currentSeg));

            ChapterNarrationAudio currentAudio = createAudio(UUID.randomUUID(), currentSegId, VOICE_ID, SYNTHESIS_REVISION);
            when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(currentSegId), VOICE_ID))
                    .thenReturn(List.of(currentAudio));
            when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(currentSegId), VOICE_ID))
                    .thenReturn(Collections.emptyList());

            UUID retSegId = UUID.randomUUID();
            ChapterNarrationSegment retSeg = createSegment(retSegId, 0, ChapterNarrationSegmentStatus.RETIRED);
            when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.RETIRED))
                    .thenReturn(List.of(retSeg));

            UUID retAudioId = UUID.randomUUID();
            ChapterNarrationAudio retAudio = createAudio(retAudioId, retSegId, VOICE_ID, SYNTHESIS_REVISION);
            when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(retSegId), VOICE_ID))
                    .thenReturn(List.of(retAudio));

            ChapterNarrationCompletionCleanupSummary summary = useCase.execute(CHAPTER_ID, VOICE_ID);

            assertThat(summary.status()).isEqualTo(ChapterNarrationCompletionCleanupStatus.MANIFEST_CHANGED);
            assertThat(summary.currentNarrationReady()).isTrue();
            assertThat(summary.retiredAudioCandidateCount()).isEqualTo(1);
            assertThat(summary.handedOffCount()).isEqualTo(0);
            assertThat(summary.cleanupComplete()).isFalse();
            verify(handoffUseCase, never()).execute(any(UUID.class));
        }
    }

    @Nested
    @DisplayName("Fresh Readiness Gate Tests")
    class ReadinessGateTests {

        @Test
        @DisplayName("When managed voice is not found -> returns notEligible")
        void whenVoiceNotFound_returnsNotEligible() {
            when(manifestRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(Optional.of(createManifest(1L, VALID_MANIFEST_HASH)));
            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.empty());

            ChapterNarrationCompletionCleanupSummary summary = useCase.execute(CHAPTER_ID, VOICE_ID);

            assertThat(summary.status()).isEqualTo(ChapterNarrationCompletionCleanupStatus.NOT_ELIGIBLE);
            assertThat(summary.currentNarrationReady()).isFalse();
            assertThat(summary.retiredAudioCandidateCount()).isEqualTo(0);
            assertThat(summary.cleanupComplete()).isFalse();
            verify(handoffUseCase, never()).execute(any(UUID.class));
        }

        @Test
        @DisplayName("When managed voice is DISABLED -> returns notEligible")
        void whenVoiceDisabled_returnsNotEligible() {
            when(manifestRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(Optional.of(createManifest(1L, VALID_MANIFEST_HASH)));
            Instant now = Instant.now();
            ManagedVoice disabledVoice = ManagedVoice.rehydrate(
                    VOICE_ID, "key", "Name", "p-id",
                    ManagedVoiceStatus.DISABLED, 1, false, SYNTHESIS_REVISION, now, now
            );
            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(disabledVoice));

            ChapterNarrationCompletionCleanupSummary summary = useCase.execute(CHAPTER_ID, VOICE_ID);

            assertThat(summary.status()).isEqualTo(ChapterNarrationCompletionCleanupStatus.NOT_ELIGIBLE);
            assertThat(summary.currentNarrationReady()).isFalse();
            verify(handoffUseCase, never()).execute(any(UUID.class));
        }

        @Test
        @DisplayName("When zero CURRENT segments exist -> returns notEligible")
        void whenZeroCurrentSegments_returnsNotEligible() {
            when(manifestRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(Optional.of(createManifest(1L, VALID_MANIFEST_HASH)));
            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createActiveVoice(VOICE_ID, SYNTHESIS_REVISION)));
            when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                    .thenReturn(Collections.emptyList());

            ChapterNarrationCompletionCleanupSummary summary = useCase.execute(CHAPTER_ID, VOICE_ID);

            assertThat(summary.status()).isEqualTo(ChapterNarrationCompletionCleanupStatus.NOT_ELIGIBLE);
            assertThat(summary.currentNarrationReady()).isFalse();
            verify(handoffUseCase, never()).execute(any(UUID.class));
        }

        @Test
        @DisplayName("When one CURRENT segment is MISSING audio -> blocks cleanup")
        void whenOneCurrentSegmentMissingAudio_blocksCleanup() {
            when(manifestRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(Optional.of(createManifest(1L, VALID_MANIFEST_HASH)));
            UUID seg1Id = UUID.randomUUID();
            UUID seg2Id = UUID.randomUUID();
            ChapterNarrationSegment seg1 = createSegment(seg1Id, 0, ChapterNarrationSegmentStatus.CURRENT);
            ChapterNarrationSegment seg2 = createSegment(seg2Id, 1, ChapterNarrationSegmentStatus.CURRENT);

            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createActiveVoice(VOICE_ID, SYNTHESIS_REVISION)));
            when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                    .thenReturn(List.of(seg1, seg2));

            // seg1 has audio, seg2 is missing audio
            ChapterNarrationAudio audio1 = createAudio(UUID.randomUUID(), seg1Id, VOICE_ID, SYNTHESIS_REVISION);
            when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(seg1Id, seg2Id), VOICE_ID))
                    .thenReturn(List.of(audio1));
            when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(seg1Id, seg2Id), VOICE_ID))
                    .thenReturn(Collections.emptyList());

            ChapterNarrationCompletionCleanupSummary summary = useCase.execute(CHAPTER_ID, VOICE_ID);

            assertThat(summary.status()).isEqualTo(ChapterNarrationCompletionCleanupStatus.NOT_ELIGIBLE);
            assertThat(summary.currentNarrationReady()).isFalse();
            assertThat(summary.retiredAudioCandidateCount()).isEqualTo(0);
            verify(handoffUseCase, never()).execute(any(UUID.class));
        }

        @Test
        @DisplayName("When one CURRENT segment is FAILED -> blocks cleanup")
        void whenOneCurrentSegmentFailed_blocksCleanup() {
            when(manifestRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(Optional.of(createManifest(1L, VALID_MANIFEST_HASH)));
            UUID seg1Id = UUID.randomUUID();
            UUID seg2Id = UUID.randomUUID();
            ChapterNarrationSegment seg1 = createSegment(seg1Id, 0, ChapterNarrationSegmentStatus.CURRENT);
            ChapterNarrationSegment seg2 = createSegment(seg2Id, 1, ChapterNarrationSegmentStatus.CURRENT);

            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createActiveVoice(VOICE_ID, SYNTHESIS_REVISION)));
            when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                    .thenReturn(List.of(seg1, seg2));

            ChapterNarrationAudio audio1 = createAudio(UUID.randomUUID(), seg1Id, VOICE_ID, SYNTHESIS_REVISION);
            when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(seg1Id, seg2Id), VOICE_ID))
                    .thenReturn(List.of(audio1));

            ChapterNarrationAudioFailure failure2 = ChapterNarrationAudioFailure.create(
                    UUID.randomUUID(),
                    seg2Id,
                    VOICE_ID,
                    NarrationAudioOperation.INITIAL_GENERATION,
                    NarrationAudioFailureStage.TTS_SYNTHESIS,
                    SYNTHESIS_REVISION,
                    "PROVIDER_ERROR",
                    Instant.now()
            );
            when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(seg1Id, seg2Id), VOICE_ID))
                    .thenReturn(List.of(failure2));

            ChapterNarrationCompletionCleanupSummary summary = useCase.execute(CHAPTER_ID, VOICE_ID);

            assertThat(summary.status()).isEqualTo(ChapterNarrationCompletionCleanupStatus.NOT_ELIGIBLE);
            assertThat(summary.currentNarrationReady()).isFalse();
            verify(handoffUseCase, never()).execute(any(UUID.class));
        }

        @Test
        @DisplayName("When one CURRENT segment is OUTDATED (synthesis revision mismatch) -> blocks cleanup")
        void whenOneCurrentSegmentOutdated_blocksCleanup() {
            when(manifestRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(Optional.of(createManifest(1L, VALID_MANIFEST_HASH)));
            UUID seg1Id = UUID.randomUUID();
            UUID seg2Id = UUID.randomUUID();
            ChapterNarrationSegment seg1 = createSegment(seg1Id, 0, ChapterNarrationSegmentStatus.CURRENT);
            ChapterNarrationSegment seg2 = createSegment(seg2Id, 1, ChapterNarrationSegmentStatus.CURRENT);

            // Voice is revision 2
            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createActiveVoice(VOICE_ID, 2L)));
            when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                    .thenReturn(List.of(seg1, seg2));

            // seg1 audio is revision 2 (READY), seg2 audio is revision 1 (OUTDATED)
            ChapterNarrationAudio audio1 = createAudio(UUID.randomUUID(), seg1Id, VOICE_ID, 2L);
            ChapterNarrationAudio audio2 = createAudio(UUID.randomUUID(), seg2Id, VOICE_ID, 1L);
            when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(seg1Id, seg2Id), VOICE_ID))
                    .thenReturn(List.of(audio1, audio2));
            when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(seg1Id, seg2Id), VOICE_ID))
                    .thenReturn(Collections.emptyList());

            ChapterNarrationCompletionCleanupSummary summary = useCase.execute(CHAPTER_ID, VOICE_ID);

            assertThat(summary.status()).isEqualTo(ChapterNarrationCompletionCleanupStatus.NOT_ELIGIBLE);
            assertThat(summary.currentNarrationReady()).isFalse();
            verify(handoffUseCase, never()).execute(any(UUID.class));
        }

        @Test
        @DisplayName("When one CURRENT segment has same-revision untimed audio -> blocks cleanup")
        void whenOneCurrentSegmentUntimed_blocksCleanup() {
            when(manifestRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(Optional.of(createManifest(1L, VALID_MANIFEST_HASH)));
            UUID seg1Id = UUID.randomUUID();
            ChapterNarrationSegment seg1 = createSegment(seg1Id, 0, ChapterNarrationSegmentStatus.CURRENT);

            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createActiveVoice(VOICE_ID, SYNTHESIS_REVISION)));
            when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                    .thenReturn(List.of(seg1));

            // Same revision but untimed (legacy null/null)
            ChapterNarrationAudio untimedAudio = ChapterNarrationAudio.create(
                    UUID.randomUUID(), seg1Id, VOICE_ID, UUID.randomUUID(), SYNTHESIS_REVISION, Instant.now()
            );
            when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(seg1Id), VOICE_ID))
                    .thenReturn(List.of(untimedAudio));
            when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(seg1Id), VOICE_ID))
                    .thenReturn(Collections.emptyList());

            ChapterNarrationCompletionCleanupSummary summary = useCase.execute(CHAPTER_ID, VOICE_ID);

            assertThat(summary.status()).isEqualTo(ChapterNarrationCompletionCleanupStatus.NOT_ELIGIBLE);
            assertThat(summary.currentNarrationReady()).isFalse();
            verify(handoffUseCase, never()).execute(any(UUID.class));
        }
    }

    @Nested
    @DisplayName("Retired Narration Audio Cleanup Tests")
    class RetiredCleanupTests {

        @Test
        @DisplayName("When all CURRENT READY and no RETIRED segments exist -> returns COMPLETED clean summary")
        void whenAllReadyAndNoRetiredSegments_returnsCleanSummary() {
            when(manifestRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(Optional.of(createManifest(1L, VALID_MANIFEST_HASH)));
            UUID seg1Id = UUID.randomUUID();
            ChapterNarrationSegment seg1 = createSegment(seg1Id, 0, ChapterNarrationSegmentStatus.CURRENT);

            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createActiveVoice(VOICE_ID, SYNTHESIS_REVISION)));
            when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                    .thenReturn(List.of(seg1));

            ChapterNarrationAudio audio1 = createAudio(UUID.randomUUID(), seg1Id, VOICE_ID, SYNTHESIS_REVISION);
            when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(seg1Id), VOICE_ID))
                    .thenReturn(List.of(audio1));
            when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(seg1Id), VOICE_ID))
                    .thenReturn(Collections.emptyList());

            when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.RETIRED))
                    .thenReturn(Collections.emptyList());

            ChapterNarrationCompletionCleanupSummary summary = useCase.execute(CHAPTER_ID, VOICE_ID);

            assertThat(summary.status()).isEqualTo(ChapterNarrationCompletionCleanupStatus.COMPLETED);
            assertThat(summary.currentNarrationReady()).isTrue();
            assertThat(summary.retiredAudioCandidateCount()).isEqualTo(0);
            assertThat(summary.cleanupComplete()).isTrue();
            verify(handoffUseCase, never()).execute(any(UUID.class));
        }

        @Test
        @DisplayName("When all CURRENT READY but retired audio exists only for ANOTHER voice -> no handoff")
        void whenRetiredAudioForOtherVoiceOnly_noHandoff() {
            when(manifestRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(Optional.of(createManifest(1L, VALID_MANIFEST_HASH)));
            UUID currentSegId = UUID.randomUUID();
            ChapterNarrationSegment currentSeg = createSegment(currentSegId, 0, ChapterNarrationSegmentStatus.CURRENT);

            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createActiveVoice(VOICE_ID, SYNTHESIS_REVISION)));
            when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                    .thenReturn(List.of(currentSeg));

            ChapterNarrationAudio currentAudio = createAudio(UUID.randomUUID(), currentSegId, VOICE_ID, SYNTHESIS_REVISION);
            when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(currentSegId), VOICE_ID))
                    .thenReturn(List.of(currentAudio));
            when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(currentSegId), VOICE_ID))
                    .thenReturn(Collections.emptyList());

            UUID retiredSegId = UUID.randomUUID();
            ChapterNarrationSegment retiredSeg = createSegment(retiredSegId, 0, ChapterNarrationSegmentStatus.RETIRED);
            when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.RETIRED))
                    .thenReturn(List.of(retiredSeg));

            when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(retiredSegId), VOICE_ID))
                    .thenReturn(Collections.emptyList());

            ChapterNarrationCompletionCleanupSummary summary = useCase.execute(CHAPTER_ID, VOICE_ID);

            assertThat(summary.status()).isEqualTo(ChapterNarrationCompletionCleanupStatus.COMPLETED);
            assertThat(summary.currentNarrationReady()).isTrue();
            assertThat(summary.retiredAudioCandidateCount()).isEqualTo(0);
            assertThat(summary.cleanupComplete()).isTrue();
            verify(handoffUseCase, never()).execute(any(UUID.class));
        }

        @Test
        @DisplayName("Happy Path: all CURRENT READY -> hands off all selected-voice retired audio assignments in segmentIndex ASC order")
        void whenAllReady_handsOffAllSelectedVoiceRetiredAudiosInOrder() {
            when(manifestRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(Optional.of(createManifest(1L, VALID_MANIFEST_HASH)));
            UUID currentSegId = UUID.randomUUID();
            ChapterNarrationSegment currentSeg = createSegment(currentSegId, 0, ChapterNarrationSegmentStatus.CURRENT);

            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createActiveVoice(VOICE_ID, SYNTHESIS_REVISION)));
            when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                    .thenReturn(List.of(currentSeg));

            ChapterNarrationAudio currentAudio = createAudio(UUID.randomUUID(), currentSegId, VOICE_ID, SYNTHESIS_REVISION);
            when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(currentSegId), VOICE_ID))
                    .thenReturn(List.of(currentAudio));
            when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(currentSegId), VOICE_ID))
                    .thenReturn(Collections.emptyList());

            // 2 retired segments: segB (index 2), segA (index 0)
            UUID retSegAId = UUID.randomUUID();
            UUID retSegBId = UUID.randomUUID();
            ChapterNarrationSegment retSegA = createSegment(retSegAId, 0, ChapterNarrationSegmentStatus.RETIRED);
            ChapterNarrationSegment retSegB = createSegment(retSegBId, 2, ChapterNarrationSegmentStatus.RETIRED);

            when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.RETIRED))
                    .thenReturn(List.of(retSegB, retSegA)); // unordered from DB

            UUID audioAId = UUID.randomUUID();
            UUID audioBId = UUID.randomUUID();
            ChapterNarrationAudio audioA = createAudio(audioAId, retSegAId, VOICE_ID, SYNTHESIS_REVISION);
            ChapterNarrationAudio audioB = createAudio(audioBId, retSegBId, VOICE_ID, SYNTHESIS_REVISION);

            when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(eq(List.of(retSegBId, retSegAId)), eq(VOICE_ID)))
                    .thenReturn(List.of(audioB, audioA));

            when(handoffUseCase.execute(audioAId)).thenReturn(new HandoffRetiredNarrationAudioCleanupResult(
                    audioAId, retSegAId, UUID.randomUUID(), HandoffRetiredNarrationAudioCleanupOutcome.HANDED_OFF
            ));
            when(handoffUseCase.execute(audioBId)).thenReturn(new HandoffRetiredNarrationAudioCleanupResult(
                    audioBId, retSegBId, UUID.randomUUID(), HandoffRetiredNarrationAudioCleanupOutcome.HANDED_OFF
            ));

            ChapterNarrationCompletionCleanupSummary summary = useCase.execute(new CleanupCompletedChapterNarrationRetiredAudioCommand(CHAPTER_ID, VOICE_ID));

            assertThat(summary.status()).isEqualTo(ChapterNarrationCompletionCleanupStatus.COMPLETED);
            assertThat(summary.currentNarrationReady()).isTrue();
            assertThat(summary.retiredAudioCandidateCount()).isEqualTo(2);
            assertThat(summary.handedOffCount()).isEqualTo(2);
            assertThat(summary.alreadyAbsentCount()).isEqualTo(0);
            assertThat(summary.skippedNotRetiredCount()).isEqualTo(0);
            assertThat(summary.skippedSharedMediaReferenceCount()).isEqualTo(0);
            assertThat(summary.failedCount()).isEqualTo(0);
            assertThat(summary.cleanupAttemptedCount()).isEqualTo(2);
            assertThat(summary.remainingCleanupCount()).isEqualTo(0);
            assertThat(summary.cleanupComplete()).isTrue();

            // Verify order: audioA (index 0) executed before audioB (index 2)
            InOrder inOrder = inOrder(handoffUseCase);
            inOrder.verify(handoffUseCase).execute(audioAId);
            inOrder.verify(handoffUseCase).execute(audioBId);
        }

        @Test
        @DisplayName("Failure Isolation: one handoff throws RuntimeException -> records PARTIAL and continues remaining candidates")
        void whenOneHandoffThrowsException_recordsFailedAndContinuesRemaining() {
            when(manifestRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(Optional.of(createManifest(1L, VALID_MANIFEST_HASH)));
            UUID currentSegId = UUID.randomUUID();
            ChapterNarrationSegment currentSeg = createSegment(currentSegId, 0, ChapterNarrationSegmentStatus.CURRENT);

            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createActiveVoice(VOICE_ID, SYNTHESIS_REVISION)));
            when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                    .thenReturn(List.of(currentSeg));

            ChapterNarrationAudio currentAudio = createAudio(UUID.randomUUID(), currentSegId, VOICE_ID, SYNTHESIS_REVISION);
            when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(currentSegId), VOICE_ID))
                    .thenReturn(List.of(currentAudio));
            when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(currentSegId), VOICE_ID))
                    .thenReturn(Collections.emptyList());

            UUID retSeg1Id = UUID.randomUUID();
            UUID retSeg2Id = UUID.randomUUID();
            ChapterNarrationSegment retSeg1 = createSegment(retSeg1Id, 0, ChapterNarrationSegmentStatus.RETIRED);
            ChapterNarrationSegment retSeg2 = createSegment(retSeg2Id, 1, ChapterNarrationSegmentStatus.RETIRED);

            when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.RETIRED))
                    .thenReturn(List.of(retSeg1, retSeg2));

            UUID audio1Id = UUID.randomUUID();
            UUID audio2Id = UUID.randomUUID();
            ChapterNarrationAudio audio1 = createAudio(audio1Id, retSeg1Id, VOICE_ID, SYNTHESIS_REVISION);
            ChapterNarrationAudio audio2 = createAudio(audio2Id, retSeg2Id, VOICE_ID, SYNTHESIS_REVISION);

            when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(eq(List.of(retSeg1Id, retSeg2Id)), eq(VOICE_ID)))
                    .thenReturn(List.of(audio1, audio2));

            // audio1 fails unexpectedly
            when(handoffUseCase.execute(audio1Id)).thenThrow(new RuntimeException("Simulated DB lock timeout"));
            // audio2 succeeds
            when(handoffUseCase.execute(audio2Id)).thenReturn(new HandoffRetiredNarrationAudioCleanupResult(
                    audio2Id, retSeg2Id, UUID.randomUUID(), HandoffRetiredNarrationAudioCleanupOutcome.HANDED_OFF
            ));

            ChapterNarrationCompletionCleanupSummary summary = useCase.execute(CHAPTER_ID, VOICE_ID);

            assertThat(summary.status()).isEqualTo(ChapterNarrationCompletionCleanupStatus.PARTIAL);
            assertThat(summary.currentNarrationReady()).isTrue();
            assertThat(summary.retiredAudioCandidateCount()).isEqualTo(2);
            assertThat(summary.handedOffCount()).isEqualTo(1);
            assertThat(summary.failedCount()).isEqualTo(1);
            assertThat(summary.cleanupAttemptedCount()).isEqualTo(2);
            assertThat(summary.remainingCleanupCount()).isEqualTo(1);
            assertThat(summary.cleanupComplete()).isFalse();

            verify(handoffUseCase).execute(audio1Id);
            verify(handoffUseCase).execute(audio2Id);
        }

        @Test
        @DisplayName("Outcome Mapping: records alreadyAbsent, skippedNotRetired, and skippedSharedMediaReference accurately as PARTIAL")
        void mapsAllNonHandoffOutcomesAccurately() {
            when(manifestRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(Optional.of(createManifest(1L, VALID_MANIFEST_HASH)));
            UUID currentSegId = UUID.randomUUID();
            ChapterNarrationSegment currentSeg = createSegment(currentSegId, 0, ChapterNarrationSegmentStatus.CURRENT);

            when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(createActiveVoice(VOICE_ID, SYNTHESIS_REVISION)));
            when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                    .thenReturn(List.of(currentSeg));

            ChapterNarrationAudio currentAudio = createAudio(UUID.randomUUID(), currentSegId, VOICE_ID, SYNTHESIS_REVISION);
            when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(currentSegId), VOICE_ID))
                    .thenReturn(List.of(currentAudio));
            when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(currentSegId), VOICE_ID))
                    .thenReturn(Collections.emptyList());

            UUID retSeg1Id = UUID.randomUUID();
            UUID retSeg2Id = UUID.randomUUID();
            UUID retSeg3Id = UUID.randomUUID();
            ChapterNarrationSegment retSeg1 = createSegment(retSeg1Id, 0, ChapterNarrationSegmentStatus.RETIRED);
            ChapterNarrationSegment retSeg2 = createSegment(retSeg2Id, 1, ChapterNarrationSegmentStatus.RETIRED);
            ChapterNarrationSegment retSeg3 = createSegment(retSeg3Id, 2, ChapterNarrationSegmentStatus.RETIRED);

            when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.RETIRED))
                    .thenReturn(List.of(retSeg1, retSeg2, retSeg3));

            UUID audio1Id = UUID.randomUUID();
            UUID audio2Id = UUID.randomUUID();
            UUID audio3Id = UUID.randomUUID();
            ChapterNarrationAudio audio1 = createAudio(audio1Id, retSeg1Id, VOICE_ID, SYNTHESIS_REVISION);
            ChapterNarrationAudio audio2 = createAudio(audio2Id, retSeg2Id, VOICE_ID, SYNTHESIS_REVISION);
            ChapterNarrationAudio audio3 = createAudio(audio3Id, retSeg3Id, VOICE_ID, SYNTHESIS_REVISION);

            when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(eq(List.of(retSeg1Id, retSeg2Id, retSeg3Id)), eq(VOICE_ID)))
                    .thenReturn(List.of(audio1, audio2, audio3));

            when(handoffUseCase.execute(audio1Id)).thenReturn(new HandoffRetiredNarrationAudioCleanupResult(
                    audio1Id, retSeg1Id, UUID.randomUUID(), HandoffRetiredNarrationAudioCleanupOutcome.ALREADY_ABSENT
            ));
            when(handoffUseCase.execute(audio2Id)).thenReturn(new HandoffRetiredNarrationAudioCleanupResult(
                    audio2Id, retSeg2Id, UUID.randomUUID(), HandoffRetiredNarrationAudioCleanupOutcome.SKIPPED_NOT_RETIRED
            ));
            when(handoffUseCase.execute(audio3Id)).thenReturn(new HandoffRetiredNarrationAudioCleanupResult(
                    audio3Id, retSeg3Id, UUID.randomUUID(), HandoffRetiredNarrationAudioCleanupOutcome.SKIPPED_SHARED_MEDIA_REFERENCE
            ));

            ChapterNarrationCompletionCleanupSummary summary = useCase.execute(CHAPTER_ID, VOICE_ID);

            assertThat(summary.status()).isEqualTo(ChapterNarrationCompletionCleanupStatus.PARTIAL);
            assertThat(summary.currentNarrationReady()).isTrue();
            assertThat(summary.retiredAudioCandidateCount()).isEqualTo(3);
            assertThat(summary.handedOffCount()).isEqualTo(0);
            assertThat(summary.alreadyAbsentCount()).isEqualTo(1);
            assertThat(summary.skippedNotRetiredCount()).isEqualTo(1);
            assertThat(summary.skippedSharedMediaReferenceCount()).isEqualTo(1);
            assertThat(summary.failedCount()).isEqualTo(0);
            assertThat(summary.cleanupAttemptedCount()).isEqualTo(3);
            assertThat(summary.remainingCleanupCount()).isEqualTo(2); // 3 - 1 (already absent)
            assertThat(summary.cleanupComplete()).isFalse();
        }

        @Test
        @DisplayName("Canonical CURRENT narration ready + RETIRED segment with legacy untimed audio -> eligible for normal cleanup")
        void whenCurrentReadyAndRetiredSegmentHasLegacyUntimedAudio_eligibleForNormalHandoffCleanup() {
            when(manifestRepositoryPort.findByChapterId(CHAPTER_ID))
                    .thenReturn(Optional.of(createManifest(1L, VALID_MANIFEST_HASH)));
            UUID currentSegId = UUID.randomUUID();
            ChapterNarrationSegment currentSeg = createSegment(currentSegId, 0, ChapterNarrationSegmentStatus.CURRENT);

            when(managedVoiceRepositoryPort.findById(VOICE_ID))
                    .thenReturn(Optional.of(createActiveVoice(VOICE_ID, SYNTHESIS_REVISION)));
            when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.CURRENT))
                    .thenReturn(List.of(currentSeg));

            // CURRENT audio is canonical ready (timed 51840L, 48000)
            ChapterNarrationAudio currentAudio = createAudio(UUID.randomUUID(), currentSegId, VOICE_ID, SYNTHESIS_REVISION);
            when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(currentSegId), VOICE_ID))
                    .thenReturn(List.of(currentAudio));
            when(failureRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(currentSegId), VOICE_ID))
                    .thenReturn(Collections.emptyList());

            // RETIRED segment with legacy untimed narration audio (null/null contribution samples and rate)
            UUID retSegId = UUID.randomUUID();
            ChapterNarrationSegment retSeg = createSegment(retSegId, 1, ChapterNarrationSegmentStatus.RETIRED);
            when(segmentRepositoryPort.findByChapterIdAndStatus(CHAPTER_ID, ChapterNarrationSegmentStatus.RETIRED))
                    .thenReturn(List.of(retSeg));

            UUID retAudioId = UUID.randomUUID();
            ChapterNarrationAudio legacyRetiredAudio = ChapterNarrationAudio.create(
                    retAudioId, retSegId, VOICE_ID, UUID.randomUUID(), 1L, Instant.now()
            );
            when(audioRepositoryPort.findBySegmentIdInAndManagedVoiceId(List.of(retSegId), VOICE_ID))
                    .thenReturn(List.of(legacyRetiredAudio));

            when(handoffUseCase.execute(retAudioId)).thenReturn(new HandoffRetiredNarrationAudioCleanupResult(
                    retAudioId, retSegId, legacyRetiredAudio.getMediaAssetId(), HandoffRetiredNarrationAudioCleanupOutcome.HANDED_OFF
            ));

            ChapterNarrationCompletionCleanupSummary summary = useCase.execute(CHAPTER_ID, VOICE_ID);

            assertThat(summary.status()).isEqualTo(ChapterNarrationCompletionCleanupStatus.COMPLETED);
            assertThat(summary.currentNarrationReady()).isTrue();
            assertThat(summary.retiredAudioCandidateCount()).isEqualTo(1);
            assertThat(summary.handedOffCount()).isEqualTo(1);
            assertThat(summary.failedCount()).isEqualTo(0);
            assertThat(summary.cleanupComplete()).isTrue();
            verify(handoffUseCase).execute(retAudioId);
        }
    }

    @Nested
    @DisplayName("Summary Semantics & Counter Invariant Tests (MS-04.9H.7C1C2A)")
    class SummarySemanticsTests {

        @Test
        @DisplayName("6. coordinatorFailed summary semantics")
        void coordinatorFailedSummarySemantics() {
            ChapterNarrationCompletionCleanupSummary summary = ChapterNarrationCompletionCleanupSummary.coordinatorFailed();

            assertThat(summary.status()).isEqualTo(ChapterNarrationCompletionCleanupStatus.COORDINATOR_FAILED);
            assertThat(summary.currentNarrationReady()).isFalse();
            assertThat(summary.retiredAudioCandidateCount()).isEqualTo(0);
            assertThat(summary.handedOffCount()).isEqualTo(0);
            assertThat(summary.alreadyAbsentCount()).isEqualTo(0);
            assertThat(summary.skippedNotRetiredCount()).isEqualTo(0);
            assertThat(summary.skippedSharedMediaReferenceCount()).isEqualTo(0);
            assertThat(summary.failedCount()).isEqualTo(0);
            assertThat(summary.cleanupAttemptedCount()).isEqualTo(0);
            assertThat(summary.remainingCleanupCount()).isEqualTo(0);
            assertThat(summary.cleanupComplete()).isFalse();
        }

        @Test
        @DisplayName("7. Invariant: attemptedCount <= candidateCount must hold across all states")
        void attemptedCountNeverExceedsCandidateCount() {
            ChapterNarrationCompletionCleanupSummary notEligible = ChapterNarrationCompletionCleanupSummary.notEligible();
            assertThat(notEligible.cleanupAttemptedCount()).isLessThanOrEqualTo(notEligible.retiredAudioCandidateCount());

            ChapterNarrationCompletionCleanupSummary clean = ChapterNarrationCompletionCleanupSummary.clean(true);
            assertThat(clean.cleanupAttemptedCount()).isLessThanOrEqualTo(clean.retiredAudioCandidateCount());

            ChapterNarrationCompletionCleanupSummary manifestChanged = ChapterNarrationCompletionCleanupSummary.manifestChanged(5);
            assertThat(manifestChanged.cleanupAttemptedCount()).isLessThanOrEqualTo(manifestChanged.retiredAudioCandidateCount());

            ChapterNarrationCompletionCleanupSummary coordinatorFailed = ChapterNarrationCompletionCleanupSummary.coordinatorFailed();
            assertThat(coordinatorFailed.cleanupAttemptedCount()).isLessThanOrEqualTo(coordinatorFailed.retiredAudioCandidateCount());

            ChapterNarrationCompletionCleanupSummary completed = new ChapterNarrationCompletionCleanupSummary(
                    ChapterNarrationCompletionCleanupStatus.COMPLETED, true, 3, 2, 1, 0, 0, 0
            );
            assertThat(completed.cleanupAttemptedCount()).isLessThanOrEqualTo(completed.retiredAudioCandidateCount());
        }

        @Test
        @DisplayName("Rejects creation where attemptedCount > candidateCount")
        void rejectsInvalidAttemptedCount() {
            assertThatThrownBy(() -> new ChapterNarrationCompletionCleanupSummary(
                    ChapterNarrationCompletionCleanupStatus.PARTIAL, true, 2, 2, 1, 0, 0, 0
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cleanupAttemptedCount (3) cannot exceed retiredAudioCandidateCount (2)");
        }
    }
}
