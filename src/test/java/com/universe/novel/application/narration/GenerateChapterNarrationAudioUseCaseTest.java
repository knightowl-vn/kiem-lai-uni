package com.universe.novel.application.narration;

import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.dto.UploadMediaAssetRequestDTO;
import com.universe.media.contracts.dto.UploadMediaAssetResponseDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.novel.application.exceptions.ChapterNarrationAudioAlreadyExistsException;
import com.universe.novel.application.exceptions.ChapterNarrationSegmentInvalidStateException;
import com.universe.novel.application.exceptions.ChapterNarrationSegmentNotFoundException;
import com.universe.novel.application.exceptions.ManagedVoiceInvalidStateException;
import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.ports.ChapterNarrationAudioFailureRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationAudioRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationSegmentRepositoryPort;
import com.universe.novel.application.ports.ManagedVoiceRepositoryPort;
import com.universe.novel.application.ports.SegmentAudioEncoderPort;
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
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

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
@DisplayName("GenerateChapterNarrationAudioUseCase Unit Tests")
class GenerateChapterNarrationAudioUseCaseTest {

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

    private GenerateChapterNarrationAudioUseCase useCase;

    private static final UUID SEGMENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CHAPTER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID VOICE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID AUDIO_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID FAILURE_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID MEDIA_ASSET_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final Instant NOW = Instant.parse("2026-09-05T14:00:00Z");

    @BeforeEach
    void setUp() {
        useCase = new GenerateChapterNarrationAudioUseCase(
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
                NOW
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
                NOW,
                NOW
        );
    }

