package com.universe.novel.application.narration;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.ports.ReaderChapterAccessQueryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PrepareReaderNarrationPlaybackUseCase Unit Tests (MS-04.9H.7C2C3B, MS-04.9H.7D1A)")
class PrepareReaderNarrationPlaybackUseCaseTest {

    @Mock
    private ReaderChapterAccessQueryPort readerChapterAccessQueryPort;
    @Mock
    private PrepareReaderNarrationSegmentUseCase prepareSegmentUseCase;
    @Mock
    private ReaderNarrationContinuationDispatcher continuationDispatcher;

    private PrepareReaderNarrationPlaybackUseCase useCase;

    private static final UUID CHAPTER_ID = UUID.randomUUID();
    private static final UUID SEGMENT_ID = UUID.randomUUID();
    private static final UUID VOICE_ID = UUID.randomUUID();
    private static final UUID MEDIA_ASSET_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        useCase = new PrepareReaderNarrationPlaybackUseCase(
                readerChapterAccessQueryPort,
                prepareSegmentUseCase,
                continuationDispatcher
        );
    }

    private PrepareReaderNarrationSegmentResult createImmediateResult(
            ChapterNarrationAudioHealthStatus initialHealth,
            ReaderNarrationPreparationAction initialAction,
            ChapterNarrationAudioHealthStatus finalHealth,
            PrepareReaderNarrationSegmentOutcome outcome,
            UUID mediaAssetId,
            boolean refreshRecommended
    ) {
        return new PrepareReaderNarrationSegmentResult(
                CHAPTER_ID,
                SEGMENT_ID,
                2,
                VOICE_ID,
                initialHealth,
                initialAction,
                finalHealth,
                outcome,
                mediaAssetId,
                false,
                refreshRecommended
        );
    }

    @Test
    @DisplayName("Rejects null command or arguments")
    void rejectsNullInputs() {
        assertThatThrownBy(() -> useCase.execute((PrepareReaderNarrationSegmentCommand) null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> useCase.execute(null, SEGMENT_ID, VOICE_ID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, null, VOICE_ID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, SEGMENT_ID, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("1. Public access missing before preparation -> ChapterNotFoundException, zero preparation and zero continuation")
    void publicAccessMissingBeforePreparationThrowsChapterNotFound() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID))
                .isInstanceOf(ChapterNotFoundException.class);

        verify(prepareSegmentUseCase, never()).execute(any(), any(), any());
        verify(continuationDispatcher, never()).dispatch(any());
    }

    @Test
    @DisplayName("2. Public access disappears after preparation -> ChapterNotFoundException, zero continuation dispatch")
    void publicAccessDisappearsAfterPreparationThrowsChapterNotFound() {
        ReaderChapterAccessQueryPort.ReadableChapterReference readableRef =
                new ReaderChapterAccessQueryPort.ReadableChapterReference(CHAPTER_ID, 1);

        // First call (pre-check) succeeds, second call (post-check) returns empty
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(readableRef))
                .thenReturn(Optional.empty());

        PrepareReaderNarrationSegmentResult immediateResult = createImmediateResult(
                ChapterNarrationAudioHealthStatus.READY,
                ReaderNarrationPreparationAction.PLAY_NOW,
                ChapterNarrationAudioHealthStatus.READY,
                PrepareReaderNarrationSegmentOutcome.PLAYABLE_CACHED,
                MEDIA_ASSET_ID,
                false
        );

        when(prepareSegmentUseCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID))
                .thenReturn(immediateResult);

        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID))
                .isInstanceOf(ChapterNotFoundException.class);

        verify(continuationDispatcher, never()).dispatch(any());
    }

    @Test
    @DisplayName("3. Playable READY result -> continuation dispatched with refreshRequestedSegment=false and SCHEDULED")
    void playableReadyDispatchesContinuationWithoutRefresh() {
        ReaderChapterAccessQueryPort.ReadableChapterReference readableRef =
                new ReaderChapterAccessQueryPort.ReadableChapterReference(CHAPTER_ID, 1);
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(readableRef));

        PrepareReaderNarrationSegmentResult immediateResult = createImmediateResult(
                ChapterNarrationAudioHealthStatus.READY,
                ReaderNarrationPreparationAction.PLAY_NOW,
                ChapterNarrationAudioHealthStatus.READY,
                PrepareReaderNarrationSegmentOutcome.PLAYABLE_CACHED,
                MEDIA_ASSET_ID,
                false
        );

        when(prepareSegmentUseCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID))
                .thenReturn(immediateResult);
        when(continuationDispatcher.dispatch(any(ReaderNarrationContinuationCommand.class)))
                .thenReturn(ReaderNarrationContinuationDispatchStatus.SCHEDULED);

        PrepareReaderNarrationPlaybackResult result = useCase.execute(new PrepareReaderNarrationSegmentCommand(
                CHAPTER_ID, SEGMENT_ID, VOICE_ID
        ));

        assertThat(result.isPlayableNow()).isTrue();
        assertThat(result.blocksPlayback()).isFalse();
        assertThat(result.mediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
        assertThat(result.continuationDispatchStatus()).isEqualTo(ReaderNarrationContinuationDispatchStatus.SCHEDULED);

        ArgumentCaptor<ReaderNarrationContinuationCommand> captor = ArgumentCaptor.forClass(ReaderNarrationContinuationCommand.class);
        verify(continuationDispatcher).dispatch(captor.capture());
        ReaderNarrationContinuationCommand cmd = captor.getValue();
        assertThat(cmd.chapterId()).isEqualTo(CHAPTER_ID);
        assertThat(cmd.requestedSegmentId()).isEqualTo(SEGMENT_ID);
        assertThat(cmd.managedVoiceId()).isEqualTo(VOICE_ID);
        assertThat(cmd.refreshRequestedSegment()).isFalse();
    }

    @Test
    @DisplayName("4. Playable OUTDATED cached result -> continuation dispatched with refreshRequestedSegment=true")
    void playableOutdatedCachedDispatchesContinuationWithRefreshTrue() {
        ReaderChapterAccessQueryPort.ReadableChapterReference readableRef =
                new ReaderChapterAccessQueryPort.ReadableChapterReference(CHAPTER_ID, 1);
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(readableRef));

        PrepareReaderNarrationSegmentResult immediateResult = createImmediateResult(
                ChapterNarrationAudioHealthStatus.OUTDATED,
                ReaderNarrationPreparationAction.PLAY_NOW_AND_REFRESH,
                ChapterNarrationAudioHealthStatus.OUTDATED,
                PrepareReaderNarrationSegmentOutcome.PLAYABLE_CACHED,
                MEDIA_ASSET_ID,
                true
        );

        when(prepareSegmentUseCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID))
                .thenReturn(immediateResult);
        when(continuationDispatcher.dispatch(any(ReaderNarrationContinuationCommand.class)))
                .thenReturn(ReaderNarrationContinuationDispatchStatus.SCHEDULED);

        PrepareReaderNarrationPlaybackResult result = useCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID);

        assertThat(result.isPlayableNow()).isTrue();
        assertThat(result.continuationDispatchStatus()).isEqualTo(ReaderNarrationContinuationDispatchStatus.SCHEDULED);

        ArgumentCaptor<ReaderNarrationContinuationCommand> captor = ArgumentCaptor.forClass(ReaderNarrationContinuationCommand.class);
        verify(continuationDispatcher).dispatch(captor.capture());
        assertThat(captor.getValue().refreshRequestedSegment()).isTrue();
    }

    @Test
    @DisplayName("5. PREPARED_AND_PLAYABLE result with final OUTDATED -> refreshRequestedSegment=true")
    void preparedAndPlayableOutdatedDispatchesWithRefreshTrue() {
        ReaderChapterAccessQueryPort.ReadableChapterReference readableRef =
                new ReaderChapterAccessQueryPort.ReadableChapterReference(CHAPTER_ID, 1);
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(readableRef));

        PrepareReaderNarrationSegmentResult immediateResult = createImmediateResult(
                ChapterNarrationAudioHealthStatus.MISSING,
                ReaderNarrationPreparationAction.PREPARE,
                ChapterNarrationAudioHealthStatus.OUTDATED,
                PrepareReaderNarrationSegmentOutcome.PREPARED_AND_PLAYABLE,
                MEDIA_ASSET_ID,
                true
        );

        when(prepareSegmentUseCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID))
                .thenReturn(immediateResult);
        when(continuationDispatcher.dispatch(any(ReaderNarrationContinuationCommand.class)))
                .thenReturn(ReaderNarrationContinuationDispatchStatus.SCHEDULED);

        PrepareReaderNarrationPlaybackResult result = useCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID);

        assertThat(result.isPlayableNow()).isTrue();
        ArgumentCaptor<ReaderNarrationContinuationCommand> captor = ArgumentCaptor.forClass(ReaderNarrationContinuationCommand.class);
        verify(continuationDispatcher).dispatch(captor.capture());
        assertThat(captor.getValue().refreshRequestedSegment()).isTrue();
    }

    @Test
    @DisplayName("6. Non-playable result (RETRY_REQUIRED / FAILED / UNAVAILABLE) -> NOT_SCHEDULED, zero continuation dispatch")
    void nonPlayableResultReturnsNotScheduledWithoutDispatch() {
        ReaderChapterAccessQueryPort.ReadableChapterReference readableRef =
                new ReaderChapterAccessQueryPort.ReadableChapterReference(CHAPTER_ID, 1);
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(readableRef));

        PrepareReaderNarrationSegmentResult immediateResult = createImmediateResult(
                ChapterNarrationAudioHealthStatus.MISSING,
                ReaderNarrationPreparationAction.PREPARE,
                ChapterNarrationAudioHealthStatus.FAILED,
                PrepareReaderNarrationSegmentOutcome.RETRY_REQUIRED,
                null,
                false
        );

        when(prepareSegmentUseCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID))
                .thenReturn(immediateResult);

        PrepareReaderNarrationPlaybackResult result = useCase.execute(CHAPTER_ID, SEGMENT_ID, VOICE_ID);

        assertThat(result.isPlayableNow()).isFalse();
        assertThat(result.blocksPlayback()).isTrue();
        assertThat(result.continuationDispatchStatus()).isEqualTo(ReaderNarrationContinuationDispatchStatus.NOT_SCHEDULED);

        verify(continuationDispatcher, never()).dispatch(any());
    }
}
