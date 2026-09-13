package com.universe.novel.application.narration;

import com.universe.novel.application.ports.ChapterNarrationAudioRepositoryPort;
import com.universe.novel.application.ports.ChapterNarrationSegmentRepositoryPort;
import com.universe.novel.domain.narration.ChapterNarrationAudio;
import com.universe.novel.domain.narration.ChapterNarrationSegment;
import com.universe.novel.domain.narration.ChapterNarrationSegmentStatus;
import com.universe.novel.domain.narration.NarrationMediaCleanupReason;
import com.universe.novel.domain.narration.NarrationMediaCleanupTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("HandoffRetiredNarrationAudioCleanupUseCase Unit Tests")
class HandoffRetiredNarrationAudioCleanupUseCaseTest {

    private static final UUID AUDIO_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SEGMENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CHAPTER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID VOICE_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID MEDIA_ASSET_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final Instant T0 = Instant.parse("2026-09-06T10:00:00Z");

    @Mock
    private ChapterNarrationAudioRepositoryPort audioRepositoryPort;

    @Mock
    private ChapterNarrationSegmentRepositoryPort segmentRepositoryPort;

    @Mock
    private EnqueueNarrationMediaCleanupUseCase enqueueCleanupUseCase;

    private HandoffRetiredNarrationAudioCleanupUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new HandoffRetiredNarrationAudioCleanupUseCase(
                audioRepositoryPort,
                segmentRepositoryPort,
                enqueueCleanupUseCase
        );
    }

    private ChapterNarrationAudio createAudio() {
        return ChapterNarrationAudio.create(
                AUDIO_ID,
                SEGMENT_ID,
                VOICE_ID,
                MEDIA_ASSET_ID,
                1L,
                T0
        );
    }

    private ChapterNarrationSegment createSegment(ChapterNarrationSegmentStatus status) {
        ChapterNarrationSegment segment = ChapterNarrationSegment.create(
                SEGMENT_ID,
                CHAPTER_ID,
                0,
                "Đoạn văn bản",
                T0
        );
        if (status == ChapterNarrationSegmentStatus.RETIRED) {
            segment.retire(T0);
        }
        return segment;
    }

    @Test
    @DisplayName("1. Null narrationAudioId or command is rejected")
    void shouldRejectNullInput() {
        assertThatThrownBy(() -> useCase.execute((UUID) null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> useCase.execute((HandoffRetiredNarrationAudioCleanupCommand) null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("2. Already absent audio returns ALREADY_ABSENT without locking segment or calling cleanup")
    void shouldReturnAlreadyAbsentWhenAudioNotFound() {
        when(audioRepositoryPort.findById(AUDIO_ID)).thenReturn(Optional.empty());

        HandoffRetiredNarrationAudioCleanupResult result = useCase.execute(AUDIO_ID);

        assertThat(result.narrationAudioId()).isEqualTo(AUDIO_ID);
        assertThat(result.segmentId()).isNull();
        assertThat(result.mediaAssetId()).isNull();
        assertThat(result.outcome()).isEqualTo(HandoffRetiredNarrationAudioCleanupOutcome.ALREADY_ABSENT);
        assertThat(result.isAlreadyAbsent()).isTrue();
        assertThat(result.isHandedOff()).isFalse();

        verifyNoInteractions(segmentRepositoryPort);
        verifyNoInteractions(enqueueCleanupUseCase);
        verify(audioRepositoryPort, never()).deleteById(any());
    }

    @Test
    @DisplayName("3. RETIRED segment + exclusive Media reference -> enqueues cleanup, deletes audio assignment, returns HANDED_OFF")
    void shouldHandoffSuccessfullyForRetiredSegmentWithExclusiveMedia() {
        ChapterNarrationAudio audio = createAudio();
        ChapterNarrationSegment retiredSegment = createSegment(ChapterNarrationSegmentStatus.RETIRED);
        NarrationMediaCleanupTask dummyTask = NarrationMediaCleanupTask.create(
                UUID.randomUUID(), MEDIA_ASSET_ID, NarrationMediaCleanupReason.OBSOLETE_RETIRED_SEGMENT_AUDIO, T0
        );

        when(audioRepositoryPort.findById(AUDIO_ID)).thenReturn(Optional.of(audio));
        when(segmentRepositoryPort.findByIdForUpdate(SEGMENT_ID)).thenReturn(Optional.of(retiredSegment));
        when(audioRepositoryPort.existsOtherReferenceToMediaAsset(MEDIA_ASSET_ID, AUDIO_ID)).thenReturn(false);
        when(enqueueCleanupUseCase.execute(MEDIA_ASSET_ID, NarrationMediaCleanupReason.OBSOLETE_RETIRED_SEGMENT_AUDIO))
                .thenReturn(dummyTask);

        HandoffRetiredNarrationAudioCleanupResult result = useCase.execute(new HandoffRetiredNarrationAudioCleanupCommand(AUDIO_ID));

        assertThat(result.narrationAudioId()).isEqualTo(AUDIO_ID);
        assertThat(result.segmentId()).isEqualTo(SEGMENT_ID);
        assertThat(result.mediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
        assertThat(result.outcome()).isEqualTo(HandoffRetiredNarrationAudioCleanupOutcome.HANDED_OFF);
        assertThat(result.isHandedOff()).isTrue();

        InOrder inOrder = inOrder(segmentRepositoryPort, audioRepositoryPort, enqueueCleanupUseCase);
        inOrder.verify(segmentRepositoryPort).findByIdForUpdate(SEGMENT_ID);
        inOrder.verify(audioRepositoryPort).existsOtherReferenceToMediaAsset(MEDIA_ASSET_ID, AUDIO_ID);
        inOrder.verify(enqueueCleanupUseCase).execute(MEDIA_ASSET_ID, NarrationMediaCleanupReason.OBSOLETE_RETIRED_SEGMENT_AUDIO);
        inOrder.verify(audioRepositoryPort).deleteById(AUDIO_ID);
    }

    @Test
    @DisplayName("4. CURRENT segment -> returns SKIPPED_NOT_RETIRED, no cleanup enqueue, no delete")
    void shouldSkipWhenSegmentIsCurrent() {
        ChapterNarrationAudio audio = createAudio();
        ChapterNarrationSegment currentSegment = createSegment(ChapterNarrationSegmentStatus.CURRENT);

        when(audioRepositoryPort.findById(AUDIO_ID)).thenReturn(Optional.of(audio));
        when(segmentRepositoryPort.findByIdForUpdate(SEGMENT_ID)).thenReturn(Optional.of(currentSegment));

        HandoffRetiredNarrationAudioCleanupResult result = useCase.execute(AUDIO_ID);

        assertThat(result.narrationAudioId()).isEqualTo(AUDIO_ID);
        assertThat(result.segmentId()).isEqualTo(SEGMENT_ID);
        assertThat(result.mediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
        assertThat(result.outcome()).isEqualTo(HandoffRetiredNarrationAudioCleanupOutcome.SKIPPED_NOT_RETIRED);
        assertThat(result.isSkipped()).isTrue();
        assertThat(result.isHandedOff()).isFalse();

        verify(segmentRepositoryPort).findByIdForUpdate(SEGMENT_ID);
        verify(audioRepositoryPort, never()).existsOtherReferenceToMediaAsset(any(), any());
        verifyNoInteractions(enqueueCleanupUseCase);
        verify(audioRepositoryPort, never()).deleteById(any());
    }

    @Test
    @DisplayName("5. Missing locked segment -> returns SKIPPED_NOT_RETIRED, no cleanup enqueue, no delete")
    void shouldSkipWhenLockedSegmentNotFound() {
        ChapterNarrationAudio audio = createAudio();

        when(audioRepositoryPort.findById(AUDIO_ID)).thenReturn(Optional.of(audio));
        when(segmentRepositoryPort.findByIdForUpdate(SEGMENT_ID)).thenReturn(Optional.empty());

        HandoffRetiredNarrationAudioCleanupResult result = useCase.execute(AUDIO_ID);

        assertThat(result.outcome()).isEqualTo(HandoffRetiredNarrationAudioCleanupOutcome.SKIPPED_NOT_RETIRED);
        assertThat(result.isSkipped()).isTrue();

        verifyNoInteractions(enqueueCleanupUseCase);
        verify(audioRepositoryPort, never()).deleteById(any());
    }

    @Test
    @DisplayName("6. Media asset referenced by another audio assignment -> returns SKIPPED_SHARED_MEDIA_REFERENCE, no cleanup enqueue, no delete")
    void shouldSkipWhenMediaAssetIsShared() {
        ChapterNarrationAudio audio = createAudio();
        ChapterNarrationSegment retiredSegment = createSegment(ChapterNarrationSegmentStatus.RETIRED);

        when(audioRepositoryPort.findById(AUDIO_ID)).thenReturn(Optional.of(audio));
        when(segmentRepositoryPort.findByIdForUpdate(SEGMENT_ID)).thenReturn(Optional.of(retiredSegment));
        when(audioRepositoryPort.existsOtherReferenceToMediaAsset(MEDIA_ASSET_ID, AUDIO_ID)).thenReturn(true);

        HandoffRetiredNarrationAudioCleanupResult result = useCase.execute(AUDIO_ID);

        assertThat(result.narrationAudioId()).isEqualTo(AUDIO_ID);
        assertThat(result.segmentId()).isEqualTo(SEGMENT_ID);
        assertThat(result.mediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
        assertThat(result.outcome()).isEqualTo(HandoffRetiredNarrationAudioCleanupOutcome.SKIPPED_SHARED_MEDIA_REFERENCE);
        assertThat(result.isSkipped()).isTrue();
        assertThat(result.isHandedOff()).isFalse();

        verify(segmentRepositoryPort).findByIdForUpdate(SEGMENT_ID);
        verify(audioRepositoryPort).existsOtherReferenceToMediaAsset(MEDIA_ASSET_ID, AUDIO_ID);
        verifyNoInteractions(enqueueCleanupUseCase);
        verify(audioRepositoryPort, never()).deleteById(any());
    }
}
