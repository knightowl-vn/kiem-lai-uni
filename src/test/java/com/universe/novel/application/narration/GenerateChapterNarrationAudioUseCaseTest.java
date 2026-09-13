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

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

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
                mediaContract,
                cleanupRequestUseCase,
                idGeneratorPort,
                clockPort
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
    @DisplayName("1. Reuses existing audio assignment when compatible without invoking TTS or Media or DB save")
    void shouldReuseExistingCompatibleAudioAssignmentWithoutTtsOrMediaOrDbCall() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L);
        ChapterNarrationAudio existingAudio = ChapterNarrationAudio.create(
                AUDIO_ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 2L, NOW
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
                AUDIO_ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 2L, NOW // Audio was generated at revision 2
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
        verifyNoInteractions(mediaContract);
        verifyNoInteractions(cleanupRequestUseCase);
        verify(audioRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("3. Generates audio via TTS, uploads to Media, and persists assignment when none exists")
    void shouldGenerateAudioAndStoreMediaAndPersistWhenNoAssignmentExists() throws IOException {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(1L);
        byte[] audioBytes = new byte[]{82, 73, 70, 70, 1, 2, 3}; // RIFF...
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/wav");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty());

        when(ttsProviderPort.synthesize(new TtsSynthesisCommand("Trần Bình An cất bước ra đi.", "minh-duc")))
                .thenReturn(ttsResult);

        when(mediaContract.uploadAsset(any(UploadMediaAssetRequestDTO.class)))
                .thenReturn(new UploadMediaAssetResponseDTO(MEDIA_ASSET_ID));

        when(clockPort.now()).thenReturn(NOW);
        when(idGeneratorPort.generate()).thenReturn(AUDIO_ID);

        ChapterNarrationAudio savedAudio = ChapterNarrationAudio.create(
                AUDIO_ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 1L, NOW
        );
        when(audioRepositoryPort.save(any(ChapterNarrationAudio.class))).thenReturn(savedAudio);

        GenerateChapterNarrationAudioResult result = useCase.execute(new GenerateChapterNarrationAudioCommand(SEGMENT_ID, VOICE_ID));

        assertThat(result.assignmentId()).isEqualTo(AUDIO_ID);
        assertThat(result.segmentId()).isEqualTo(SEGMENT_ID);
        assertThat(result.managedVoiceId()).isEqualTo(VOICE_ID);
        assertThat(result.mediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
        assertThat(result.generatedSynthesisRevision()).isEqualTo(1L);
        assertThat(result.outcome()).isEqualTo(NarrationAudioGenerationOutcome.GENERATED);

        // Verify TTS call
        verify(ttsProviderPort).synthesize(new TtsSynthesisCommand("Trần Bình An cất bước ra đi.", "minh-duc"));

        // Verify Media upload payload
        ArgumentCaptor<UploadMediaAssetRequestDTO> uploadCaptor = ArgumentCaptor.forClass(UploadMediaAssetRequestDTO.class);
        verify(mediaContract).uploadAsset(uploadCaptor.capture());
        UploadMediaAssetRequestDTO capturedUpload = uploadCaptor.getValue();
        assertThat(capturedUpload.sizeBytes()).isEqualTo(audioBytes.length);
        assertThat(capturedUpload.mimeType()).isEqualTo("audio/wav");
        assertThat(capturedUpload.mediaType()).isEqualTo(MediaTypeDTO.AUDIO);
        assertThat(capturedUpload.visibility()).isEqualTo(MediaVisibilityDTO.PUBLIC);
        assertThat(capturedUpload.originalFilename()).isEqualTo("segment-" + SEGMENT_ID + ".wav");
        assertThat(capturedUpload.content().readAllBytes()).isEqualTo(audioBytes);

        // Verify persistence
        ArgumentCaptor<ChapterNarrationAudio> audioCaptor = ArgumentCaptor.forClass(ChapterNarrationAudio.class);
        verify(audioRepositoryPort).save(audioCaptor.capture());
        ChapterNarrationAudio capturedAudio = audioCaptor.getValue();
        assertThat(capturedAudio.getId()).isEqualTo(AUDIO_ID);
        assertThat(capturedAudio.getSegmentId()).isEqualTo(SEGMENT_ID);
        assertThat(capturedAudio.getManagedVoiceId()).isEqualTo(VOICE_ID);
        assertThat(capturedAudio.getMediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
        assertThat(capturedAudio.getGeneratedSynthesisRevision()).isEqualTo(1L);

        // Verify failure cleared on success with generated revision
        verify(failureRepositoryPort).deleteSupersededBySuccessfulRevision(SEGMENT_ID, VOICE_ID, 1L);
        verifyNoInteractions(cleanupRequestUseCase);
    }

    @Test
    @DisplayName("3b. Derives .mp3 filename when TTS returns audio/mpeg")
    void shouldDeriveMp3FilenameForMpegMimeType() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(1L);
        byte[] audioBytes = new byte[]{1, 2, 3, 4};
        TtsSynthesisResult ttsResult = new TtsSynthesisResult(audioBytes, "audio/mpeg");

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty());

        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);
        when(idGeneratorPort.generate()).thenReturn(AUDIO_ID);

        ChapterNarrationAudio savedAudio = ChapterNarrationAudio.create(
                AUDIO_ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 1L, NOW
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

        verifyNoInteractions(mediaContract);
        verifyNoInteractions(cleanupRequestUseCase);
        verify(audioRepositoryPort, never()).save(any());
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
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);
        when(idGeneratorPort.generate()).thenReturn(AUDIO_ID);

        ChapterNarrationAudio savedAudio = ChapterNarrationAudio.create(
                AUDIO_ID, SEGMENT_ID, VOICE_ID, MEDIA_ASSET_ID, 1L, NOW
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
                winnerAudioId, SEGMENT_ID, VOICE_ID, winnerMediaId, 1L, NOW
        );

        // First check in useCase: empty
        // Second check in catch block after duplicate exception: winningAudio
        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winningAudio));

        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
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
                winnerAudioId, SEGMENT_ID, VOICE_ID, winnerMediaId, 1L, NOW
        );

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winningAudio));

        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
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
                staleWinnerAudioId, SEGMENT_ID, VOICE_ID, staleWinnerMediaId, 1L, NOW // revision 1 != 2
        );

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(staleAudio));

        when(ttsProviderPort.synthesize(any())).thenReturn(ttsResult);
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
}