    @Test
    @DisplayName("1. Reuses existing audio assignment when compatible and timed without invoking TTS, encoder, Media, or DB save")
    void shouldReuseExistingCompatibleAudioAssignmentWithoutTtsOrMediaOrDbCall() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L);
        ChapterNarrationAudio existingAudio = ChapterNarrationAudio.create(
                AUDIO_ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 2L, 51840L, 48000, NOW
        );

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(existingAudio));

        GenerateChapterNarrationAudioResult result = useCase.execute(SEGMENT_ID, VOICE_ID);

        assertThat(result.assignmentId()).isEqualTo(AUDIO_ID);
        assertThat(result.segmentId()).isEqualTo(SEGMENT_ID);
        assertThat(result.managedVoiceId()).isEqualTo(VOICE_ID);
        assertThat(result.mediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
        assertThat(result.generatedSynthesisRevision()).isEqualTo(2L);
        assertThat(result.outcome()).isEqualTo(NarrationAudioGenerationOutcome.REUSED);

        verifyNoInteractions(ttsProviderPort);
        verifyNoInteractions(segmentAudioEncoderPort);
        verifyNoInteractions(mediaContract);
        verifyNoInteractions(cleanupRequestUseCase);
        verify(audioRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("2. Returns STALE when existing audio assignment synthesis revision differs without regenerating")
    void shouldReturnStaleWhenAssignmentRevisionDiffersWithoutTtsOrMediaOrDbCall() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(3L); // Voice changed to revision 3
        ChapterNarrationAudio existingAudio = ChapterNarrationAudio.create(
                AUDIO_ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 2L, 51840L, 48000, NOW // Audio was generated at revision 2
        );

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(existingAudio));

        GenerateChapterNarrationAudioResult result = useCase.execute(SEGMENT_ID, VOICE_ID);

        assertThat(result.assignmentId()).isEqualTo(AUDIO_ID);
        assertThat(result.segmentId()).isEqualTo(SEGMENT_ID);
        assertThat(result.managedVoiceId()).isEqualTo(VOICE_ID);
        assertThat(result.mediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
        assertThat(result.generatedSynthesisRevision()).isEqualTo(2L);
        assertThat(result.outcome()).isEqualTo(NarrationAudioGenerationOutcome.STALE);

        verifyNoInteractions(ttsProviderPort);
        verifyNoInteractions(segmentAudioEncoderPort);
        verifyNoInteractions(mediaContract);
        verifyNoInteractions(cleanupRequestUseCase);
        verify(audioRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("2b. Returns STALE when existing audio assignment has same revision but lacks encoded timing (legacy null/null)")
    void shouldReturnStaleWhenAssignmentHasSameRevisionButLacksTimingWithoutTtsOrEncoderOrMediaOrDbCall() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L);
        ChapterNarrationAudio legacyAudio = ChapterNarrationAudio.create(
                AUDIO_ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 2L, NOW // legacy null/null timing
        );

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(legacyAudio));

        GenerateChapterNarrationAudioResult result = useCase.execute(SEGMENT_ID, VOICE_ID);

        assertThat(result.assignmentId()).isEqualTo(AUDIO_ID);
        assertThat(result.segmentId()).isEqualTo(SEGMENT_ID);
        assertThat(result.managedVoiceId()).isEqualTo(VOICE_ID);
        assertThat(result.mediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
        assertThat(result.generatedSynthesisRevision()).isEqualTo(2L);
        assertThat(result.outcome()).isEqualTo(NarrationAudioGenerationOutcome.STALE);

        verifyNoInteractions(ttsProviderPort);
        verifyNoInteractions(segmentAudioEncoderPort);
        verifyNoInteractions(mediaContract);
        verifyNoInteractions(cleanupRequestUseCase);
        verify(audioRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("3. Generates audio via TTS, normalizes, encodes to MP3, uploads to Media, and persists assignment with timing")
    void shouldGenerateAudioAndStoreMediaAndPersistWhenNoAssignmentExists() throws IOException {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(1L);
        byte[] audioBytes = new byte[]{82, 73, 70, 70, 1, 2, 3}; // RIFF... WAV
        byte[] mp3Bytes = new byte[]{-1, -5, 16, 4, 5, 6}; // MP3
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty());

        when(ttsProviderPort.synthesize(new TtsSynthesisCommand("Trần Bình An cất bước ra đi.", "minh-duc")))
                .thenReturn(ttsResult);

        when(segmentAudioEncoderPort.encode(any(SegmentAudioEncodingRequest.class)))
                .thenReturn(createEncodingResult(mp3Bytes, 51840L, 48000));

        java.util.concurrent.atomic.AtomicReference<byte[]> uploadedBytesRef = new java.util.concurrent.atomic.AtomicReference<>();
        when(mediaContract.uploadAsset(any(UploadMediaAssetRequestDTO.class)))
                .thenAnswer(invocation -> {
                    UploadMediaAssetRequestDTO req = invocation.getArgument(0);
                    uploadedBytesRef.set(req.content().readAllBytes());
                    return new UploadMediaAssetResponseDTO(MEDIA_ASSET_ID);
                });

        when(clockPort.now()).thenReturn(NOW);
        when(idGeneratorPort.generate()).thenReturn(AUDIO_ID);

        ChapterNarrationAudio savedAudio = ChapterNarrationAudio.create(
                AUDIO_ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 1L, 51840L, 48000, NOW
        );
        when(audioRepositoryPort.save(any(ChapterNarrationAudio.class))).thenReturn(savedAudio);

        GenerateChapterNarrationAudioResult result = useCase.execute(new GenerateChapterNarrationAudioCommand(SEGMENT_ID, VOICE_ID));

        assertThat(result.assignmentId()).isEqualTo(AUDIO_ID);
        assertThat(result.segmentId()).isEqualTo(SEGMENT_ID);
        assertThat(result.managedVoiceId()).isEqualTo(VOICE_ID);
        assertThat(result.mediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
        assertThat(result.generatedSynthesisRevision()).isEqualTo(1L);
        assertThat(result.outcome()).isEqualTo(NarrationAudioGenerationOutcome.GENERATED);

        // Verify InOrder sequence: TTS -> encoder -> Media -> save -> clear failure
        InOrder inOrder = inOrder(ttsProviderPort, segmentAudioEncoderPort, mediaContract, audioRepositoryPort, failureRepositoryPort);
        inOrder.verify(ttsProviderPort).synthesize(new TtsSynthesisCommand("Trần Bình An cất bước ra đi.", "minh-duc"));

        ArgumentCaptor<SegmentAudioEncodingRequest> encodeCaptor = ArgumentCaptor.forClass(SegmentAudioEncodingRequest.class);
        inOrder.verify(segmentAudioEncoderPort).encode(encodeCaptor.capture());
        SegmentAudioEncodingRequest capturedEncodeReq = encodeCaptor.getValue();
        assertThat(capturedEncodeReq.mimeType()).isEqualTo("audio/wav");
        assertThat(capturedEncodeReq.openStream().readAllBytes()).isEqualTo(NarrationWavBoundaryNormalizer.normalize(audioBytes, "audio/wav"));

        // Verify Media upload payload (MP3 format, size, mime, .mp3 extension)
        ArgumentCaptor<UploadMediaAssetRequestDTO> uploadCaptor = ArgumentCaptor.forClass(UploadMediaAssetRequestDTO.class);
        inOrder.verify(mediaContract).uploadAsset(uploadCaptor.capture());
        UploadMediaAssetRequestDTO capturedUpload = uploadCaptor.getValue();
        assertThat(capturedUpload.sizeBytes()).isEqualTo(mp3Bytes.length);
        assertThat(capturedUpload.mimeType()).isEqualTo("audio/mpeg");
        assertThat(capturedUpload.mediaType()).isEqualTo(MediaTypeDTO.AUDIO);
        assertThat(capturedUpload.visibility()).isEqualTo(MediaVisibilityDTO.PUBLIC);
        assertThat(capturedUpload.originalFilename()).isEqualTo("segment-" + SEGMENT_ID + ".mp3");
        assertThat(uploadedBytesRef.get()).isEqualTo(mp3Bytes);

        // Verify persistence with encoded timing
        ArgumentCaptor<ChapterNarrationAudio> audioCaptor = ArgumentCaptor.forClass(ChapterNarrationAudio.class);
        inOrder.verify(audioRepositoryPort).save(audioCaptor.capture());
        ChapterNarrationAudio capturedAudio = audioCaptor.getValue();
        assertThat(capturedAudio.getId()).isEqualTo(AUDIO_ID);
        assertThat(capturedAudio.getSegmentId()).isEqualTo(SEGMENT_ID);
        assertThat(capturedAudio.getManagedVoiceId()).isEqualTo(VOICE_ID);
        assertThat(capturedAudio.getMediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
        assertThat(capturedAudio.getGeneratedSynthesisRevision()).isEqualTo(1L);
        assertThat(capturedAudio.getEncodedContributionSamples()).isEqualTo(51840L);
        assertThat(capturedAudio.getEncodedSampleRateHz()).isEqualTo(48000);

        // Verify failure cleared on success with generated revision
        inOrder.verify(failureRepositoryPort).deleteSupersededBySuccessfulRevision(SEGMENT_ID, VOICE_ID, 1L);
        verifyNoInteractions(cleanupRequestUseCase);
    }

    @Test
    @DisplayName("3b. Derives .mp3 filename and audio/mpeg when encoder outputs MP3")
    void shouldDeriveMp3FilenameForMpegMimeType() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(1L);
        byte[] audioBytes = new byte[]{1, 2, 3, 4};
        byte[] mp3Bytes = new byte[]{10, 20, 30};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty());

        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        when(segmentAudioEncoderPort.encode(any())).thenReturn(createEncodingResult(mp3Bytes, 51840L, 48000));
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);
        when(idGeneratorPort.generate()).thenReturn(AUDIO_ID);

        ChapterNarrationAudio savedAudio = ChapterNarrationAudio.create(
                AUDIO_ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 1L, 51840L, 48000, NOW
        );
        when(audioRepositoryPort.save(any())).thenReturn(savedAudio);

        GenerateChapterNarrationAudioResult result = useCase.execute(SEGMENT_ID, VOICE_ID);

        assertThat(result.outcome()).isEqualTo(NarrationAudioGenerationOutcome.GENERATED);

        ArgumentCaptor<UploadMediaAssetRequestDTO> uploadCaptor = ArgumentCaptor.forClass(UploadMediaAssetRequestDTO.class);
        verify(mediaContract).uploadAsset(uploadCaptor.capture());
        assertThat(uploadCaptor.getValue().originalFilename()).isEqualTo("segment-" + SEGMENT_ID + ".mp3");
        assertThat(uploadCaptor.getValue().mimeType()).isEqualTo("audio/mpeg");
    }

    @Test
    @DisplayName("3d. Closes encoded resource and open stream after successful media upload")
    void shouldCloseEncodedResourceAndStreamAfterSuccessfulMediaUpload() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(1L);
        byte[] audioBytes = new byte[]{1, 2, 3, 4};
        byte[] mp3Bytes = new byte[]{10, 20, 30};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        AtomicBoolean resourceClosed = new AtomicBoolean(false);
        java.util.List<AtomicBoolean> streamClosedFlags = new java.util.ArrayList<>();
        SegmentAudioEncodingResult encodingResult = createEncodingResult(mp3Bytes, 51840L, 48000, resourceClosed, streamClosedFlags);

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty());
        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        when(segmentAudioEncoderPort.encode(any())).thenReturn(encodingResult);
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);
        when(idGeneratorPort.generate()).thenReturn(AUDIO_ID);

        ChapterNarrationAudio savedAudio = ChapterNarrationAudio.create(
                AUDIO_ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 1L, 51840L, 48000, NOW
        );
        when(audioRepositoryPort.save(any())).thenReturn(savedAudio);

        GenerateChapterNarrationAudioResult result = useCase.execute(SEGMENT_ID, VOICE_ID);

        assertThat(result.outcome()).isEqualTo(NarrationAudioGenerationOutcome.GENERATED);
        assertThat(resourceClosed.get()).isTrue();
        assertThat(streamClosedFlags).hasSize(1);
        assertThat(streamClosedFlags.get(0).get()).isTrue();
    }

    @Test
    @DisplayName("3e. Closes encoded resource and open stream when media upload fails")
    void shouldCloseEncodedResourceAndStreamWhenMediaUploadFails() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(1L);
        byte[] audioBytes = new byte[]{1, 2, 3, 4};
        byte[] mp3Bytes = new byte[]{10, 20, 30};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        AtomicBoolean resourceClosed = new AtomicBoolean(false);
        java.util.List<AtomicBoolean> streamClosedFlags = new java.util.ArrayList<>();
        SegmentAudioEncodingResult encodingResult = createEncodingResult(mp3Bytes, 51840L, 48000, resourceClosed, streamClosedFlags);

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty());
        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        when(segmentAudioEncoderPort.encode(any())).thenReturn(encodingResult);

        RuntimeException mediaEx = new RuntimeException("Media storage unreachable");
        when(mediaContract.uploadAsset(any())).thenThrow(mediaEx);
        when(clockPort.now()).thenReturn(NOW);
        when(idGeneratorPort.generate()).thenReturn(FAILURE_ID);

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(mediaEx);

        assertThat(resourceClosed.get()).isTrue();
        assertThat(streamClosedFlags).hasSize(1);
        assertThat(streamClosedFlags.get(0).get()).isTrue();

        ArgumentCaptor<ChapterNarrationAudioFailure> failureCaptor = ArgumentCaptor.forClass(ChapterNarrationAudioFailure.class);
        verify(failureRepositoryPort).save(failureCaptor.capture());
        assertThat(failureCaptor.getValue().getStage()).isEqualTo(NarrationAudioFailureStage.MEDIA_UPLOAD);
    }

    @Test
    @DisplayName("3c. Resolves safe filename extensions for various audio MIME types")
    void shouldResolveSafeFilenameExtensionsForVariousAudioMimeTypes() {
        assertThat(GenerateChapterNarrationAudioUseCase.resolveAudioFilename(SEGMENT_ID, "audio/wav"))
                .isEqualTo("segment-" + SEGMENT_ID + ".wav");
        assertThat(GenerateChapterNarrationAudioUseCase.resolveAudioFilename(SEGMENT_ID, "audio/x-wav"))
                .isEqualTo("segment-" + SEGMENT_ID + ".wav");
        assertThat(GenerateChapterNarrationAudioUseCase.resolveAudioFilename(SEGMENT_ID, "audio/wave"))
                .isEqualTo("segment-" + SEGMENT_ID + ".wav");
        assertThat(GenerateChapterNarrationAudioUseCase.resolveAudioFilename(SEGMENT_ID, "audio/mpeg"))
                .isEqualTo("segment-" + SEGMENT_ID + ".mp3");
        assertThat(GenerateChapterNarrationAudioUseCase.resolveAudioFilename(SEGMENT_ID, "audio/mp3"))
                .isEqualTo("segment-" + SEGMENT_ID + ".mp3");
        assertThat(GenerateChapterNarrationAudioUseCase.resolveAudioFilename(SEGMENT_ID, "audio/ogg"))
                .isEqualTo("segment-" + SEGMENT_ID + ".ogg");
        assertThat(GenerateChapterNarrationAudioUseCase.resolveAudioFilename(SEGMENT_ID, "audio/webm"))
                .isEqualTo("segment-" + SEGMENT_ID + ".webm");
        assertThat(GenerateChapterNarrationAudioUseCase.resolveAudioFilename(SEGMENT_ID, "audio/aac"))
                .isEqualTo("segment-" + SEGMENT_ID + ".aac");
        assertThat(GenerateChapterNarrationAudioUseCase.resolveAudioFilename(SEGMENT_ID, "audio/mp4"))
                .isEqualTo("segment-" + SEGMENT_ID + ".m4a");
        assertThat(GenerateChapterNarrationAudioUseCase.resolveAudioFilename(SEGMENT_ID, "audio/flac"))
                .isEqualTo("segment-" + SEGMENT_ID + ".flac");
        assertThat(GenerateChapterNarrationAudioUseCase.resolveAudioFilename(SEGMENT_ID, "audio/x-custom"))
                .isEqualTo("segment-" + SEGMENT_ID + ".custom");
        assertThat(GenerateChapterNarrationAudioUseCase.resolveAudioFilename(SEGMENT_ID, null))
                .isEqualTo("segment-" + SEGMENT_ID + ".audio");
    }

    @Test
    @DisplayName("4. Throws ChapterNarrationSegmentNotFoundException when segment does not exist")
    void shouldThrowWhenSegmentNotFound() {
        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isInstanceOf(ChapterNarrationSegmentNotFoundException.class);

        verifyNoInteractions(managedVoiceRepositoryPort);
        verifyNoInteractions(ttsProviderPort);
        verifyNoInteractions(mediaContract);
    }

    @Test
    @DisplayName("5. Throws ChapterNarrationSegmentInvalidStateException when segment is not in CURRENT status")
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
        verifyNoInteractions(ttsProviderPort);
        verifyNoInteractions(mediaContract);
    }

    @Test
    @DisplayName("6. Throws ManagedVoiceNotFoundException when managed voice does not exist")
    void shouldThrowWhenVoiceNotFound() {
        ChapterNarrationSegment segment = createCurrentSegment();
        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isInstanceOf(ManagedVoiceNotFoundException.class);

        verifyNoInteractions(ttsProviderPort);
        verifyNoInteractions(mediaContract);
    }

    @Test
    @DisplayName("7. Throws ManagedVoiceInvalidStateException when managed voice is not in ACTIVE status")
    void shouldThrowWhenVoiceNotActive() {
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
                NOW,
                NOW
        );

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(disabledVoice));

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isInstanceOf(ManagedVoiceInvalidStateException.class)
                .hasMessageContaining("ACTIVE");

        verifyNoInteractions(ttsProviderPort);
        verifyNoInteractions(mediaContract);
    }

    @Test
    @DisplayName("8. Propagates TTS provider failure without calling Media or Database")
    void shouldPropagateTtsProviderFailureWithoutCallingMediaOrDatabase() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(1L);

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty());

        RuntimeException ttsException = new RuntimeException("TTS service connection timeout");
        when(ttsProviderPort.synthesize(any())).thenThrow(ttsException);

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(ttsException);

        verifyNoInteractions(segmentAudioEncoderPort);
        verifyNoInteractions(mediaContract);
        verifyNoInteractions(cleanupRequestUseCase);
        verify(audioRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("8b. Propagates segment audio encoder failure without calling Media or Database and records AUDIO_ENCODING")
    void shouldPropagateEncoderFailureAndRecordAudioEncodingWithoutCallingMediaOrDatabase() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L);
        byte[] audioBytes = new byte[]{1, 2, 3};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty());

        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);

        RuntimeException encodeEx = new RuntimeException("Encoder process exited with code 1");
        when(segmentAudioEncoderPort.encode(any())).thenThrow(encodeEx);
        when(failureRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty());
        when(clockPort.now()).thenReturn(NOW);
        when(idGeneratorPort.generate()).thenReturn(FAILURE_ID);

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(encodeEx);

        verifyNoInteractions(mediaContract);
        verifyNoInteractions(cleanupRequestUseCase);
        verify(audioRepositoryPort, never()).save(any());

        ArgumentCaptor<ChapterNarrationAudioFailure> failureCaptor = ArgumentCaptor.forClass(ChapterNarrationAudioFailure.class);
        verify(failureRepositoryPort).save(failureCaptor.capture());
        ChapterNarrationAudioFailure failure = failureCaptor.getValue();
        assertThat(failure.getId()).isEqualTo(FAILURE_ID);
        assertThat(failure.getSegmentId()).isEqualTo(SEGMENT_ID);
        assertThat(failure.getManagedVoiceId()).isEqualTo(VOICE_ID);
        assertThat(failure.getOperation()).isEqualTo(NarrationAudioOperation.INITIAL_GENERATION);
        assertThat(failure.getStage()).isEqualTo(NarrationAudioFailureStage.AUDIO_ENCODING);
        assertThat(failure.getAttemptedSynthesisRevision()).isEqualTo(2L);
        assertThat(failure.getFailureCount()).isEqualTo(1);
        assertThat(failure.getErrorType()).isEqualTo("RuntimeException");
        assertThat(failure.getErrorMessage()).isEqualTo("Narration audio encoding failed.");
    }

    @Test
    @DisplayName("9. Propagates Media upload failure without calling Database or compensation delete")
    void shouldPropagateMediaUploadFailureWithoutCallingDatabaseOrCompensationDelete() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(1L);
        byte[] audioBytes = new byte[]{1, 2, 3};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty());

        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        when(segmentAudioEncoderPort.encode(any())).thenReturn(createEncodingResult(new byte[]{10, 20}, 51840L, 48000));

        RuntimeException mediaException = new RuntimeException("Media storage disk full");
        when(mediaContract.uploadAsset(any())).thenThrow(mediaException);

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(mediaException);

        verify(audioRepositoryPort, never()).save(any());
        verify(mediaContract, never()).delete(any());
        verifyNoInteractions(cleanupRequestUseCase);
    }

    @Test
    @DisplayName("10. Requests durable cleanup when audio database persistence fails (non-duplicate)")
    void shouldRequestDurableCleanupWhenAudioPersistenceFails() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(1L);
        byte[] audioBytes = new byte[]{1, 2, 3};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty());

        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        when(segmentAudioEncoderPort.encode(any())).thenReturn(createEncodingResult(new byte[]{10, 20}, 51840L, 48000));
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);
        when(idGeneratorPort.generate()).thenReturn(AUDIO_ID);

        RuntimeException dbException = new RuntimeException("Database connection timed out");
        when(audioRepositoryPort.save(any())).thenThrow(dbException);
        when(cleanupRequestUseCase.execute(MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET))
                .thenReturn(new RequestNarrationMediaCleanupResult(MEDIA_ASSET_ID, NarrationMediaCleanupOutcome.IMMEDIATELY_DELETED));

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(dbException);

        // Verify InOrder: upload -> save attempt -> cleanup request
        InOrder inOrder = inOrder(mediaContract, audioRepositoryPort, cleanupRequestUseCase);
        inOrder.verify(mediaContract).uploadAsset(any(UploadMediaAssetRequestDTO.class));
        inOrder.verify(audioRepositoryPort).save(any(ChapterNarrationAudio.class));
        inOrder.verify(cleanupRequestUseCase).execute(MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);

        // Generate must never directly call mediaContract.delete
        verify(mediaContract, never()).delete(any());
    }

    @Test
    @DisplayName("10b. Non-duplicate persistence failure with cleanup ENQUEUED_FOR_RETRY propagates original persistence exception")
    void shouldPropagatePersistenceExceptionWhenCleanupEnqueuedForRetry() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(1L);
        byte[] audioBytes = new byte[]{1, 2, 3};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty());

        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        when(segmentAudioEncoderPort.encode(any())).thenReturn(createEncodingResult(new byte[]{10, 20}, 51840L, 48000));
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);
        when(idGeneratorPort.generate()).thenReturn(AUDIO_ID);

        RuntimeException dbException = new RuntimeException("DB disk error");
        when(audioRepositoryPort.save(any())).thenThrow(dbException);
        when(cleanupRequestUseCase.execute(MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET))
                .thenReturn(new RequestNarrationMediaCleanupResult(MEDIA_ASSET_ID, NarrationMediaCleanupOutcome.ENQUEUED_FOR_RETRY));

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(dbException);

        verify(cleanupRequestUseCase).execute(MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        verify(mediaContract, never()).delete(any());
    }

    @Test
    @DisplayName("11. Preserves primary persistence exception and attaches suppressed exception when cleanup request fails completely")
    void shouldAttachSuppressedExceptionWhenCleanupRequestFailsCompletely() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(1L);
        byte[] audioBytes = new byte[]{1, 2, 3};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty());

        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        when(segmentAudioEncoderPort.encode(any())).thenReturn(createEncodingResult(new byte[]{10, 20}, 51840L, 48000));
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);
        when(idGeneratorPort.generate()).thenReturn(AUDIO_ID);

        RuntimeException dbException = new RuntimeException("Database deadlock");
        RuntimeException cleanupException = new RuntimeException("Cleanup infrastructure completely unavailable");

        when(audioRepositoryPort.save(any())).thenThrow(dbException);
        doThrow(cleanupException).when(cleanupRequestUseCase).execute(MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(dbException)
                .hasSuppressedException(cleanupException);

        verify(cleanupRequestUseCase).execute(MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        verify(mediaContract, never()).delete(any());
    }

    @Test
    @DisplayName("12. Validates null input parameters")
    void shouldRejectNullInputs() {
        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> useCase.execute(null, VOICE_ID))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("13. Records initial failure record when TTS synthesis fails and no prior failure existed")
    void shouldRecordInitialFailureWhenTtsSynthesisFails() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L);

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty());
        when(failureRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty());
        when(clockPort.now()).thenReturn(NOW);
        when(idGeneratorPort.generate()).thenReturn(FAILURE_ID);

        RuntimeException ttsEx = new RuntimeException("VieNeu connection refused");
        when(ttsProviderPort.synthesize(any())).thenThrow(ttsEx);

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(ttsEx);

        ArgumentCaptor<ChapterNarrationAudioFailure> failureCaptor = ArgumentCaptor.forClass(ChapterNarrationAudioFailure.class);
        verify(failureRepositoryPort).save(failureCaptor.capture());
        ChapterNarrationAudioFailure failure = failureCaptor.getValue();

        assertThat(failure.getId()).isEqualTo(FAILURE_ID);
        assertThat(failure.getSegmentId()).isEqualTo(SEGMENT_ID);
        assertThat(failure.getManagedVoiceId()).isEqualTo(VOICE_ID);
        assertThat(failure.getOperation()).isEqualTo(NarrationAudioOperation.INITIAL_GENERATION);
        assertThat(failure.getStage()).isEqualTo(NarrationAudioFailureStage.TTS_SYNTHESIS);
        assertThat(failure.getAttemptedSynthesisRevision()).isEqualTo(2L);
        assertThat(failure.getFailureCount()).isEqualTo(1);
        assertThat(failure.getErrorType()).isEqualTo("RuntimeException");
        assertThat(failure.getErrorMessage()).isEqualTo("Narration TTS synthesis failed.");
        assertThat(failure.getFirstFailedAt()).isEqualTo(NOW);
        assertThat(failure.getLastFailedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("14. Updates existing failure record when repeated failure occurs during media upload")
    void shouldUpdateExistingFailureWhenRepeatedFailureOccursDuringMediaUpload() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(3L);
        byte[] audioBytes = new byte[]{1, 2, 3};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        ChapterNarrationAudioFailure existingFailure = ChapterNarrationAudioFailure.create(
                FAILURE_ID,
                SEGMENT_ID,
                VOICE_ID,
                NarrationAudioOperation.INITIAL_GENERATION,
                NarrationAudioFailureStage.TTS_SYNTHESIS,
                2L,
                "RuntimeException",
                Instant.parse("2026-09-05T10:00:00Z")
        );

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty());
        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        when(segmentAudioEncoderPort.encode(any())).thenReturn(createEncodingResult(new byte[]{10, 20}, 51840L, 48000));
        when(failureRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(existingFailure));
        when(clockPort.now()).thenReturn(NOW);

        RuntimeException mediaEx = new RuntimeException("Storage gateway timeout");
        when(mediaContract.uploadAsset(any())).thenThrow(mediaEx);

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(mediaEx);

        verify(failureRepositoryPort).save(existingFailure);
        assertThat(existingFailure.getFailureCount()).isEqualTo(2);
        assertThat(existingFailure.getStage()).isEqualTo(NarrationAudioFailureStage.MEDIA_UPLOAD);
        assertThat(existingFailure.getAttemptedSynthesisRevision()).isEqualTo(3L);
        assertThat(existingFailure.getErrorMessage()).isEqualTo("Narration audio media upload failed.");
        assertThat(existingFailure.getFirstFailedAt()).isEqualTo(Instant.parse("2026-09-05T10:00:00Z"));
        assertThat(existingFailure.getLastFailedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("15. Records failure when assignment persistence fails and preserves primary error if failure save also fails")
    void shouldAttachSuppressedWhenFailureRecordingFails() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(1L);
        byte[] audioBytes = new byte[]{1, 2, 3};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty());
        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        when(segmentAudioEncoderPort.encode(any())).thenReturn(createEncodingResult(new byte[]{10, 20}, 51840L, 48000));
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);
        when(idGeneratorPort.generate()).thenReturn(AUDIO_ID);

        RuntimeException dbException = new RuntimeException("Primary persistence failure");
        when(audioRepositoryPort.save(any())).thenThrow(dbException);

        RuntimeException failureSaveEx = new RuntimeException("Failure repo DB unreachable");
        when(failureRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenThrow(failureSaveEx);

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(dbException)
                .hasSuppressedException(failureSaveEx);
    }

    @Test
    @DisplayName("16. Successful generation completes even when clearing failure diagnostics throws an exception")
    void shouldCompleteGenerationWhenClearingFailureThrowsException() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(1L);
        byte[] audioBytes = new byte[]{1, 2, 3};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty());
        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        when(segmentAudioEncoderPort.encode(any())).thenReturn(createEncodingResult(new byte[]{10, 20}, 51840L, 48000));
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);
        when(idGeneratorPort.generate()).thenReturn(AUDIO_ID);

        ChapterNarrationAudio savedAudio = ChapterNarrationAudio.create(
                AUDIO_ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 1L, 51840L, 48000, NOW
        );
        when(audioRepositoryPort.save(any(ChapterNarrationAudio.class))).thenReturn(savedAudio);

        // Deleting failure diagnostic throws
        doThrow(new RuntimeException("Failure repo DB unreachable during cleanup"))
                .when(failureRepositoryPort).deleteSupersededBySuccessfulRevision(SEGMENT_ID, VOICE_ID, 1L);

        GenerateChapterNarrationAudioResult result = useCase.execute(SEGMENT_ID, VOICE_ID);

        assertThat(result.outcome()).isEqualTo(NarrationAudioGenerationOutcome.GENERATED);
        assertThat(result.assignmentId()).isEqualTo(AUDIO_ID);
        assertThat(result.mediaAssetId()).isEqualTo(MEDIA_ASSET_ID);

        // Cleanup request was NOT invoked for successful generation
        verifyNoInteractions(cleanupRequestUseCase);
        verify(mediaContract, never()).delete(any());
    }

    @Test
    @DisplayName("17. Clock failure during diagnostic recording does not mask primary TTS exception and is attached as suppressed")
    void shouldNotMaskPrimaryTtsExceptionWhenClockFailsDuringDiagnosticRecording() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L);

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty());

        RuntimeException ttsEx = new RuntimeException("TTS service connection timeout");
        when(ttsProviderPort.synthesize(any())).thenThrow(ttsEx);

        RuntimeException clockEx = new RuntimeException("Clock provider hardware failure");
        when(clockPort.now()).thenThrow(clockEx);

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(ttsEx)
                .hasSuppressedException(clockEx);
    }

    @Test
    @DisplayName("18. Duplicate race: Redundant uploaded media is requested for cleanup, compatible winner is reloaded, outcome is REUSED, and diagnostics not recorded")
    void shouldHandleDuplicateRaceWhenCompatibleWinnerExists() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(1L);
        byte[] audioBytes = new byte[]{1, 2, 3};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        UUID winnerAudioId = UUID.fromString("90000000-0000-0000-0000-000000000001");
        UUID winnerMediaId = UUID.fromString("90000000-0000-0000-0000-000000000002");
        ChapterNarrationAudio winningAudio = ChapterNarrationAudio.create(
                winnerAudioId, SEGMENT_ID, VOICE_ID, winnerMediaId, 1L, 51840L, 48000, NOW
        );

        // First check in useCase: empty
        // Second check in catch block after duplicate exception: winningAudio
        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winningAudio));

        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        when(segmentAudioEncoderPort.encode(any())).thenReturn(createEncodingResult(new byte[]{10, 20}, 51840L, 48000));
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);
        when(idGeneratorPort.generate()).thenReturn(AUDIO_ID);

        // Save fails with ChapterNarrationAudioAlreadyExistsException
        ChapterNarrationAudioAlreadyExistsException duplicateEx =
                new ChapterNarrationAudioAlreadyExistsException(SEGMENT_ID, VOICE_ID);
        when(audioRepositoryPort.save(any())).thenThrow(duplicateEx);
        when(cleanupRequestUseCase.execute(MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET))
                .thenReturn(new RequestNarrationMediaCleanupResult(MEDIA_ASSET_ID, NarrationMediaCleanupOutcome.IMMEDIATELY_DELETED));

        GenerateChapterNarrationAudioResult result = useCase.execute(SEGMENT_ID, VOICE_ID);

        // 1. Redundant media requested for cleanup exactly once
        verify(cleanupRequestUseCase, times(1)).execute(MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        // 2. Winner media NOT requested for cleanup
        verify(cleanupRequestUseCase, never()).execute(winnerMediaId, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        verify(mediaContract, never()).delete(any());

        // 3. Outcome is REUSED with winning audio identity
        assertThat(result.outcome()).isEqualTo(NarrationAudioGenerationOutcome.REUSED);
        assertThat(result.assignmentId()).isEqualTo(winnerAudioId);
        assertThat(result.mediaAssetId()).isEqualTo(winnerMediaId);
        assertThat(result.generatedSynthesisRevision()).isEqualTo(1L);

        // 4. Failure diagnostic NOT recorded and NOT deleted on benign loser path
        verify(failureRepositoryPort, never()).save(any());
        verify(failureRepositoryPort, never()).deleteSupersededBySuccessfulRevision(any(), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("18b. Duplicate race with compatible winner + cleanup TOTAL FAILURE propagates cleanup exception and suppresses duplicate exception")
    void shouldPropagateCleanupExceptionWhenCleanupFailsCompletelyOnDuplicateRaceWithCompatibleWinner() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(1L);
        byte[] audioBytes = new byte[]{1, 2, 3};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        UUID winnerAudioId = UUID.fromString("90000000-0000-0000-0000-000000000001");
        UUID winnerMediaId = UUID.fromString("90000000-0000-0000-0000-000000000002");
        ChapterNarrationAudio winningAudio = ChapterNarrationAudio.create(
                winnerAudioId, SEGMENT_ID, VOICE_ID, winnerMediaId, 1L, 51840L, 48000, NOW
        );

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winningAudio));

        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        when(segmentAudioEncoderPort.encode(any())).thenReturn(createEncodingResult(new byte[]{10, 20}, 51840L, 48000));
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);
        when(idGeneratorPort.generate()).thenReturn(AUDIO_ID);

        ChapterNarrationAudioAlreadyExistsException duplicateEx =
                new ChapterNarrationAudioAlreadyExistsException(SEGMENT_ID, VOICE_ID);
        when(audioRepositoryPort.save(any())).thenThrow(duplicateEx);

        RuntimeException cleanupFailureEx = new RuntimeException("Cleanup completely failed on both enqueue and delete");
        doThrow(cleanupFailureEx).when(cleanupRequestUseCase).execute(MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(cleanupFailureEx)
                .hasSuppressedException(duplicateEx);

        // No misleading ASSIGNMENT_PERSISTENCE diagnostic recorded for winning audio
        verify(failureRepositoryPort, never()).save(any());
        verify(mediaContract, never()).delete(any());
    }

    @Test
    @DisplayName("19. Duplicate race with no compatible winner: Redundant media requested for cleanup and duplicate error propagates with diagnostics")
    void shouldPropagateDuplicateErrorWhenNoCompatibleWinnerExists() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L); // target revision is 2
        byte[] audioBytes = new byte[]{1, 2, 3};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        UUID staleWinnerAudioId = UUID.fromString("90000000-0000-0000-0000-000000000003");
        UUID staleWinnerMediaId = UUID.fromString("90000000-0000-0000-0000-000000000004");
        ChapterNarrationAudio staleAudio = ChapterNarrationAudio.create(
                staleWinnerAudioId, SEGMENT_ID, VOICE_ID, staleWinnerMediaId, 1L, 51840L, 48000, NOW // revision 1 != 2
        );

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(staleAudio));

        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        when(segmentAudioEncoderPort.encode(any())).thenReturn(createEncodingResult(new byte[]{10, 20}, 51840L, 48000));
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);
        when(idGeneratorPort.generate()).thenReturn(AUDIO_ID);

        ChapterNarrationAudioAlreadyExistsException duplicateEx =
                new ChapterNarrationAudioAlreadyExistsException(SEGMENT_ID, VOICE_ID);
        when(audioRepositoryPort.save(any())).thenThrow(duplicateEx);
        when(cleanupRequestUseCase.execute(MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET))
                .thenReturn(new RequestNarrationMediaCleanupResult(MEDIA_ASSET_ID, NarrationMediaCleanupOutcome.IMMEDIATELY_DELETED));

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(duplicateEx);

        // Redundant media requested for cleanup
        verify(cleanupRequestUseCase).execute(MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        verify(mediaContract, never()).delete(any());
        // Diagnostics recorded
        verify(failureRepositoryPort).findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID);
    }

    @Test
    @DisplayName("19b. Duplicate race with legacy winner (null timing): Loser media requested for cleanup and duplicate error propagates with diagnostics")
    void shouldPropagateDuplicateErrorWhenRaceWinnerIsLegacyWithoutTiming() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(1L); // target revision is 1
        byte[] audioBytes = new byte[]{1, 2, 3};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        UUID legacyWinnerAudioId = UUID.fromString("90000000-0000-0000-0000-000000000005");
        UUID legacyWinnerMediaId = UUID.fromString("90000000-0000-0000-0000-000000000006");
        // Same revision 1L, but legacy null/null timing:
        ChapterNarrationAudio legacyWinnerAudio = ChapterNarrationAudio.create(
                legacyWinnerAudioId, SEGMENT_ID, VOICE_ID, legacyWinnerMediaId, 1L, NOW
        );

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(legacyWinnerAudio));

        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        when(segmentAudioEncoderPort.encode(any())).thenReturn(createEncodingResult(new byte[]{10, 20}, 51840L, 48000));
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);
        when(idGeneratorPort.generate()).thenReturn(AUDIO_ID);

        ChapterNarrationAudioAlreadyExistsException duplicateEx =
                new ChapterNarrationAudioAlreadyExistsException(SEGMENT_ID, VOICE_ID);
        when(audioRepositoryPort.save(any())).thenThrow(duplicateEx);
        when(cleanupRequestUseCase.execute(MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET))
                .thenReturn(new RequestNarrationMediaCleanupResult(MEDIA_ASSET_ID, NarrationMediaCleanupOutcome.IMMEDIATELY_DELETED));

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(duplicateEx);

        // Redundant media requested for cleanup
        verify(cleanupRequestUseCase).execute(MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        verify(mediaContract, never()).delete(any());
        // Diagnostics recorded because legacy winner cannot be reused
        verify(failureRepositoryPort).findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID);
    }

    @Test
    @DisplayName("20. Duplicate race with winner reload failure + cleanup success propagates duplicate exception, suppresses lookup exception, and records diagnostic")
    void shouldPropagateDuplicateExceptionAndSuppressLookupExceptionWhenWinnerReloadThrowsAfterSuccessfulCleanup() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(1L);
        byte[] audioBytes = new byte[]{1, 2, 3};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));

        RuntimeException winnerLookupEx = new RuntimeException("DB error during winner reload query");
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty())
                .thenThrow(winnerLookupEx);

        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        when(segmentAudioEncoderPort.encode(any())).thenReturn(createEncodingResult(new byte[]{10, 20}, 51840L, 48000));
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);
        when(idGeneratorPort.generate()).thenReturn(AUDIO_ID).thenReturn(FAILURE_ID);

        ChapterNarrationAudioAlreadyExistsException duplicateEx =
                new ChapterNarrationAudioAlreadyExistsException(SEGMENT_ID, VOICE_ID);
        when(audioRepositoryPort.save(any())).thenThrow(duplicateEx);
        when(cleanupRequestUseCase.execute(MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET))
                .thenReturn(new RequestNarrationMediaCleanupResult(MEDIA_ASSET_ID, NarrationMediaCleanupOutcome.IMMEDIATELY_DELETED));

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(duplicateEx)
                .hasSuppressedException(winnerLookupEx);

        // Loser cleanup requested exactly once
        verify(cleanupRequestUseCase, times(1)).execute(MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        verify(mediaContract, never()).delete(any());

        // Diagnostic recorded with original persistence error
        verify(failureRepositoryPort).findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID);
    }

    @Test
    @DisplayName("21. Duplicate race with winner reload failure + cleanup TOTAL FAILURE propagates duplicate exception, suppresses both cleanup and lookup exceptions, and records diagnostic")
    void shouldPropagateDuplicateExceptionAndSuppressBothCleanupAndLookupExceptionsWhenBothFail() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(1L);
        byte[] audioBytes = new byte[]{1, 2, 3};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));

        RuntimeException winnerLookupEx = new RuntimeException("DB error during winner reload query");
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty())
                .thenThrow(winnerLookupEx);

        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        when(segmentAudioEncoderPort.encode(any())).thenReturn(createEncodingResult(new byte[]{10, 20}, 51840L, 48000));
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);
        when(idGeneratorPort.generate()).thenReturn(AUDIO_ID).thenReturn(FAILURE_ID);

        ChapterNarrationAudioAlreadyExistsException duplicateEx =
                new ChapterNarrationAudioAlreadyExistsException(SEGMENT_ID, VOICE_ID);
        when(audioRepositoryPort.save(any())).thenThrow(duplicateEx);

        RuntimeException cleanupFailureEx = new RuntimeException("Cleanup completely failed");
        doThrow(cleanupFailureEx).when(cleanupRequestUseCase).execute(MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(duplicateEx)
                .hasSuppressedException(winnerLookupEx)
                .hasSuppressedException(cleanupFailureEx);

        // Loser cleanup requested exactly once
        verify(cleanupRequestUseCase, times(1)).execute(MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        verify(mediaContract, never()).delete(any());

        // Diagnostic recorded with original persistence error
        verify(failureRepositoryPort).findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID);
    }

    @Test
    @DisplayName("22. Propagates pre-upload stream open failure, closes resource, does not call Media, and records AUDIO_ENCODING")
    void shouldRecordAudioEncodingAndCloseResourceWhenOpenStreamFailsPreUpload() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L);
        byte[] audioBytes = new byte[]{1, 2, 3};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        AtomicBoolean resourceClosed = new AtomicBoolean(false);
        RuntimeException openStreamEx = new RuntimeException("Cannot open encoded audio file");
        SegmentAudioEncodingResult encodingResult = createEncodingResult(
                new byte[]{10, 20}, 51840L, 48000,
                resourceClosed, null, openStreamEx, null, null
        );

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty());
        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        when(segmentAudioEncoderPort.encode(any())).thenReturn(encodingResult);
        when(clockPort.now()).thenReturn(NOW);
        when(idGeneratorPort.generate()).thenReturn(FAILURE_ID);

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(openStreamEx);

        assertThat(resourceClosed.get()).isTrue();
        verifyNoInteractions(mediaContract);
        verifyNoInteractions(cleanupRequestUseCase);
        verify(audioRepositoryPort, never()).save(any());

        ArgumentCaptor<ChapterNarrationAudioFailure> failureCaptor = ArgumentCaptor.forClass(ChapterNarrationAudioFailure.class);
        verify(failureRepositoryPort).save(failureCaptor.capture());
        ChapterNarrationAudioFailure failure = failureCaptor.getValue();
        assertThat(failure.getStage()).isEqualTo(NarrationAudioFailureStage.AUDIO_ENCODING);
        assertThat(failure.getOperation()).isEqualTo(NarrationAudioOperation.INITIAL_GENERATION);
        assertThat(failure.getErrorMessage()).isEqualTo("Narration audio encoding failed.");
    }

    @Test
    @DisplayName("22b. Pre-upload stream open failure suppresses resource close failure under primary encoding exception")
    void shouldSuppressResourceCloseFailureUnderPrimaryWhenOpenStreamFails() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L);
        byte[] audioBytes = new byte[]{1, 2, 3};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        RuntimeException openStreamEx = new RuntimeException("Cannot open encoded audio file");
        RuntimeException resourceCloseEx = new RuntimeException("Failed to clean up encoder temp directory");
        SegmentAudioEncodingResult encodingResult = createEncodingResult(
                new byte[]{10, 20}, 51840L, 48000,
                null, null, openStreamEx, null, resourceCloseEx
        );

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty());
        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        when(segmentAudioEncoderPort.encode(any())).thenReturn(encodingResult);
        when(clockPort.now()).thenReturn(NOW);
        when(idGeneratorPort.generate()).thenReturn(FAILURE_ID);

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(openStreamEx)
                .hasSuppressedException(resourceCloseEx);

        verifyNoInteractions(mediaContract);
        verifyNoInteractions(cleanupRequestUseCase);
        verify(audioRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("23. During-upload failure preserves Media exception as primary and attaches stream/resource close failure as suppressed")
    void shouldPreserveMediaExceptionAsPrimaryAndAttachCloseFailureAsSuppressedWhenUploadFails() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(1L);
        byte[] audioBytes = new byte[]{1, 2, 3};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        IOException streamCloseEx = new IOException("Broken pipe closing stream");
        RuntimeException resourceCloseEx = new RuntimeException("Failed to delete temp file");
        SegmentAudioEncodingResult encodingResult = createEncodingResult(
                new byte[]{10, 20}, 51840L, 48000,
                null, null, null, streamCloseEx, resourceCloseEx
        );

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty());
        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        when(segmentAudioEncoderPort.encode(any())).thenReturn(encodingResult);

        RuntimeException mediaEx = new RuntimeException("Media storage connection timed out");
        when(mediaContract.uploadAsset(any())).thenThrow(mediaEx);
        when(clockPort.now()).thenReturn(NOW);
        when(idGeneratorPort.generate()).thenReturn(FAILURE_ID);

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(mediaEx)
                .hasSuppressedException(streamCloseEx)
                .hasSuppressedException(resourceCloseEx);

        verify(audioRepositoryPort, never()).save(any());
        verifyNoInteractions(cleanupRequestUseCase);

        ArgumentCaptor<ChapterNarrationAudioFailure> failureCaptor = ArgumentCaptor.forClass(ChapterNarrationAudioFailure.class);
        verify(failureRepositoryPort).save(failureCaptor.capture());
        assertThat(failureCaptor.getValue().getStage()).isEqualTo(NarrationAudioFailureStage.MEDIA_UPLOAD);
    }

    @Test
    @DisplayName("24. Post-upload InputStream close failure logs warning and continues with persistence without requesting cleanup")
    void shouldContinueWithPersistenceWhenPostUploadStreamCloseFails() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(1L);
        byte[] audioBytes = new byte[]{1, 2, 3};
        byte[] mp3Bytes = new byte[]{10, 20, 30};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        IOException streamCloseEx = new IOException("Stream flush error on close");
        SegmentAudioEncodingResult encodingResult = createEncodingResult(
                mp3Bytes, 51840L, 48000,
                null, null, null, streamCloseEx, null
        );

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty());
        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        when(segmentAudioEncoderPort.encode(any())).thenReturn(encodingResult);
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);
        when(idGeneratorPort.generate()).thenReturn(AUDIO_ID);

        ChapterNarrationAudio savedAudio = ChapterNarrationAudio.create(
                AUDIO_ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 1L, 51840L, 48000, NOW
        );
        when(audioRepositoryPort.save(any())).thenReturn(savedAudio);

        GenerateChapterNarrationAudioResult result = useCase.execute(SEGMENT_ID, VOICE_ID);

        assertThat(result.outcome()).isEqualTo(NarrationAudioGenerationOutcome.GENERATED);
        assertThat(result.assignmentId()).isEqualTo(AUDIO_ID);
        assertThat(result.mediaAssetId()).isEqualTo(MEDIA_ASSET_ID);

        verify(audioRepositoryPort).save(any(ChapterNarrationAudio.class));
        verifyNoInteractions(cleanupRequestUseCase);
    }

    @Test
    @DisplayName("25. Post-upload encodedResult close failure logs warning and continues with persistence without requesting cleanup")
    void shouldContinueWithPersistenceWhenPostUploadResourceCloseFails() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(1L);
        byte[] audioBytes = new byte[]{1, 2, 3};
        byte[] mp3Bytes = new byte[]{10, 20, 30};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        RuntimeException resourceCloseEx = new RuntimeException("Temp file locked by virus scanner");
        SegmentAudioEncodingResult encodingResult = createEncodingResult(
                mp3Bytes, 51840L, 48000,
                null, null, null, null, resourceCloseEx
        );

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty());
        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        when(segmentAudioEncoderPort.encode(any())).thenReturn(encodingResult);
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);
        when(idGeneratorPort.generate()).thenReturn(AUDIO_ID);

        ChapterNarrationAudio savedAudio = ChapterNarrationAudio.create(
                AUDIO_ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 1L, 51840L, 48000, NOW
        );
        when(audioRepositoryPort.save(any())).thenReturn(savedAudio);

        GenerateChapterNarrationAudioResult result = useCase.execute(SEGMENT_ID, VOICE_ID);

        assertThat(result.outcome()).isEqualTo(NarrationAudioGenerationOutcome.GENERATED);
        assertThat(result.assignmentId()).isEqualTo(AUDIO_ID);
        assertThat(result.mediaAssetId()).isEqualTo(MEDIA_ASSET_ID);

        verify(audioRepositoryPort).save(any(ChapterNarrationAudio.class));
        verifyNoInteractions(cleanupRequestUseCase);
    }

    @Test
    @DisplayName("26. Post-upload clockPort.now() failure requests durable Media cleanup, records ASSIGNMENT_PERSISTENCE, and does not attempt winner reload")
    void shouldRequestDurableCleanupAndRecordAssignmentPersistenceWhenClockFailsPostUpload() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(1L);
        byte[] audioBytes = new byte[]{1, 2, 3};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty());
        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        when(segmentAudioEncoderPort.encode(any())).thenReturn(createEncodingResult(new byte[]{10, 20}, 51840L, 48000));
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(MEDIA_ASSET_ID));

        RuntimeException clockEx = new RuntimeException("System NTP synchronized clock skew");
        when(clockPort.now()).thenThrow(clockEx).thenReturn(NOW);
        when(idGeneratorPort.generate()).thenReturn(FAILURE_ID);
        when(cleanupRequestUseCase.execute(MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET))
                .thenReturn(new RequestNarrationMediaCleanupResult(MEDIA_ASSET_ID, NarrationMediaCleanupOutcome.IMMEDIATELY_DELETED));

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(clockEx);

        verify(cleanupRequestUseCase).execute(MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        verify(audioRepositoryPort, never()).save(any());
        verify(audioRepositoryPort, times(1)).findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID);

        ArgumentCaptor<ChapterNarrationAudioFailure> failureCaptor = ArgumentCaptor.forClass(ChapterNarrationAudioFailure.class);
        verify(failureRepositoryPort).save(failureCaptor.capture());
        assertThat(failureCaptor.getValue().getStage()).isEqualTo(NarrationAudioFailureStage.ASSIGNMENT_PERSISTENCE);
    }

    @Test
    @DisplayName("27. Post-upload idGeneratorPort.generate() failure requests durable Media cleanup, records ASSIGNMENT_PERSISTENCE, and does not attempt winner reload")
    void shouldRequestDurableCleanupAndRecordAssignmentPersistenceWhenIdGeneratorFailsPostUpload() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(1L);
        byte[] audioBytes = new byte[]{1, 2, 3};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty());
        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        when(segmentAudioEncoderPort.encode(any())).thenReturn(createEncodingResult(new byte[]{10, 20}, 51840L, 48000));
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);

        RuntimeException idGenEx = new RuntimeException("UUID generator exhausted");
        when(idGeneratorPort.generate()).thenThrow(idGenEx).thenReturn(FAILURE_ID);
        when(cleanupRequestUseCase.execute(MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET))
                .thenReturn(new RequestNarrationMediaCleanupResult(MEDIA_ASSET_ID, NarrationMediaCleanupOutcome.IMMEDIATELY_DELETED));

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(idGenEx);

        verify(cleanupRequestUseCase).execute(MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        verify(audioRepositoryPort, never()).save(any());
        verify(audioRepositoryPort, times(1)).findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID);

        ArgumentCaptor<ChapterNarrationAudioFailure> failureCaptor = ArgumentCaptor.forClass(ChapterNarrationAudioFailure.class);
        verify(failureRepositoryPort).save(failureCaptor.capture());
        assertThat(failureCaptor.getValue().getStage()).isEqualTo(NarrationAudioFailureStage.ASSIGNMENT_PERSISTENCE);
    }

    @Test
    @DisplayName("28. Cleanup handoff failure during post-upload assignment preparation preserves preparation exception and attaches cleanup as suppressed")
    void shouldPreservePreparationExceptionAndAttachCleanupAsSuppressedWhenBothFail() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(1L);
        byte[] audioBytes = new byte[]{1, 2, 3};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty());
        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        when(segmentAudioEncoderPort.encode(any())).thenReturn(createEncodingResult(new byte[]{10, 20}, 51840L, 48000));
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(MEDIA_ASSET_ID));

        RuntimeException clockEx = new RuntimeException("Clock provider dead");
        when(clockPort.now()).thenThrow(clockEx).thenReturn(NOW);
        when(idGeneratorPort.generate()).thenReturn(FAILURE_ID);

        RuntimeException cleanupEx = new RuntimeException("Cleanup service unreachable");
        doThrow(cleanupEx).when(cleanupRequestUseCase).execute(MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(clockEx)
                .hasSuppressedException(cleanupEx);

        verify(cleanupRequestUseCase).execute(MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        verify(audioRepositoryPort, never()).save(any());
        verify(failureRepositoryPort).save(any(ChapterNarrationAudioFailure.class));
    }
}
