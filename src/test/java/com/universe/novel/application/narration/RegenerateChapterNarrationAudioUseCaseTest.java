package com.universe.novel.application.narration;

import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.dto.UploadMediaAssetRequestDTO;
import com.universe.media.contracts.dto.UploadMediaAssetResponseDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.novel.application.exceptions.ChapterNarrationAudioNotFoundException;
import com.universe.novel.application.exceptions.ChapterNarrationSegmentInvalidStateException;
import com.universe.novel.application.exceptions.ChapterNarrationSegmentNotFoundException;
import com.universe.novel.application.exceptions.ManagedVoiceInvalidStateException;
import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.exceptions.NarrationMediaCleanupRequestException;
import com.universe.novel.application.ports.ChapterNarrationAudioFailureRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationAudioRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationSegmentRepositoryPort;
import com.universe.novel.application.ports.ManagedVoiceRepositoryPort;
import com.universe.novel.application.ports.TtsProviderPort;
import com.universe.novel.domain.narration.ChapterNarrationAudio;
import com.universe.novel.domain.narration.ChapterNarrationAudioFailure;
import com.universe.novel.domain.narration.ChapterNarrationSegment;
import com.universe.novel.domain.narration.ChapterNarrationSegmentStatus;
import com.universe.novel.domain.narration.ManagedVoice;
import com.universe.novel.domain.narration.ManagedVoiceStatus;
import com.universe.novel.domain.narration.NarrationAudioFailureStage;
import com.universe.novel.domain.narration.NarrationAudioOperation;
import com.universe.novel.domain.narration.NarrationMediaCleanupReason;
import com.universe.novel.domain.narration.NarrationTextSegment;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.novel.application.ports.SegmentAudioEncoderPort;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RegenerateChapterNarrationAudioUseCase Unit Tests")
class RegenerateChapterNarrationAudioUseCaseTest {

    @Mock
    private ChapterNarrationSegmentRepositoryPort segmentRepositoryPort;

    @Mock
    private ManagedVoiceRepositoryPort managedVoiceRepositoryPort;

    @Mock
    private ChapterNarrationAudioRepositoryPort audioRepositoryPort;

    @Mock
    private ChapterNarrationAudioFailureRepositoryPort failureRepositoryPort;

    @Mock
    private TtsProviderPort ttsProviderPort;

    @Mock
    private SegmentAudioEncoderPort segmentAudioEncoderPort;

    @Mock
    private MediaContract mediaContract;

    @Mock
    private RequestNarrationMediaCleanupUseCase cleanupRequestUseCase;

    @Mock
    private IdGeneratorPort idGeneratorPort;

    @Mock
    private ClockPort clockPort;

    private RegenerateChapterNarrationAudioUseCase useCase;

    private static final UUID SEGMENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CHAPTER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID VOICE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID AUDIO_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID FAILURE_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID OLD_MEDIA_ASSET_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID NEW_MEDIA_ASSET_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final Instant INITIAL_TIME = Instant.parse("2026-09-05T12:00:00Z");
    private static final Instant NOW = Instant.parse("2026-09-05T14:00:00Z");
    private static final byte[] DEFAULT_MP3_BYTES = new byte[]{11, 22, 33, 44};
    private static final long DEFAULT_SAMPLES = 51840L;
    private static final int DEFAULT_SAMPLE_RATE = 48000;

    @BeforeEach
    void setUp() {
        useCase = new RegenerateChapterNarrationAudioUseCase(
                segmentRepositoryPort,
                managedVoiceRepositoryPort,
                audioRepositoryPort,
                failureRepositoryPort,
                ttsProviderPort,
                segmentAudioEncoderPort,
                mediaContract,
                cleanupRequestUseCase,
                idGeneratorPort,
                clockPort
        );
    }

    private void mockDefaultEncoding() {
        when(segmentAudioEncoderPort.encode(any(SegmentAudioEncodingRequest.class)))
                .thenAnswer(invocation -> createEncodingResult(DEFAULT_MP3_BYTES, DEFAULT_SAMPLES, DEFAULT_SAMPLE_RATE));
    }

    private SegmentAudioEncodingResult createEncodingResult(byte[] mp3Bytes, long samples, int sampleRate) {
        return createEncodingResult(mp3Bytes, samples, sampleRate, null, null);
    }

    private SegmentAudioEncodingResult createEncodingResult(
            byte[] mp3Bytes,
            long samples,
            int sampleRate,
            AtomicBoolean resourceClosed,
            List<AtomicBoolean> streamClosedFlags
    ) {
        return createEncodingResult(mp3Bytes, samples, sampleRate, resourceClosed, streamClosedFlags, null, null, null);
    }

    private SegmentAudioEncodingResult createEncodingResult(
            byte[] mp3Bytes,
            long samples,
            int sampleRate,
            AtomicBoolean resourceClosed,
            List<AtomicBoolean> streamClosedFlags,
            RuntimeException openStreamException,
            IOException streamCloseException,
            RuntimeException resourceCloseException
    ) {
        SegmentAudioEncodedResource resource = new SegmentAudioEncodedResource() {
            @Override
            public String mimeType() {
                return "audio/mpeg";
            }

            @Override
            public long sizeBytes() {
                return mp3Bytes.length;
            }

            @Override
            public long encodedContributionSamples() {
                return samples;
            }

            @Override
            public int sampleRateHz() {
                return sampleRate;
            }

            @Override
            public InputStream openStream() {
                if (openStreamException != null) {
                    throw openStreamException;
                }
                AtomicBoolean streamClosed = new AtomicBoolean(false);
                if (streamClosedFlags != null) {
                    streamClosedFlags.add(streamClosed);
                }
                return new ByteArrayInputStream(mp3Bytes) {
                    private boolean isClosed = false;

                    @Override
                    public int read() {
                        if (isClosed) {
                            throw new IllegalStateException("Stream is closed");
                        }
                        return super.read();
                    }

                    @Override
                    public int read(byte[] b, int off, int len) {
                        if (isClosed) {
                            throw new IllegalStateException("Stream is closed");
                        }
                        return super.read(b, off, len);
                    }

                    @Override
                    public byte[] readAllBytes() {
                        if (isClosed) {
                            throw new IllegalStateException("Stream is closed");
                        }
                        return super.readAllBytes();
                    }

                    @Override
                    public void close() throws IOException {
                        isClosed = true;
                        streamClosed.set(true);
                        if (streamCloseException != null) {
                            throw streamCloseException;
                        }
                        super.close();
                    }
                };
            }

            @Override
            public void close() {
                if (resourceClosed != null) {
                    resourceClosed.set(true);
                }
                if (resourceCloseException != null) {
                    throw resourceCloseException;
                }
            }
        };
        return new SegmentAudioEncodingResult(resource);
    }

    private ChapterNarrationSegment createCurrentSegment() {
        return ChapterNarrationSegment.create(
                SEGMENT_ID,
                CHAPTER_ID,
                0,
                "Trần Bình An cất bước ra đi.",
                INITIAL_TIME
        );
    }

    private ManagedVoice createActiveVoice(long synthesisRevision) {
        return ManagedVoice.rehydrate(
                VOICE_ID,
                "kiemlai-male-01",
                "Minh Đức",
                "minh-duc",
                ManagedVoiceStatus.ACTIVE,
                1,
                true,
                synthesisRevision,
                INITIAL_TIME,
                INITIAL_TIME
        );
    }

    private ChapterNarrationAudio createExistingAudio(long revision) {
        return ChapterNarrationAudio.create(
                AUDIO_ID,
                SEGMENT_ID,
                VOICE_ID,
                OLD_MEDIA_ASSET_ID,
                revision,
                INITIAL_TIME
        );
    }

    private ChapterNarrationAudio createExistingTimedAudio(long revision) {
        return ChapterNarrationAudio.create(
                AUDIO_ID,
                SEGMENT_ID,
                VOICE_ID,
                OLD_MEDIA_ASSET_ID,
                revision,
                51840L,
                48000,
                INITIAL_TIME
        );
    }

