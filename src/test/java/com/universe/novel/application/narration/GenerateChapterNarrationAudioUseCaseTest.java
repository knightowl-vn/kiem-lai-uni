package com.universe.novel.application.narration;

import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.dto.UploadMediaAssetRequestDTO;
import com.universe.media.contracts.dto.UploadMediaAssetResponseDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.novel.application.exceptions.ChapterNarrationSegmentInvalidStateException;
import com.universe.novel.application.exceptions.ChapterNarrationSegmentNotFoundException;
import com.universe.novel.application.exceptions.ManagedVoiceInvalidStateException;
import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.ports.ChapterNarrationAudioRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationSegmentRepositoryPort;
import com.universe.novel.application.ports.ManagedVoiceRepositoryPort;
import com.universe.novel.application.ports.TtsProviderPort;
import com.universe.novel.domain.narration.ChapterNarrationAudio;
import com.universe.novel.domain.narration.ChapterNarrationSegment;
import com.universe.novel.domain.narration.ChapterNarrationSegmentStatus;
import com.universe.novel.domain.narration.ManagedVoice;
import com.universe.novel.domain.narration.ManagedVoiceStatus;
import com.universe.novel.domain.narration.NarrationTextSegment;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.never;
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
    private TtsProviderPort ttsProviderPort;

    @Mock
    private MediaContract mediaContract;

    @Mock
    private IdGeneratorPort idGeneratorPort;

    @Mock
    private ClockPort clockPort;

    private GenerateChapterNarrationAudioUseCase useCase;

    private static final UUID SEGMENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CHAPTER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID VOICE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID AUDIO_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID MEDIA_ASSET_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final Instant NOW = Instant.parse("2026-09-05T14:00:00Z");

    @BeforeEach
    void setUp() {
        useCase = new GenerateChapterNarrationAudioUseCase(
                segmentRepositoryPort,
                managedVoiceRepositoryPort,
                audioRepositoryPort,
                ttsProviderPort,
                mediaContract,
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
    }

    @Test
    @DisplayName("10. Compensates media asset deletion when audio database persistence fails")
    void shouldCompensateMediaAssetWhenAudioPersistenceFails() {
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

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(dbException);

        verify(mediaContract).delete(MEDIA_ASSET_ID);
    }

    @Test
    @DisplayName("11. Preserves primary persistence exception and attaches suppressed exception when media compensation fails")
    void shouldAttachSuppressedExceptionWhenMediaCompensationFails() {
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
        RuntimeException compException = new RuntimeException("Media service unreachable during cleanup");

        when(audioRepositoryPort.save(any())).thenThrow(dbException);
        doThrow(compException).when(mediaContract).delete(MEDIA_ASSET_ID);

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(dbException)
                .hasSuppressedException(compException);

        verify(mediaContract).delete(MEDIA_ASSET_ID);
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
}
