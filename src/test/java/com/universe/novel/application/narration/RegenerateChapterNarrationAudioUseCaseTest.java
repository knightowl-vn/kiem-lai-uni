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
    private MediaContract mediaContract;

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

    @BeforeEach
    void setUp() {
        useCase = new RegenerateChapterNarrationAudioUseCase(
                segmentRepositoryPort,
                managedVoiceRepositoryPort,
                audioRepositoryPort,
                failureRepositoryPort,
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

    @Test
    @DisplayName("1. Compatible assignment: Returns ALREADY_CURRENT without TTS, Media writes, or database mutation")
    void shouldReturnAlreadyCurrentWhenAssignmentIsAlreadyCompatible() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(2L);
        ChapterNarrationAudio audio = createExistingAudio(2L); // Already revision 2

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
        verifyNoInteractions(mediaContract);
        verify(audioRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("2. Stale assignment: Synthesizes replacement audio, uploads new Media asset, updates assignment, and deletes old Media asset")
    void shouldRegenerateStaleAssignmentSuccessfully() throws IOException {
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

        when(mediaContract.uploadAsset(any(UploadMediaAssetRequestDTO.class)))
                .thenReturn(new UploadMediaAssetResponseDTO(NEW_MEDIA_ASSET_ID));

        when(clockPort.now()).thenReturn(NOW);
        when(audioRepositoryPort.save(any(ChapterNarrationAudio.class))).thenAnswer(invocation -> invocation.getArgument(0));

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

        // Verify persistence of the same assignment identity
        ArgumentCaptor<ChapterNarrationAudio> audioCaptor = ArgumentCaptor.forClass(ChapterNarrationAudio.class);
        verify(audioRepositoryPort).save(audioCaptor.capture());
        ChapterNarrationAudio capturedAudio = audioCaptor.getValue();
        assertThat(capturedAudio.getId()).isEqualTo(AUDIO_ID);
        assertThat(capturedAudio.getMediaAssetId()).isEqualTo(NEW_MEDIA_ASSET_ID);
        assertThat(capturedAudio.getGeneratedSynthesisRevision()).isEqualTo(2L);
        assertThat(capturedAudio.getUpdatedAt()).isEqualTo(NOW);

        // Verify old Media asset deleted after successful save
        verify(mediaContract).delete(OLD_MEDIA_ASSET_ID);
        // Ensure new asset was NEVER deleted
        verify(mediaContract, never()).delete(NEW_MEDIA_ASSET_ID);

        // Verify failure cleared on success
        verify(failureRepositoryPort).deleteBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID);
    }

    @Test
    @DisplayName("3. TTS failure: Old assignment and old Media asset remain untouched")
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

        verifyNoInteractions(mediaContract);
        verify(audioRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("4. Media upload failure: Old assignment remains untouched and no save occurs")
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

        RuntimeException mediaEx = new RuntimeException("Media storage network error");
        when(mediaContract.uploadAsset(any())).thenThrow(mediaEx);

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(mediaEx);

        assertThat(audio.getMediaAssetId()).isEqualTo(OLD_MEDIA_ASSET_ID);
        assertThat(audio.getGeneratedSynthesisRevision()).isEqualTo(1L);

        verify(audioRepositoryPort, never()).save(any());
        verify(mediaContract, never()).delete(any());
    }

    @Test
    @DisplayName("5. Persistence failure after new upload: Compensates NEW Media asset, preserves OLD asset and domain state")
    void shouldCompensateNewMediaAssetAndPreserveOldAssetWhenPersistenceFails() {
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
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(NEW_MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);

        RuntimeException dbEx = new RuntimeException("DB transaction deadlock");
        when(audioRepositoryPort.save(any())).thenThrow(dbEx);

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(dbEx);

        // In-memory assignment state is reverted to previous state
        assertThat(audio.getMediaAssetId()).isEqualTo(OLD_MEDIA_ASSET_ID);
        assertThat(audio.getGeneratedSynthesisRevision()).isEqualTo(1L);

        // Verify compensation: NEW asset is deleted, OLD asset is NOT deleted
        verify(mediaContract).delete(NEW_MEDIA_ASSET_ID);
        verify(mediaContract, never()).delete(OLD_MEDIA_ASSET_ID);
    }

    @Test
    @DisplayName("6. NEW-asset compensation failure: Preserves primary persistence exception and attaches compensation error as suppressed")
    void shouldAttachSuppressedExceptionWhenCompensationFails() {
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
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(NEW_MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);

        RuntimeException dbEx = new RuntimeException("DB connection dropped");
        RuntimeException compEx = new RuntimeException("Media delete failed during compensation");

        when(audioRepositoryPort.save(any())).thenThrow(dbEx);
        doThrow(compEx).when(mediaContract).delete(NEW_MEDIA_ASSET_ID);

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(dbEx)
                .hasSuppressedException(compEx);

        // OLD asset untouched
        verify(mediaContract, never()).delete(OLD_MEDIA_ASSET_ID);
    }

    @Test
    @DisplayName("7. Old Media cleanup failure after successful switch: New assignment remains current without rollback")
    void shouldKeepNewAssignmentCurrentWhenOldMediaCleanupFails() {
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
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(NEW_MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);
        when(audioRepositoryPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Old media cleanup throws
        RuntimeException oldMediaCleanupEx = new RuntimeException("Media contract delete timed out");
        doThrow(oldMediaCleanupEx).when(mediaContract).delete(OLD_MEDIA_ASSET_ID);

        RegenerateChapterNarrationAudioResult result = useCase.execute(SEGMENT_ID, VOICE_ID);

        // Operation still succeeds with REGENERATED
        assertThat(result.outcome()).isEqualTo(RegenerateNarrationAudioOutcome.REGENERATED);
        assertThat(result.currentMediaAssetId()).isEqualTo(NEW_MEDIA_ASSET_ID);
        assertThat(result.previousMediaAssetId()).isEqualTo(OLD_MEDIA_ASSET_ID);

        // New asset is never deleted
        verify(mediaContract, never()).delete(NEW_MEDIA_ASSET_ID);
    }

    @Test
    @DisplayName("8. Missing assignment rejected: Throws ChapterNarrationAudioNotFoundException")
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
        verifyNoInteractions(mediaContract);
        verify(audioRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("9. Retired segment rejected: Throws ChapterNarrationSegmentInvalidStateException")
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
        verifyNoInteractions(mediaContract);
    }

    @Test
    @DisplayName("10. Disabled voice rejected: Throws ManagedVoiceInvalidStateException")
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
        verifyNoInteractions(mediaContract);
    }

    @Test
    @DisplayName("11. Segment not found rejected: Throws ChapterNarrationSegmentNotFoundException")
    void shouldThrowWhenSegmentNotFound() {
        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isInstanceOf(ChapterNarrationSegmentNotFoundException.class);

        verifyNoInteractions(managedVoiceRepositoryPort);
        verifyNoInteractions(ttsProviderPort);
        verifyNoInteractions(mediaContract);
    }

    @Test
    @DisplayName("12. Voice not found rejected: Throws ManagedVoiceNotFoundException")
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
    @DisplayName("13. Assignment ID remains stable across regeneration")
    void shouldPreserveAssignmentIdAcrossRegeneration() {
        ChapterNarrationSegment segment = createCurrentSegment();
        ManagedVoice voice = createActiveVoice(3L);
        ChapterNarrationAudio audio = createExistingAudio(1L);

        when(segmentRepositoryPort.findById(SEGMENT_ID)).thenReturn(Optional.of(segment));
        when(managedVoiceRepositoryPort.findById(VOICE_ID)).thenReturn(Optional.of(voice));
        when(audioRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.of(audio));

        when(ttsProviderPort.synthesize(any())).thenReturn(new TtsSynthesisResult(new byte[]{1, 2}, "audio/mpeg"));
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(NEW_MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);
        when(audioRepositoryPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        RegenerateChapterNarrationAudioResult result = useCase.execute(SEGMENT_ID, VOICE_ID);

        assertThat(result.assignmentId()).isEqualTo(AUDIO_ID);
        assertThat(result.outcome()).isEqualTo(RegenerateNarrationAudioOutcome.REGENERATED);
    }

    @Test
    @DisplayName("14. Validates null inputs")
    void shouldRejectNullInputs() {
        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> useCase.execute(null, VOICE_ID))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("15. Records failure record with REGENERATION operation when TTS synthesis fails during regeneration")
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
    @DisplayName("16. Updates failure record when Media upload fails during regeneration")
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
    @DisplayName("17. Records failure when assignment persistence fails during regeneration and compensates new media")
    void shouldRecordFailureWhenPersistenceFailsDuringRegeneration() {
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
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(NEW_MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);
        when(idGeneratorPort.generate()).thenReturn(FAILURE_ID);
        when(failureRepositoryPort.findBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID))
                .thenReturn(Optional.empty());

        RuntimeException dbEx = new RuntimeException("Lock wait timeout");
        when(audioRepositoryPort.save(any())).thenThrow(dbEx);

        assertThatThrownBy(() -> useCase.execute(SEGMENT_ID, VOICE_ID))
                .isSameAs(dbEx);

        // Compensated new media asset
        verify(mediaContract).delete(NEW_MEDIA_ASSET_ID);

        // Saved failure diagnostic
        ArgumentCaptor<ChapterNarrationAudioFailure> failureCaptor = ArgumentCaptor.forClass(ChapterNarrationAudioFailure.class);
        verify(failureRepositoryPort).save(failureCaptor.capture());
        ChapterNarrationAudioFailure failure = failureCaptor.getValue();
        assertThat(failure.getStage()).isEqualTo(NarrationAudioFailureStage.ASSIGNMENT_PERSISTENCE);
        assertThat(failure.getOperation()).isEqualTo(NarrationAudioOperation.REGENERATION);
        assertThat(failure.getErrorMessage()).isEqualTo("Narration audio assignment persistence failed.");
    }

    @Test
    @DisplayName("18. Successful regeneration completes even when clearing failure diagnostics throws an exception")
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
        when(mediaContract.uploadAsset(any())).thenReturn(new UploadMediaAssetResponseDTO(NEW_MEDIA_ASSET_ID));
        when(clockPort.now()).thenReturn(NOW);
        when(audioRepositoryPort.save(any(ChapterNarrationAudio.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Deleting failure diagnostic throws
        doThrow(new RuntimeException("Failure repo DB unreachable during cleanup"))
                .when(failureRepositoryPort).deleteBySegmentIdAndManagedVoiceId(SEGMENT_ID, VOICE_ID);

        RegenerateChapterNarrationAudioResult result = useCase.execute(SEGMENT_ID, VOICE_ID);

        assertThat(result.outcome()).isEqualTo(RegenerateNarrationAudioOutcome.REGENERATED);
        assertThat(result.assignmentId()).isEqualTo(AUDIO_ID);
        assertThat(result.currentMediaAssetId()).isEqualTo(NEW_MEDIA_ASSET_ID);
        assertThat(result.previousMediaAssetId()).isEqualTo(OLD_MEDIA_ASSET_ID);

        // NEW Media asset is NOT deleted
        verify(mediaContract, never()).delete(NEW_MEDIA_ASSET_ID);
        // OLD Media asset is deleted
        verify(mediaContract).delete(OLD_MEDIA_ASSET_ID);
    }

    @Test
    @DisplayName("19. Clock failure during regeneration diagnostic recording does not mask primary TTS exception and is attached as suppressed")
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
    }
}