    @Test
    @DisplayName("1. ALREADY_CURRENT: Returns ALREADY_CURRENT when assignment is compatible and has encoded timing")
    void shouldReturnAlreadyCurrentWhenAssignmentIsAlreadyCompatible() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L);
        ChapterNarrationAudio audio = createExistingTimedAudio(2L); // Already revision 2 and timed

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(audio));

        RegenerateChapterNarrationAudioResult result = useCase.execute(SEGMENT_ID, VOICE_ID);

        assertThat(result.assignmentId()).isEqualTo(AUDIO_ID);
        assertThat(result.segmentId()).isEqualTo(SEGMENT_ID);
        assertThat(result.managedVoiceId()).isEqualTo(VOICE_ID);
        assertThat(result.previousMediaAssetId()).isEqualTo(OLD_MEDIA_ASSET_ID);
        assertThat(result.currentMediaAssetId()).isEqualTo(OLD_MEDIA_ASSET_ID);
        assertThat(result.generatedSynthesisRevision()).isEqualTo(2L);
        assertThat(result.outcome()).isEqualTo(RegenerateNarrationAudioOutcome.ALREADY_CURRENT);

        verifyNoInteractions(ttsProviderPort);
        verifyNoInteractions(segmentAudioEncoderPort);
        verifyNoInteractions(mediaContract);
        verifyNoInteractions(cleanupRequestUseCase);
        verify(audioRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("1b. Legacy assignment with matching revision but lacking encoded timing MUST regenerate")
    void shouldRegenerateWhenAssignmentIsLegacyUntimedEvenWithMatchingRevision() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L);
        ChapterNarrationAudio audio = createExistingAudio(2L); // Revision matches 2L, but untimed (null samples/rate)

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(audio));

        byte[] audioBytes = new byte[]{82, 73, 70, 70, 10, 20, 30};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");
        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        mockDefaultEncoding();
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(NEW_MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);
        when(audioRepositoryPort.save(any(ChapterNarrationAudio.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cleanupRequestUseCase.execute(OLD_MEDIA_ASSET_ID, NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET))
                .thenReturn(new RequestNarrationMediaCleanupResult(OLD_MEDIA_ASSET_ID, NarrationMediaCleanupOutcome.IMMEDIATELY_DELETED));

        RegenerateChapterNarrationAudioResult result = useCase.execute(SEGMENT_ID, VOICE_ID);

        assertThat(result.outcome()).isEqualTo(RegenerateNarrationAudioOutcome.REGENERATED);
        assertThat(result.currentMediaAssetId()).isEqualTo(NEW_MEDIA_ASSET_ID);
        assertThat(result.previousMediaAssetId()).isEqualTo(OLD_MEDIA_ASSET_ID);
        assertThat(result.generatedSynthesisRevision()).isEqualTo(2L);

        assertThat(audio.hasEncodedTiming()).isTrue();
        assertThat(audio.getEncodedContributionSamples()).isEqualTo(DEFAULT_SAMPLES);
        assertThat(audio.getEncodedSampleRateHz()).isEqualTo(DEFAULT_SAMPLE_RATE);
    }

    @Test
    @DisplayName("1c. Stale assignment with encoded timing MUST regenerate when voice revision changed")
    void shouldRegenerateWhenAssignmentIsStaleEvenWithEncodedTiming() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L); // Current voice is 2L
        ChapterNarrationAudio audio = createExistingTimedAudio(1L); // Audio is 1L (timed)

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(audio));

        byte[] audioBytes = new byte[]{82, 73, 70, 70, 10, 20, 30};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");
        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        mockDefaultEncoding();
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(NEW_MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);
        when(audioRepositoryPort.save(any(ChapterNarrationAudio.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cleanupRequestUseCase.execute(OLD_MEDIA_ASSET_ID, NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET))
                .thenReturn(new RequestNarrationMediaCleanupResult(OLD_MEDIA_ASSET_ID, NarrationMediaCleanupOutcome.IMMEDIATELY_DELETED));

        RegenerateChapterNarrationAudioResult result = useCase.execute(SEGMENT_ID, VOICE_ID);

        assertThat(result.outcome()).isEqualTo(RegenerateNarrationAudioOutcome.REGENERATED);
        assertThat(result.currentMediaAssetId()).isEqualTo(NEW_MEDIA_ASSET_ID);
        assertThat(result.previousMediaAssetId()).isEqualTo(OLD_MEDIA_ASSET_ID);
        assertThat(result.generatedSynthesisRevision()).isEqualTo(2L);
    }

    @Test
    @DisplayName("2. TTS failure: Old assignment and old Media asset remain untouched with no cleanup request")
    void shouldLeaveOldAssignmentUntouchedWhenTtsFails() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L);
        ChapterNarrationAudio audio = createExistingAudio(1L);

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(audio));

        RuntimeException ttsEx = new RuntimeException("TTS service unavailable");
        when(ttsProviderPort.synthesize(any())).thenThrow(ttsEx);

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(ttsEx);

        assertThat(audio.getMediaAssetId()).isEqualTo(OLD_MEDIA_ASSET_ID);
        assertThat(audio.getGeneratedSynthesisRevision()).isEqualTo(1L);

        verifyNoInteractions(segmentAudioEncoderPort);
        verifyNoInteractions(mediaContract);
        verifyNoInteractions(cleanupRequestUseCase);
        verify(audioRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("3. Media upload failure: Old assignment remains untouched, no save occurs, and no cleanup requested")
    void shouldLeaveOldAssignmentUntouchedWhenMediaUploadFails() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L);
        ChapterNarrationAudio audio = createExistingAudio(1L);
        byte[] audioBytes = new byte[]{1, 2, 3};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(audio));

        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        mockDefaultEncoding();

        RuntimeException mediaEx = new RuntimeException("Media storage network error");
        when(mediaContract.uploadAsset(any())).thenThrow(mediaEx);

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(mediaEx);

        assertThat(audio.getMediaAssetId()).isEqualTo(OLD_MEDIA_ASSET_ID);
        assertThat(audio.getGeneratedSynthesisRevision()).isEqualTo(1L);

        verify(audioRepositoryPort, never()).save(any());
        verify(mediaContract, never()).delete(any());
        verifyNoInteractions(cleanupRequestUseCase);
    }

    @Test
    @DisplayName("4. Normal persistence failure: Restores OLD in-memory state, requests cleanup for NEW asset with UNREFERENCED_GENERATED_ASSET, leaves OLD untouched, propagates original error")
    void shouldRestoreOldStateAndCleanupNewAssetWhenPersistenceFails() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L);
        ChapterNarrationAudio audio = createExistingAudio(1L);
        byte[] audioBytes = new byte[]{1, 2, 3};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(audio));

        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        mockDefaultEncoding();
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(NEW_MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);

        RuntimeException dbEx = new RuntimeException("DB transaction deadlock");
        when(audioRepositoryPort.save(any())).thenThrow(dbEx);
        when(cleanupRequestUseCase.execute(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET))
                .thenReturn(new RequestNarrationMediaCleanupResult(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupOutcome.IMMEDIATELY_DELETED));

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(dbEx);

        // In-memory assignment state is reverted to previous state
        assertThat(audio.getMediaAssetId()).isEqualTo(OLD_MEDIA_ASSET_ID);
        assertThat(audio.getGeneratedSynthesisRevision()).isEqualTo(1L);
        assertThat(audio.getEncodedContributionSamples()).isNull();
        assertThat(audio.getEncodedSampleRateHz()).isNull();
        assertThat(audio.getUpdatedAt()).isEqualTo(INITIAL_TIME);

        // Verify InOrder: upload -> save attempt -> cleanup request for NEW
        InOrder inOrder = inOrder(mediaContract, audioRepositoryPort, cleanupRequestUseCase);
        inOrder.verify(mediaContract).uploadAsset(any(UploadMediaAssetRequestDTO.class));
        inOrder.verify(audioRepositoryPort).save(any(ChapterNarrationAudio.class));
        inOrder.verify(cleanupRequestUseCase).execute(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);

        // OLD asset is NEVER cleanup-targeted
        verify(cleanupRequestUseCase, never()).execute(OLD_MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        verify(cleanupRequestUseCase, never()).execute(OLD_MEDIA_ASSET_ID, NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET);
        verify(mediaContract, never()).delete(any());
    }

    @Test
    @DisplayName("5. Persistence fail + NEW cleanup ENQUEUED_FOR_RETRY propagates original persistence exception")
    void shouldPropagatePersistenceExceptionWhenNewCleanupEnqueuedForRetry() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L);
        ChapterNarrationAudio audio = createExistingAudio(1L);
        byte[] audioBytes = new byte[]{1, 2, 3};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(audio));

        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        mockDefaultEncoding();
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(NEW_MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);

        RuntimeException dbEx = new RuntimeException("DB transaction deadlock");
        when(audioRepositoryPort.save(any())).thenThrow(dbEx);
        when(cleanupRequestUseCase.execute(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET))
                .thenReturn(new RequestNarrationMediaCleanupResult(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupOutcome.ENQUEUED_FOR_RETRY));

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(dbEx);

        verify(cleanupRequestUseCase).execute(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        verify(cleanupRequestUseCase, never()).execute(OLD_MEDIA_ASSET_ID, NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET);
        verify(mediaContract, never()).delete(any());
    }

    @Test
    @DisplayName("6. Persistence fail + NEW cleanup TOTAL FAILURE: Persistence exception remains primary, cleanup exception suppressed, OLD preserved")
    void shouldAttachSuppressedExceptionWhenNewCleanupFailsCompletelyOnPersistenceFailure() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L);
        ChapterNarrationAudio audio = createExistingAudio(1L);
        byte[] audioBytes = new byte[]{1, 2, 3};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(audio));

        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        mockDefaultEncoding();
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(NEW_MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);

        RuntimeException dbEx = new RuntimeException("DB connection dropped");
        RuntimeException cleanupEx = new RuntimeException("Cleanup service unavailable");

        when(audioRepositoryPort.save(any())).thenThrow(dbEx);
        doThrow(cleanupEx).when(cleanupRequestUseCase).execute(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(dbEx)
                .hasSuppressedException(cleanupEx);

        // OLD asset untouched and preserved
        assertThat(audio.getMediaAssetId()).isEqualTo(OLD_MEDIA_ASSET_ID);
        assertThat(audio.getGeneratedSynthesisRevision()).isEqualTo(1L);
        verify(cleanupRequestUseCase, never()).execute(OLD_MEDIA_ASSET_ID, NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET);
        verify(mediaContract, never()).delete(any());
    }

    @Test
    @DisplayName("7. Optimistic race + compatible winner + cleanup success: NEW loser requested for cleanup once, winner/OLD untouched, returns REGENERATED, no diagnostics")
    void shouldHandleOptimisticLockConflictWhenCompatibleWinnerExists() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L);
        ChapterNarrationAudio initialStaleAudio = createExistingAudio(1L);

        UUID winningMediaAssetId = UUID.fromString("90000000-0000-0000-0000-000000000009");
        ChapterNarrationAudio winningAudio = ChapterNarrationAudio.rehydrate(
                AUDIO_ID, SEGMENT_ID, VOICE_ID, winningMediaAssetId, 2L, DEFAULT_SAMPLES, DEFAULT_SAMPLE_RATE, 1L, NOW, NOW
        );

        byte[] audioBytes = new byte[]{1, 2, 3};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(initialStaleAudio))
                .thenReturn(Optional.of(winningAudio));

        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        mockDefaultEncoding();
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(NEW_MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);

        ObjectOptimisticLockingFailureException optLockEx =
                new ObjectOptimisticLockingFailureException(ChapterNarrationAudio.class, AUDIO_ID);
        when(audioRepositoryPort.save(any())).thenThrow(optLockEx);
        when(cleanupRequestUseCase.execute(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET))
                .thenReturn(new RequestNarrationMediaCleanupResult(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupOutcome.IMMEDIATELY_DELETED));

        RegenerateChapterNarrationAudioResult result = useCase.execute(SEGMENT_ID, VOICE_ID);

        // 1. Loser's NEW media requested for cleanup exactly once
        verify(cleanupRequestUseCase, times(1)).execute(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        // 2. OLD media NOT cleanup-targeted by loser
        verify(cleanupRequestUseCase, never()).execute(OLD_MEDIA_ASSET_ID, NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET);
        // 3. Winner's media NOT cleanup-targeted
        verify(cleanupRequestUseCase, never()).execute(winningMediaAssetId, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        verify(cleanupRequestUseCase, never()).execute(winningMediaAssetId, NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET);
        verify(mediaContract, never()).delete(any());

        // 4. Result outcome is REGENERATED using winner state
        assertThat(result.outcome()).isEqualTo(RegenerateNarrationAudioOutcome.REGENERATED);
        assertThat(result.assignmentId()).isEqualTo(AUDIO_ID);
        assertThat(result.previousMediaAssetId()).isEqualTo(OLD_MEDIA_ASSET_ID);
        assertThat(result.currentMediaAssetId()).isEqualTo(winningMediaAssetId);
        assertThat(result.generatedSynthesisRevision()).isEqualTo(2L);

        // 5. Failure diagnostic NOT recorded and NOT deleted on benign loser path
        verify(failureRepositoryPort, never()).save(any());
        verify(failureRepositoryPort, never()).deleteSupersededBySuccessfulRevision(any(), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("8. Optimistic race + compatible winner + cleanup TOTAL FAILURE: Cleanup exception propagates as primary, optimistic exception suppressed, no diagnostic recorded")
    void shouldPropagateCleanupExceptionWhenCleanupFailsCompletelyOnOptimisticRaceWithCompatibleWinner() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L);
        ChapterNarrationAudio initialStaleAudio = createExistingAudio(1L);

        UUID winningMediaAssetId = UUID.fromString("90000000-0000-0000-0000-000000000009");
        ChapterNarrationAudio winningAudio = ChapterNarrationAudio.rehydrate(
                AUDIO_ID, SEGMENT_ID, VOICE_ID, winningMediaAssetId, 2L, DEFAULT_SAMPLES, DEFAULT_SAMPLE_RATE, 1L, NOW, NOW
        );

        byte[] audioBytes = new byte[]{1, 2, 3};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(initialStaleAudio))
                .thenReturn(Optional.of(winningAudio));

        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        mockDefaultEncoding();
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(NEW_MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);

        ObjectOptimisticLockingFailureException optLockEx =
                new ObjectOptimisticLockingFailureException(ChapterNarrationAudio.class, AUDIO_ID);
        when(audioRepositoryPort.save(any())).thenThrow(optLockEx);

        RuntimeException cleanupFailureEx = new RuntimeException("Cleanup completely failed on optimistic race");
        doThrow(cleanupFailureEx).when(cleanupRequestUseCase).execute(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(cleanupFailureEx)
                .hasSuppressedException(optLockEx);

        // No misleading ASSIGNMENT_PERSISTENCE diagnostic recorded for winning audio
        verify(failureRepositoryPort, never()).save(any());
        verify(mediaContract, never()).delete(any());
    }

    @Test
    @DisplayName("9. Optimistic race with winner reload failure + cleanup success propagates optimistic exception, suppresses lookup exception, and records diagnostic")
    void shouldPropagateOptimisticExceptionAndSuppressLookupExceptionWhenWinnerReloadThrowsAfterSuccessfulCleanup() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L);
        ChapterNarrationAudio initialStaleAudio = createExistingAudio(1L);
        byte[] audioBytes = new byte[]{1, 2, 3};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));

        RuntimeException winnerLookupEx = new RuntimeException("DB error during winner reload query");
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(initialStaleAudio))
                .thenThrow(winnerLookupEx);

        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        mockDefaultEncoding();
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(NEW_MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);
        when(idGeneratorPort.generate()).thenReturn(FAILURE_ID);

        ObjectOptimisticLockingFailureException optLockEx =
                new ObjectOptimisticLockingFailureException(ChapterNarrationAudio.class, AUDIO_ID);
        when(audioRepositoryPort.save(any())).thenThrow(optLockEx);
        when(cleanupRequestUseCase.execute(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET))
                .thenReturn(new RequestNarrationMediaCleanupResult(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupOutcome.IMMEDIATELY_DELETED));

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(optLockEx)
                .hasSuppressedException(winnerLookupEx);

        // Loser cleanup requested exactly once
        verify(cleanupRequestUseCase, times(1)).execute(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        verify(mediaContract, never()).delete(any());

        // Diagnostic recorded with original persistence error
        verify(failureRepositoryPort).findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID);
    }

    @Test
    @DisplayName("10. Optimistic race with winner reload failure + cleanup TOTAL FAILURE propagates optimistic exception, suppresses both cleanup and lookup exceptions, and records diagnostic")
    void shouldPropagateOptimisticExceptionAndSuppressBothCleanupAndLookupExceptionsWhenBothFail() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L);
        ChapterNarrationAudio initialStaleAudio = createExistingAudio(1L);
        byte[] audioBytes = new byte[]{1, 2, 3};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));

        RuntimeException winnerLookupEx = new RuntimeException("DB error during winner reload query");
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(initialStaleAudio))
                .thenThrow(winnerLookupEx);

        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        mockDefaultEncoding();
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(NEW_MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);
        when(idGeneratorPort.generate()).thenReturn(FAILURE_ID);

        ObjectOptimisticLockingFailureException optLockEx =
                new ObjectOptimisticLockingFailureException(ChapterNarrationAudio.class, AUDIO_ID);
        when(audioRepositoryPort.save(any())).thenThrow(optLockEx);

        RuntimeException cleanupFailureEx = new RuntimeException("Cleanup completely failed");
        doThrow(cleanupFailureEx).when(cleanupRequestUseCase).execute(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(optLockEx)
                .hasSuppressedException(winnerLookupEx)
                .hasSuppressedException(cleanupFailureEx);

        // Loser cleanup requested exactly once
        verify(cleanupRequestUseCase, times(1)).execute(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        verify(mediaContract, never()).delete(any());

        // Diagnostic recorded with original persistence error
        verify(failureRepositoryPort).findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID);
    }

    @Test
    @DisplayName("11. Successful regeneration: Encodes to canonical MP3, uploads MP3, persists timing metadata, OLD cleaned up with SUPERSEDED_REGENERATION_ASSET")
    void shouldRegenerateStaleAssignmentSuccessfullyWithDurableOldCleanup() throws IOException {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L); // Current voice revision is 2
        ChapterNarrationAudio audio = createExistingAudio(1L); // Audio was generated at revision 1

        byte[] audioBytes = new byte[]{82, 73, 70, 70, 10, 20, 30};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(audio));

        when(ttsProviderPort.synthesize(new TtsSynthesisCommand("Trần Bình An cất bước ra đi.", "minh-duc")))
                .thenReturn(ttsResult);

        byte[] mp3Bytes = new byte[]{1, 2, 3, 4, 5};
        AtomicReference<byte[]> uploadedBytes = new AtomicReference<>();
        when(segmentAudioEncoderPort.encode(any(SegmentAudioEncodingRequest.class)))
                .thenAnswer(invocation -> createEncodingResult(mp3Bytes, 51840L, 48000));

        when(mediaContract.uploadAsset(any(UploadMediaAssetRequestDTO.class))).thenAnswer(invocation -> {
            UploadMediaAssetRequestDTO request = invocation.getArgument(0);
            uploadedBytes.set(request.content().readAllBytes());
            return new UploadMediaAssetResponseDTO(NEW_MEDIA_ASSET_ID);
        });

        when(clockPort.now()).thenReturn(NOW);
        when(audioRepositoryPort.save(any(ChapterNarrationAudio.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cleanupRequestUseCase.execute(OLD_MEDIA_ASSET_ID, NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET))
                .thenReturn(new RequestNarrationMediaCleanupResult(OLD_MEDIA_ASSET_ID, NarrationMediaCleanupOutcome.IMMEDIATELY_DELETED));

        RegenerateChapterNarrationAudioResult result = useCase.execute(new RegenerateChapterNarrationAudioCommand(SEGMENT_ID, VOICE_ID));

        assertThat(result.assignmentId()).isEqualTo(AUDIO_ID);
        assertThat(result.segmentId()).isEqualTo(SEGMENT_ID);
        assertThat(result.managedVoiceId()).isEqualTo(VOICE_ID);
        assertThat(result.previousMediaAssetId()).isEqualTo(OLD_MEDIA_ASSET_ID);
        assertThat(result.currentMediaAssetId()).isEqualTo(NEW_MEDIA_ASSET_ID);
        assertThat(result.generatedSynthesisRevision()).isEqualTo(2L);
        assertThat(result.outcome()).isEqualTo(RegenerateNarrationAudioOutcome.REGENERATED);

        // Verify TTS call
        verify(ttsProviderPort).synthesize(new TtsSynthesisCommand("Trần Bình An cất bước ra đi.", "minh-duc"));

        // Verify encoder call
        ArgumentCaptor<SegmentAudioEncodingRequest> encodeCaptor = ArgumentCaptor.forClass(SegmentAudioEncodingRequest.class);
        verify(segmentAudioEncoderPort).encode(encodeCaptor.capture());
        SegmentAudioEncodingRequest capturedEncode = encodeCaptor.getValue();
        assertThat(capturedEncode.mimeType()).isEqualTo("audio/wav");
        assertThat(capturedEncode.openStream().readAllBytes()).isEqualTo(NarrationWavBoundaryNormalizer.normalize(audioBytes, "audio/wav"));

        // Verify Media upload payload
        ArgumentCaptor<UploadMediaAssetRequestDTO> uploadCaptor = ArgumentCaptor.forClass(UploadMediaAssetRequestDTO.class);
        verify(mediaContract).uploadAsset(uploadCaptor.capture());
        UploadMediaAssetRequestDTO capturedUpload = uploadCaptor.getValue();
        assertThat(capturedUpload.sizeBytes()).isEqualTo(mp3Bytes.length);
        assertThat(capturedUpload.mimeType()).isEqualTo("audio/mpeg");
        assertThat(capturedUpload.mediaType()).isEqualTo(MediaTypeDTO.AUDIO);
        assertThat(capturedUpload.visibility()).isEqualTo(MediaVisibilityDTO.PUBLIC);
        assertThat(capturedUpload.originalFilename()).isEqualTo("segment-" + SEGMENT_ID + ".mp3");
        assertThat(uploadedBytes.get()).isEqualTo(mp3Bytes);

        // Verify persistence of the same assignment identity with timing
        ArgumentCaptor<ChapterNarrationAudio> audioCaptor = ArgumentCaptor.forClass(ChapterNarrationAudio.class);
        verify(audioRepositoryPort).save(audioCaptor.capture());
        ChapterNarrationAudio capturedAudio = audioCaptor.getValue();
        assertThat(capturedAudio.getId()).isEqualTo(AUDIO_ID);
        assertThat(capturedAudio.getMediaAssetId()).isEqualTo(NEW_MEDIA_ASSET_ID);
        assertThat(capturedAudio.getGeneratedSynthesisRevision()).isEqualTo(2L);
        assertThat(capturedAudio.getEncodedContributionSamples()).isEqualTo(51840L);
        assertThat(capturedAudio.getEncodedSampleRateHz()).isEqualTo(48000);
        assertThat(capturedAudio.getUpdatedAt()).isEqualTo(NOW);

        // Verify Mockito InOrder: Media upload -> assignment save -> OLD cleanup request
        InOrder inOrder = inOrder(mediaContract, audioRepositoryPort, cleanupRequestUseCase);
        inOrder.verify(mediaContract).uploadAsset(any(UploadMediaAssetRequestDTO.class));
        inOrder.verify(audioRepositoryPort).save(any(ChapterNarrationAudio.class));
        inOrder.verify(cleanupRequestUseCase).execute(OLD_MEDIA_ASSET_ID, NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET);

        // NEW asset is NEVER cleanup-targeted
        verify(cleanupRequestUseCase, never()).execute(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        verify(cleanupRequestUseCase, never()).execute(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET);
        verify(mediaContract, never()).delete(any());

        // Verify failure cleared on success with generated revision
        verify(failureRepositoryPort).deleteSupersededBySuccessfulRevision(SEGMENT_ID, VOICE_ID, 2L);
    }

    @Test
    @DisplayName("12. Successful switch + OLD cleanup ENQUEUED_FOR_RETRY still returns REGENERATED")
    void shouldReturnRegeneratedWhenOldCleanupIsEnqueuedForRetry() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L);
        ChapterNarrationAudio audio = createExistingAudio(1L);
        byte[] audioBytes = new byte[]{1, 2, 3};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(audio));

        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        mockDefaultEncoding();
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(NEW_MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);
        when(audioRepositoryPort.save(any(ChapterNarrationAudio.class))).thenAnswer(invocation -> invocation.getArgument(0));

        when(cleanupRequestUseCase.execute(OLD_MEDIA_ASSET_ID, NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET))
                .thenReturn(new RequestNarrationMediaCleanupResult(OLD_MEDIA_ASSET_ID, NarrationMediaCleanupOutcome.ENQUEUED_FOR_RETRY));

        RegenerateChapterNarrationAudioResult result = useCase.execute(SEGMENT_ID, VOICE_ID);

        assertThat(result.outcome()).isEqualTo(RegenerateNarrationAudioOutcome.REGENERATED);
        assertThat(result.currentMediaAssetId()).isEqualTo(NEW_MEDIA_ASSET_ID);
        assertThat(result.previousMediaAssetId()).isEqualTo(OLD_MEDIA_ASSET_ID);

        verify(cleanupRequestUseCase).execute(OLD_MEDIA_ASSET_ID, NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET);
        verify(mediaContract, never()).delete(any());
    }

    @Test
    @DisplayName("13. Successful switch + OLD cleanup TOTAL FAILURE: Cleanup exception propagates, assignment remains NEW/current, no rollback, no ASSIGNMENT_PERSISTENCE diagnostic")
    void shouldPropagateCleanupExceptionWhenOldMediaCleanupFailsCompletelyWithoutRollbackOrPersistenceDiagnostic() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L);
        ChapterNarrationAudio audio = createExistingAudio(1L);
        byte[] audioBytes = new byte[]{1, 2, 3};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(audio));

        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        mockDefaultEncoding();
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(NEW_MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);
        when(audioRepositoryPort.save(any(ChapterNarrationAudio.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Old media cleanup throws total failure
        NarrationMediaCleanupRequestException oldCleanupEx =
                new NarrationMediaCleanupRequestException(OLD_MEDIA_ASSET_ID, "Immediate deletion failed and enqueue failed", new RuntimeException("Media error"));
        doThrow(oldCleanupEx).when(cleanupRequestUseCase).execute(OLD_MEDIA_ASSET_ID, NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET);

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(oldCleanupEx);

        // Assignment in memory was updated to NEW and saved (NOT rolled back to OLD)
        assertThat(audio.getMediaAssetId()).isEqualTo(NEW_MEDIA_ASSET_ID);
        assertThat(audio.getGeneratedSynthesisRevision()).isEqualTo(2L);

        // No ASSIGNMENT_PERSISTENCE diagnostic recorded since save succeeded
        verify(failureRepositoryPort, never()).save(any());
        verify(mediaContract, never()).delete(any());
    }

    @Test
    @DisplayName("14. Successful switch + subsequent retry: Returns ALREADY_CURRENT without TTS, upload, or cleanup")
    void shouldReturnAlreadyCurrentOnSubsequentRetryAfterSuccessfulPersistence() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L);
        // After successful persistence from earlier attempt, audio has newMediaAssetId, revision 2, and encoded timing
        ChapterNarrationAudio persistedAudio = ChapterNarrationAudio.create(
                AUDIO_ID, SEGMENT_ID, VOICE_ID, NEW_MEDIA_ASSET_ID, 2L, DEFAULT_SAMPLES, DEFAULT_SAMPLE_RATE, NOW
        );

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(persistedAudio));

        RegenerateChapterNarrationAudioResult result = useCase.execute(SEGMENT_ID, VOICE_ID);

        assertThat(result.outcome()).isEqualTo(RegenerateNarrationAudioOutcome.ALREADY_CURRENT);
        assertThat(result.assignmentId()).isEqualTo(AUDIO_ID);
        assertThat(result.currentMediaAssetId()).isEqualTo(NEW_MEDIA_ASSET_ID);
        assertThat(result.previousMediaAssetId()).isEqualTo(NEW_MEDIA_ASSET_ID);
        assertThat(result.generatedSynthesisRevision()).isEqualTo(2L);

        verifyNoInteractions(ttsProviderPort);
        verifyNoInteractions(segmentAudioEncoderPort);
        verifyNoInteractions(mediaContract);
        verifyNoInteractions(cleanupRequestUseCase);
        verify(audioRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("15. Missing assignment rejected: Throws ChapterNarrationAudioNotFoundException")
    void shouldThrowWhenAudioAssignmentNotFound() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(1L);

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isInstanceOf(ChapterNarrationAudioNotFoundException.class);

        verifyNoInteractions(ttsProviderPort);
        verifyNoInteractions(segmentAudioEncoderPort);
        verifyNoInteractions(mediaContract);
        verifyNoInteractions(cleanupRequestUseCase);
        verify(audioRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("16. Retired segment rejected: Throws ChapterNarrationSegmentInvalidStateException")
    void shouldThrowWhenSegmentIsRetired() {
        ChapterNarrationSegment retiredSegment = ChapterNarrationSegment.rehydrate(
                SEGMENT_ID,
                CHAPTER_ID,
                0,
                "Văn bản cũ.",
                "Văn bản cũ.".length(),
                NarrationTextSegment.computeSha256("Văn bản cũ."),
                ChapterNarrationSegmentStatus.RETIRED,
                INITIAL_TIME,
                INITIAL_TIME
        );

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(retiredSegment));

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isInstanceOf(ChapterNarrationSegmentInvalidStateException.class)
                .hasMessageContaining("CURRENT");

        verifyNoInteractions(managedVoiceRepositoryPort);
        verifyNoInteractions(ttsProviderPort);
        verifyNoInteractions(segmentAudioEncoderPort);
        verifyNoInteractions(mediaContract);
        verifyNoInteractions(cleanupRequestUseCase);
    }

    @Test
    @DisplayName("17. Disabled voice rejected: Throws ManagedVoiceInvalidStateException")
    void shouldThrowWhenVoiceIsDisabled() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice disabledVoice = ManagedVoice.rehydrate(
                VOICE_ID,
                "kiemlai-male-01",
                "Minh Đức",
                "minh-duc",
                ManagedVoiceStatus.DISABLED,
                1,
                false,
                1L,
                INITIAL_TIME,
                INITIAL_TIME
        );

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(disabledVoice));

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isInstanceOf(ManagedVoiceInvalidStateException.class)
                .hasMessageContaining("ACTIVE");

        verifyNoInteractions(ttsProviderPort);
        verifyNoInteractions(segmentAudioEncoderPort);
        verifyNoInteractions(mediaContract);
        verifyNoInteractions(cleanupRequestUseCase);
    }

    @Test
    @DisplayName("18. Segment not found rejected: Throws ChapterNarrationSegmentNotFoundException")
    void shouldThrowWhenSegmentNotFound() {
        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isInstanceOf(ChapterNarrationSegmentNotFoundException.class);

        verifyNoInteractions(managedVoiceRepositoryPort);
        verifyNoInteractions(ttsProviderPort);
        verifyNoInteractions(segmentAudioEncoderPort);
        verifyNoInteractions(mediaContract);
        verifyNoInteractions(cleanupRequestUseCase);
    }

    @Test
    @DisplayName("19. Voice not found rejected: Throws ManagedVoiceNotFoundException")
    void shouldThrowWhenVoiceNotFound() {
        ChapterNarrationSegment segment = createCurrentSegment();
        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isInstanceOf(ManagedVoiceNotFoundException.class);

        verifyNoInteractions(ttsProviderPort);
        verifyNoInteractions(segmentAudioEncoderPort);
        verifyNoInteractions(mediaContract);
        verifyNoInteractions(cleanupRequestUseCase);
    }

    @Test
    @DisplayName("20. Assignment ID remains stable across regeneration")
    void shouldPreserveAssignmentIdAcrossRegeneration() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(3L);
        ChapterNarrationAudio audio = createExistingAudio(1L);

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(audio));

        when(ttsProviderPort.synthesize(any())).thenReturn(new TtsSynthesisResult(new byte[]{1, 2}, "audio/mpeg"));
        mockDefaultEncoding();
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(NEW_MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);
        when(audioRepositoryPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cleanupRequestUseCase.execute(OLD_MEDIA_ASSET_ID, NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET))
                .thenReturn(new RequestNarrationMediaCleanupResult(OLD_MEDIA_ASSET_ID, NarrationMediaCleanupOutcome.IMMEDIATELY_DELETED));

        RegenerateChapterNarrationAudioResult result = useCase.execute(SEGMENT_ID, VOICE_ID);

        assertThat(result.assignmentId()).isEqualTo(AUDIO_ID);
        assertThat(result.outcome()).isEqualTo(RegenerateNarrationAudioOutcome.REGENERATED);
    }

    @Test
    @DisplayName("21. Validates null inputs")
    void shouldRejectNullInputs() {
        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> useCase.execute(null, VOICE_ID))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("22. Records failure record with REGENERATION operation when TTS synthesis fails during regeneration")
    void shouldRecordFailureWhenTtsSynthesisFailsDuringRegeneration() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L);
        ChapterNarrationAudio audio = createExistingAudio(1L);

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(audio));
        when(failureRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty());
        when(clockPort.now()).thenReturn(NOW);
        when(idGeneratorPort.generate()).thenReturn(FAILURE_ID);

        RuntimeException ttsEx = new RuntimeException("TTS rate limit exceeded");
        when(ttsProviderPort.synthesize(any())).thenThrow(ttsEx);

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(ttsEx);

        verifyNoInteractions(segmentAudioEncoderPort);

        ArgumentCaptor<ChapterNarrationAudioFailure> failureCaptor = ArgumentCaptor.forClass(ChapterNarrationAudioFailure.class);
        verify(failureRepositoryPort).save(failureCaptor.capture());
        ChapterNarrationAudioFailure failure = failureCaptor.getValue();

        assertThat(failure.getId()).isEqualTo(FAILURE_ID);
        assertThat(failure.getSegmentId()).isEqualTo(SEGMENT_ID);
        assertThat(failure.getManagedVoiceId()).isEqualTo(VOICE_ID);
        assertThat(failure.getOperation()).isEqualTo(NarrationAudioOperation.REGENERATION);
        assertThat(failure.getStage()).isEqualTo(NarrationAudioFailureStage.TTS_SYNTHESIS);
        assertThat(failure.getAttemptedSynthesisRevision()).isEqualTo(2L);
        assertThat(failure.getFailureCount()).isEqualTo(1);
        assertThat(failure.getErrorType()).isEqualTo("RuntimeException");
        assertThat(failure.getErrorMessage()).isEqualTo("Narration TTS synthesis failed.");
        assertThat(failure.getFirstFailedAt()).isEqualTo(NOW);
        assertThat(failure.getLastFailedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("23. Updates failure record when Media upload fails during regeneration")
    void shouldUpdateFailureWhenMediaUploadFailsDuringRegeneration() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(3L);
        ChapterNarrationAudio audio = createExistingAudio(1L);
        byte[] audioBytes = new byte[]{1, 2, 3};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        ChapterNarrationAudioFailure existingFailure = ChapterNarrationAudioFailure.create(
                FAILURE_ID,
                SEGMENT_ID,
                VOICE_ID,
                NarrationAudioOperation.REGENERATION,
                NarrationAudioFailureStage.TTS_SYNTHESIS,
                2L,
                "RuntimeException",
                Instant.parse("2026-09-05T10:00:00Z")
        );

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(audio));
        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        mockDefaultEncoding();
        when(failureRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(existingFailure));
        when(clockPort.now()).thenReturn(NOW);

        RuntimeException mediaEx = new RuntimeException("S3 bucket access denied");
        when(mediaContract.uploadAsset(any())).thenThrow(mediaEx);

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(mediaEx);

        verify(failureRepositoryPort).save(existingFailure);
        assertThat(existingFailure.getFailureCount()).isEqualTo(2);
        assertThat(existingFailure.getOperation()).isEqualTo(NarrationAudioOperation.REGENERATION);
        assertThat(existingFailure.getStage()).isEqualTo(NarrationAudioFailureStage.MEDIA_UPLOAD);
        assertThat(existingFailure.getAttemptedSynthesisRevision()).isEqualTo(3L);
        assertThat(existingFailure.getErrorMessage()).isEqualTo("Narration audio media upload failed.");
    }

    @Test
    @DisplayName("24. Successful regeneration completes even when clearing failure diagnostics throws an exception")
    void shouldCompleteRegenerationWhenClearingFailureThrowsException() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L);
        ChapterNarrationAudio audio = createExistingAudio(1L);
        byte[] audioBytes = new byte[]{1, 2, 3};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(audio));
        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        mockDefaultEncoding();
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(NEW_MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);
        when(audioRepositoryPort.save(any(ChapterNarrationAudio.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cleanupRequestUseCase.execute(OLD_MEDIA_ASSET_ID, NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET))
                .thenReturn(new RequestNarrationMediaCleanupResult(OLD_MEDIA_ASSET_ID, NarrationMediaCleanupOutcome.IMMEDIATELY_DELETED));

        // Deleting failure diagnostic throws
        doThrow(new RuntimeException("Failure repo DB unreachable during cleanup"))
                .when(failureRepositoryPort).deleteSupersededBySuccessfulRevision(SEGMENT_ID, VOICE_ID, 2L);

        RegenerateChapterNarrationAudioResult result = useCase.execute(SEGMENT_ID, VOICE_ID);

        assertThat(result.outcome()).isEqualTo(RegenerateNarrationAudioOutcome.REGENERATED);
        assertThat(result.assignmentId()).isEqualTo(AUDIO_ID);
        assertThat(result.currentMediaAssetId()).isEqualTo(NEW_MEDIA_ASSET_ID);
        assertThat(result.previousMediaAssetId()).isEqualTo(OLD_MEDIA_ASSET_ID);

        // NEW Media asset is NOT cleanup-targeted
        verify(cleanupRequestUseCase, never()).execute(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        // OLD Media asset is cleanup-targeted
        verify(cleanupRequestUseCase).execute(OLD_MEDIA_ASSET_ID, NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET);
        verify(mediaContract, never()).delete(any());
    }

    @Test
    @DisplayName("25. Clock failure during regeneration diagnostic recording does not mask primary TTS exception and is attached as suppressed")
    void shouldNotMaskPrimaryTtsExceptionWhenClockFailsDuringRegenerationDiagnosticRecording() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L);
        ChapterNarrationAudio audio = createExistingAudio(1L);

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(audio));

        RuntimeException ttsEx = new RuntimeException("TTS synthesis error during regeneration");
        when(ttsProviderPort.synthesize(any())).thenThrow(ttsEx);

        RuntimeException clockEx = new RuntimeException("System time synchronization failed");
        when(clockPort.now()).thenThrow(clockEx);

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(ttsEx)
                .hasSuppressedException(clockEx);

        verifyNoInteractions(segmentAudioEncoderPort);
    }

    @Test
    @DisplayName("26. Regeneration optimistic-lock race without compatible winner: Loser's NEW media requested for cleanup, OLD media untouched, error propagates")
    void shouldPropagateOptimisticLockConflictWhenNoCompatibleWinnerExists() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(3L); // target revision is 3
        ChapterNarrationAudio initialStaleAudio = createExistingAudio(1L);

        UUID incompatibleMediaAssetId = UUID.fromString("90000000-0000-0000-0000-000000000010");
        ChapterNarrationAudio incompatibleAudio = ChapterNarrationAudio.rehydrate(
                AUDIO_ID, SEGMENT_ID, VOICE_ID, incompatibleMediaAssetId, 2L, DEFAULT_SAMPLES, DEFAULT_SAMPLE_RATE, 1L, NOW, NOW // revision 2 != 3
        );

        byte[] audioBytes = new byte[]{1, 2, 3};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(initialStaleAudio))
                .thenReturn(Optional.of(incompatibleAudio));

        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        mockDefaultEncoding();
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(NEW_MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);

        ObjectOptimisticLockingFailureException optLockEx =
                new ObjectOptimisticLockingFailureException(ChapterNarrationAudio.class, AUDIO_ID);
        when(audioRepositoryPort.save(any())).thenThrow(optLockEx);
        when(cleanupRequestUseCase.execute(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET))
                .thenReturn(new RequestNarrationMediaCleanupResult(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupOutcome.IMMEDIATELY_DELETED));

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(optLockEx);

        // Loser's NEW media requested for cleanup
        verify(cleanupRequestUseCase).execute(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        // OLD media NOT cleanup-targeted
        verify(cleanupRequestUseCase, never()).execute(OLD_MEDIA_ASSET_ID, NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET);
        verify(mediaContract, never()).delete(any());
        // Diagnostics recorded
        verify(failureRepositoryPort).findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID);
    }

    @Test
    @DisplayName("27. Regeneration handles jakarta.persistence.OptimisticLockException similarly")
    void shouldHandleJakartaPersistenceOptimisticLockException() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L);
        ChapterNarrationAudio initialStaleAudio = createExistingAudio(1L);

        UUID winningMediaAssetId = UUID.fromString("90000000-0000-0000-0000-000000000011");
        ChapterNarrationAudio winningAudio = ChapterNarrationAudio.rehydrate(
                AUDIO_ID, SEGMENT_ID, VOICE_ID, winningMediaAssetId, 2L, DEFAULT_SAMPLES, DEFAULT_SAMPLE_RATE, 1L, NOW, NOW
        );

        byte[] audioBytes = new byte[]{1, 2, 3};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(initialStaleAudio))
                .thenReturn(Optional.of(winningAudio));

        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        mockDefaultEncoding();
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(NEW_MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);

        jakarta.persistence.OptimisticLockException optLockEx =
                new jakarta.persistence.OptimisticLockException("Stale JPA version");
        when(audioRepositoryPort.save(any())).thenThrow(optLockEx);
        when(cleanupRequestUseCase.execute(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET))
                .thenReturn(new RequestNarrationMediaCleanupResult(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupOutcome.IMMEDIATELY_DELETED));

        RegenerateChapterNarrationAudioResult result = useCase.execute(SEGMENT_ID, VOICE_ID);

        verify(cleanupRequestUseCase).execute(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        verify(cleanupRequestUseCase, never()).execute(OLD_MEDIA_ASSET_ID, NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET);
        verify(mediaContract, never()).delete(any());
        verify(failureRepositoryPort, never()).save(any());
        verify(failureRepositoryPort, never()).deleteSupersededBySuccessfulRevision(any(), any(), org.mockito.ArgumentMatchers.anyLong());
        assertThat(result.outcome()).isEqualTo(RegenerateNarrationAudioOutcome.REGENERATED);
        assertThat(result.currentMediaAssetId()).isEqualTo(winningMediaAssetId);
    }

    @Test
    @DisplayName("28. Encoder failure during regeneration: stage AUDIO_ENCODING recorded, no Media upload, old state untouched")
    void shouldRecordAudioEncodingFailureWhenEncoderFailsDuringRegeneration() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L);
        ChapterNarrationAudio audio = createExistingAudio(1L);
        byte[] audioBytes = new byte[]{82, 73, 70, 70, 10, 20, 30};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(audio));
        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        when(clockPort.now()).thenReturn(NOW);
        when(idGeneratorPort.generate()).thenReturn(FAILURE_ID);

        RuntimeException encodeEx = new RuntimeException("FFmpeg encoder process crashed");
        when(segmentAudioEncoderPort.encode(any())).thenThrow(encodeEx);

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(encodeEx);

        // Old audio untouched
        assertThat(audio.getMediaAssetId()).isEqualTo(OLD_MEDIA_ASSET_ID);
        assertThat(audio.getGeneratedSynthesisRevision()).isEqualTo(1L);

        verifyNoInteractions(mediaContract);
        verifyNoInteractions(cleanupRequestUseCase);
        verify(audioRepositoryPort, never()).save(any());

        ArgumentCaptor<ChapterNarrationAudioFailure> failureCaptor = ArgumentCaptor.forClass(ChapterNarrationAudioFailure.class);
        verify(failureRepositoryPort).save(failureCaptor.capture());
        ChapterNarrationAudioFailure failure = failureCaptor.getValue();
        assertThat(failure.getOperation()).isEqualTo(NarrationAudioOperation.REGENERATION);
        assertThat(failure.getStage()).isEqualTo(NarrationAudioFailureStage.AUDIO_ENCODING);
        assertThat(failure.getAttemptedSynthesisRevision()).isEqualTo(2L);
    }

    @Test
    @DisplayName("29. Pre-upload openStream failure: stage AUDIO_ENCODING, resource closed, no Media upload, old state untouched")
    void shouldCloseResourceWhenOpenStreamFailsDuringRegeneration() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L);
        ChapterNarrationAudio audio = createExistingAudio(1L);
        byte[] audioBytes = new byte[]{82, 73, 70, 70, 10, 20, 30};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(audio));
        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        when(clockPort.now()).thenReturn(NOW);
        when(idGeneratorPort.generate()).thenReturn(FAILURE_ID);

        AtomicBoolean resourceClosed = new AtomicBoolean(false);
        RuntimeException openStreamEx = new RuntimeException("Cannot open stream from temp file");
        when(segmentAudioEncoderPort.encode(any())).thenAnswer(invocation ->
                createEncodingResult(DEFAULT_MP3_BYTES, DEFAULT_SAMPLES, DEFAULT_SAMPLE_RATE,
                        resourceClosed, null, openStreamEx, null, null));

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(openStreamEx);

        assertThat(resourceClosed.get()).isTrue();
        assertThat(audio.getMediaAssetId()).isEqualTo(OLD_MEDIA_ASSET_ID);
        verifyNoInteractions(mediaContract);
        verifyNoInteractions(cleanupRequestUseCase);

        ArgumentCaptor<ChapterNarrationAudioFailure> failureCaptor = ArgumentCaptor.forClass(ChapterNarrationAudioFailure.class);
        verify(failureRepositoryPort).save(failureCaptor.capture());
        ChapterNarrationAudioFailure failure = failureCaptor.getValue();
        assertThat(failure.getStage()).isEqualTo(NarrationAudioFailureStage.AUDIO_ENCODING);
    }

    @Test
    @DisplayName("30. Pre-upload failure with resource close failure suppresses close exception under primary exception")
    void shouldSuppressResourceCloseExceptionWhenPreUploadFailsDuringRegeneration() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L);
        ChapterNarrationAudio audio = createExistingAudio(1L);
        byte[] audioBytes = new byte[]{82, 73, 70, 70, 10, 20, 30};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(audio));
        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        when(clockPort.now()).thenReturn(NOW);
        when(idGeneratorPort.generate()).thenReturn(FAILURE_ID);

        RuntimeException openStreamEx = new RuntimeException("Stream open failed");
        RuntimeException closeEx = new RuntimeException("Resource close failed");
        when(segmentAudioEncoderPort.encode(any())).thenAnswer(invocation ->
                createEncodingResult(DEFAULT_MP3_BYTES, DEFAULT_SAMPLES, DEFAULT_SAMPLE_RATE,
                        null, null, openStreamEx, null, closeEx));

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(openStreamEx)
                .hasSuppressedException(closeEx);

        verifyNoInteractions(mediaContract);
        verifyNoInteractions(cleanupRequestUseCase);
    }

    @Test
    @DisplayName("31. Upload failure with stream and resource close failures suppresses both under upload exception")
    void shouldCloseStreamAndResourceWithSuppressionWhenMediaUploadFailsDuringRegeneration() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L);
        ChapterNarrationAudio audio = createExistingAudio(1L);
        byte[] audioBytes = new byte[]{82, 73, 70, 70, 10, 20, 30};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(audio));
        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);

        AtomicBoolean resourceClosed = new AtomicBoolean(false);
        List<AtomicBoolean> streamClosedFlags = new java.util.ArrayList<>();
        IOException streamCloseEx = new IOException("Stream flush error on close");
        RuntimeException resourceCloseEx = new RuntimeException("Resource file delete error on close");

        when(segmentAudioEncoderPort.encode(any())).thenAnswer(invocation ->
                createEncodingResult(DEFAULT_MP3_BYTES, DEFAULT_SAMPLES, DEFAULT_SAMPLE_RATE,
                        resourceClosed, streamClosedFlags, null, streamCloseEx, resourceCloseEx));

        RuntimeException mediaEx = new RuntimeException("S3 upload timeout");
        when(mediaContract.uploadAsset(any())).thenThrow(mediaEx);
        when(clockPort.now()).thenReturn(NOW);
        when(idGeneratorPort.generate()).thenReturn(FAILURE_ID);

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(mediaEx)
                .hasSuppressedException(streamCloseEx)
                .hasSuppressedException(resourceCloseEx);

        assertThat(resourceClosed.get()).isTrue();
        assertThat(streamClosedFlags).hasSize(1);
        assertThat(streamClosedFlags.get(0).get()).isTrue();
        verifyNoInteractions(cleanupRequestUseCase);
        verify(audioRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("32. Post-upload stream close failure logs warning only and does NOT fail regeneration")
    void shouldSucceedWhenInputStreamCloseFailsAfterUploadDuringRegeneration() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L);
        ChapterNarrationAudio audio = createExistingAudio(1L);
        byte[] audioBytes = new byte[]{82, 73, 70, 70, 10, 20, 30};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(audio));
        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);

        IOException streamCloseEx = new IOException("Minor stream close warning");
        when(segmentAudioEncoderPort.encode(any())).thenAnswer(invocation ->
                createEncodingResult(DEFAULT_MP3_BYTES, DEFAULT_SAMPLES, DEFAULT_SAMPLE_RATE,
                        null, null, null, streamCloseEx, null));

        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(NEW_MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);
        when(audioRepositoryPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cleanupRequestUseCase.execute(OLD_MEDIA_ASSET_ID, NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET))
                .thenReturn(new RequestNarrationMediaCleanupResult(OLD_MEDIA_ASSET_ID, NarrationMediaCleanupOutcome.IMMEDIATELY_DELETED));

        RegenerateChapterNarrationAudioResult result = useCase.execute(SEGMENT_ID, VOICE_ID);

        assertThat(result.outcome()).isEqualTo(RegenerateNarrationAudioOutcome.REGENERATED);
        assertThat(result.currentMediaAssetId()).isEqualTo(NEW_MEDIA_ASSET_ID);
        // NEW asset never cleanup-targeted
        verify(cleanupRequestUseCase, never()).execute(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        // OLD asset cleaned up
        verify(cleanupRequestUseCase).execute(OLD_MEDIA_ASSET_ID, NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET);
    }

    @Test
    @DisplayName("33. Post-upload resource close failure logs warning only and does NOT fail regeneration")
    void shouldSucceedWhenEncodedResourceCloseFailsAfterUploadDuringRegeneration() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L);
        ChapterNarrationAudio audio = createExistingAudio(1L);
        byte[] audioBytes = new byte[]{82, 73, 70, 70, 10, 20, 30};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(audio));
        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);

        RuntimeException resourceCloseEx = new RuntimeException("Temp file could not be deleted immediately");
        when(segmentAudioEncoderPort.encode(any())).thenAnswer(invocation ->
                createEncodingResult(DEFAULT_MP3_BYTES, DEFAULT_SAMPLES, DEFAULT_SAMPLE_RATE,
                        null, null, null, null, resourceCloseEx));

        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(NEW_MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);
        when(audioRepositoryPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cleanupRequestUseCase.execute(OLD_MEDIA_ASSET_ID, NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET))
                .thenReturn(new RequestNarrationMediaCleanupResult(OLD_MEDIA_ASSET_ID, NarrationMediaCleanupOutcome.IMMEDIATELY_DELETED));

        RegenerateChapterNarrationAudioResult result = useCase.execute(SEGMENT_ID, VOICE_ID);

        assertThat(result.outcome()).isEqualTo(RegenerateNarrationAudioOutcome.REGENERATED);
        assertThat(result.currentMediaAssetId()).isEqualTo(NEW_MEDIA_ASSET_ID);
        verify(cleanupRequestUseCase, never()).execute(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        verify(cleanupRequestUseCase).execute(OLD_MEDIA_ASSET_ID, NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET);
    }

    @Test
    @DisplayName("34. Clock failure after upload: Cleans up NEW asset, restores OLD state, records ASSIGNMENT_PERSISTENCE")
    void shouldCleanupNewAssetAndRestoreOldStateWhenClockFailsAfterUploadDuringRegeneration() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L);
        ChapterNarrationAudio audio = createExistingAudio(1L);
        byte[] audioBytes = new byte[]{82, 73, 70, 70, 10, 20, 30};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(audio));
        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        mockDefaultEncoding();
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(NEW_MEDIA_ASSET_ID));

        RuntimeException clockEx = new RuntimeException("System time synchronization failed");
        when(clockPort.now()).thenThrow(clockEx);

        when(cleanupRequestUseCase.execute(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET))
                .thenReturn(new RequestNarrationMediaCleanupResult(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupOutcome.IMMEDIATELY_DELETED));

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(clockEx);

        // Reverted to old state
        assertThat(audio.getMediaAssetId()).isEqualTo(OLD_MEDIA_ASSET_ID);
        assertThat(audio.getGeneratedSynthesisRevision()).isEqualTo(1L);

        // NEW asset cleanup requested
        verify(cleanupRequestUseCase).execute(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        // OLD asset NOT cleanup-targeted
        verify(cleanupRequestUseCase, never()).execute(OLD_MEDIA_ASSET_ID, NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET);
        verify(audioRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("35. Save failure with OLD TIMED assignment: Restores exact old timing metadata and timestamp")
    void shouldRestoreOldTimedStateWhenPersistenceFails() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L);
        ChapterNarrationAudio audio = createExistingTimedAudio(1L); // Samples: 51840L, Rate: 48000
        byte[] audioBytes = new byte[]{82, 73, 70, 70, 10, 20, 30};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(audio));
        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        mockDefaultEncoding();
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(NEW_MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);

        RuntimeException dbEx = new RuntimeException("DB deadlock");
        when(audioRepositoryPort.save(any())).thenThrow(dbEx);
        when(cleanupRequestUseCase.execute(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET))
                .thenReturn(new RequestNarrationMediaCleanupResult(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupOutcome.IMMEDIATELY_DELETED));

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(dbEx);

        // Verify exact restoration of timed properties
        assertThat(audio.getMediaAssetId()).isEqualTo(OLD_MEDIA_ASSET_ID);
        assertThat(audio.getGeneratedSynthesisRevision()).isEqualTo(1L);
        assertThat(audio.getEncodedContributionSamples()).isEqualTo(51840L);
        assertThat(audio.getEncodedSampleRateHz()).isEqualTo(48000);
        assertThat(audio.getUpdatedAt()).isEqualTo(INITIAL_TIME);

        verify(cleanupRequestUseCase).execute(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        verify(cleanupRequestUseCase, never()).execute(OLD_MEDIA_ASSET_ID, NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET);
    }

    @Test
    @DisplayName("36. Save failure with OLD LEGACY assignment: Restores null timing metadata and original timestamp")
    void shouldRestoreOldLegacyStateWhenPersistenceFails() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L);
        ChapterNarrationAudio audio = createExistingAudio(1L); // Legacy untimed (null samples/rate)
        byte[] audioBytes = new byte[]{82, 73, 70, 70, 10, 20, 30};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(audio));
        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        mockDefaultEncoding();
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(NEW_MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);

        RuntimeException dbEx = new RuntimeException("DB deadlock");
        when(audioRepositoryPort.save(any())).thenThrow(dbEx);
        when(cleanupRequestUseCase.execute(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET))
                .thenReturn(new RequestNarrationMediaCleanupResult(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupOutcome.IMMEDIATELY_DELETED));

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(dbEx);

        // Verify exact restoration of legacy null properties
        assertThat(audio.getMediaAssetId()).isEqualTo(OLD_MEDIA_ASSET_ID);
        assertThat(audio.getGeneratedSynthesisRevision()).isEqualTo(1L);
        assertThat(audio.getEncodedContributionSamples()).isNull();
        assertThat(audio.getEncodedSampleRateHz()).isNull();
        assertThat(audio.getUpdatedAt()).isEqualTo(INITIAL_TIME);

        verify(cleanupRequestUseCase).execute(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        verify(cleanupRequestUseCase, never()).execute(OLD_MEDIA_ASSET_ID, NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET);
    }

    @Test
    @DisplayName("37. Optimistic race with legacy untimed winner: Rejected, loser's NEW asset cleaned up, error propagates, diagnostic recorded")
    void shouldRejectOptimisticRaceWinnerWithoutEncodedTiming() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L);
        ChapterNarrationAudio initialStaleAudio = createExistingAudio(1L);

        UUID untimedMediaAssetId = UUID.fromString("90000000-0000-0000-0000-000000000012");
        ChapterNarrationAudio legacyWinner = ChapterNarrationAudio.rehydrate(
                AUDIO_ID, SEGMENT_ID, VOICE_ID, untimedMediaAssetId, 2L, null, null, 1L, NOW, NOW // Revision 2L matches, but untimed
        );

        byte[] audioBytes = new byte[]{1, 2, 3};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(initialStaleAudio))
                .thenReturn(Optional.of(legacyWinner));

        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        mockDefaultEncoding();
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(NEW_MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);
        when(idGeneratorPort.generate()).thenReturn(FAILURE_ID);

        ObjectOptimisticLockingFailureException optLockEx =
                new ObjectOptimisticLockingFailureException(ChapterNarrationAudio.class, AUDIO_ID);
        when(audioRepositoryPort.save(any())).thenThrow(optLockEx);
        when(cleanupRequestUseCase.execute(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET))
                .thenReturn(new RequestNarrationMediaCleanupResult(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupOutcome.IMMEDIATELY_DELETED));

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(optLockEx);

        // Loser's NEW media requested for cleanup
        verify(cleanupRequestUseCase).execute(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        // OLD media NOT cleanup-targeted
        verify(cleanupRequestUseCase, never()).execute(OLD_MEDIA_ASSET_ID, NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET);
        // Diagnostic recorded with ASSIGNMENT_PERSISTENCE stage
        verify(failureRepositoryPort).save(any(ChapterNarrationAudioFailure.class));
    }

    @Test
    @DisplayName("38. Optimistic race with stale timed winner: Rejected, loser's NEW asset cleaned up, error propagates, diagnostic recorded")
    void shouldRejectOptimisticRaceWinnerWithStaleRevision() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(3L); // Voice target revision is 3L
        ChapterNarrationAudio initialStaleAudio = createExistingAudio(1L);

        UUID staleMediaAssetId = UUID.fromString("90000000-0000-0000-0000-000000000013");
        ChapterNarrationAudio staleTimedWinner = ChapterNarrationAudio.rehydrate(
                AUDIO_ID, SEGMENT_ID, VOICE_ID, staleMediaAssetId, 2L, DEFAULT_SAMPLES, DEFAULT_SAMPLE_RATE, 1L, NOW, NOW // Revision 2L != 3L
        );

        byte[] audioBytes = new byte[]{1, 2, 3};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(initialStaleAudio))
                .thenReturn(Optional.of(staleTimedWinner));

        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        mockDefaultEncoding();
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(NEW_MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);
        when(idGeneratorPort.generate()).thenReturn(FAILURE_ID);

        ObjectOptimisticLockingFailureException optLockEx =
                new ObjectOptimisticLockingFailureException(ChapterNarrationAudio.class, AUDIO_ID);
        when(audioRepositoryPort.save(any())).thenThrow(optLockEx);
        when(cleanupRequestUseCase.execute(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET))
                .thenReturn(new RequestNarrationMediaCleanupResult(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupOutcome.IMMEDIATELY_DELETED));

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(optLockEx);

        verify(cleanupRequestUseCase).execute(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        verify(cleanupRequestUseCase, never()).execute(OLD_MEDIA_ASSET_ID, NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET);
        verify(failureRepositoryPort).save(any(ChapterNarrationAudioFailure.class));
    }

    @Test
    @DisplayName("39. State restoration failure during post-upload error: Restoration failure suppressed under primary, NEW cleanup still requested")
    void shouldSuppressStateRestorationFailureWhenPostUploadErrorOccurs() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L);
        ChapterNarrationAudio realAudio = createExistingAudio(1L);
        ChapterNarrationAudio spyAudio = org.mockito.Mockito.spy(realAudio);

        byte[] audioBytes = new byte[]{82, 73, 70, 70, 10, 20, 30};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(spyAudio));
        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        mockDefaultEncoding();
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(NEW_MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);

        RuntimeException dbEx = new RuntimeException("DB deadlock during save");
        when(audioRepositoryPort.save(any())).thenThrow(dbEx);

        RuntimeException restoreEx = new RuntimeException("State restoration corrupted");
        org.mockito.Mockito.doCallRealMethod()
                .doThrow(restoreEx)
                .when(spyAudio).replaceSuccessfulAudio(any(), org.mockito.ArgumentMatchers.anyLong(), any(), any(), any());

        when(cleanupRequestUseCase.execute(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET))
                .thenReturn(new RequestNarrationMediaCleanupResult(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupOutcome.IMMEDIATELY_DELETED));

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(dbEx)
                .hasSuppressedException(restoreEx);

        // Verify NEW cleanup was still attempted despite restoration failure
        verify(cleanupRequestUseCase).execute(NEW_MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
    }
}

