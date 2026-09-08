package com.universe.novel.application.narration;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.ports.ReaderChapterAccessQueryPort;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/**
 * Reader front-door use case that enforces published Reader visibility and coordinates immediate
 * segment preparation with non-blocking background continuation (MS-04.9H.7C2C3B, MS-04.9H.7D1A).
 * <p>
 * <strong>Workflow:</strong>
 * <ol>
 *     <li>Pre-checks published Reader access (Chapter {@code PUBLISHED} and parent Volume {@code PUBLISHED}).</li>
 *     <li>Synchronously executes {@link PrepareReaderNarrationSegmentUseCase} (H.7C2B) for the requested segment.</li>
 *     <li>Post-checks published Reader access before returning or dispatching continuation to close long-generation unpublish races.</li>
 *     <li>If the immediate result is not playable (e.g. {@code RETRY_REQUIRED}, {@code PREPARATION_FAILED}, {@code UNAVAILABLE}), continuation is <em>not</em> scheduled.</li>
 *     <li>If the immediate result is playable (fresh {@code READY} or cached {@code OUTDATED}), asynchronously dispatches background continuation via {@link ReaderNarrationContinuationDispatcher}.</li>
 *     <li>Returns immediately without blocking for background continuation completion.</li>
 * </ol>
 */
@Service
public class PrepareReaderNarrationPlaybackUseCase {

    private final ReaderChapterAccessQueryPort readerChapterAccessQueryPort;
    private final PrepareReaderNarrationSegmentUseCase prepareSegmentUseCase;
    private final ReaderNarrationContinuationDispatcher continuationDispatcher;

    public PrepareReaderNarrationPlaybackUseCase(
            ReaderChapterAccessQueryPort readerChapterAccessQueryPort,
            PrepareReaderNarrationSegmentUseCase prepareSegmentUseCase,
            ReaderNarrationContinuationDispatcher continuationDispatcher
    ) {
        this.readerChapterAccessQueryPort = Objects.requireNonNull(
                readerChapterAccessQueryPort, "readerChapterAccessQueryPort must not be null"
        );
        this.prepareSegmentUseCase = Objects.requireNonNull(
                prepareSegmentUseCase, "prepareSegmentUseCase must not be null"
        );
        this.continuationDispatcher = Objects.requireNonNull(
                continuationDispatcher, "continuationDispatcher must not be null"
        );
    }

    /**
     * Executes the reader narration playback preparation workflow for the given command.
     *
     * @param command input command containing chapterId, segmentId, and managedVoiceId
     * @return composite playback result
     */
    public PrepareReaderNarrationPlaybackResult execute(PrepareReaderNarrationSegmentCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        return execute(command.chapterId(), command.segmentId(), command.managedVoiceId());
    }

    /**
     * Executes the reader narration playback preparation workflow for the given IDs.
     *
     * @param chapterId      identity of the published chapter
     * @param segmentId      identity of the requested segment
     * @param managedVoiceId identity of the active managed voice
     * @return composite playback result
     */
    public PrepareReaderNarrationPlaybackResult execute(UUID chapterId, UUID segmentId, UUID managedVoiceId) {
        if (chapterId == null) {
            throw new IllegalArgumentException("chapterId must not be null");
        }
        if (segmentId == null) {
            throw new IllegalArgumentException("segmentId must not be null");
        }
        if (managedVoiceId == null) {
            throw new IllegalArgumentException("managedVoiceId must not be null");
        }

        // 1. Enforce initial published Reader visibility (Chapter PUBLISHED and Volume PUBLISHED)
        readerChapterAccessQueryPort.findPublishedById(chapterId)
                .orElseThrow(() -> new ChapterNotFoundException(chapterId));

        // 2. Synchronously prepare requested segment for immediate playback (H.7C2B)
        PrepareReaderNarrationSegmentResult immediateResult = prepareSegmentUseCase.execute(
                chapterId,
                segmentId,
                managedVoiceId
        );

        // 3. Post-check published Reader visibility to close long-generation lifecycle drift window
        readerChapterAccessQueryPort.findPublishedById(chapterId)
                .orElseThrow(() -> new ChapterNotFoundException(chapterId));

        // 4. If immediate audio is not playable, do NOT dispatch continuation
        if (!immediateResult.isPlayableNow()) {
            return new PrepareReaderNarrationPlaybackResult(
                    immediateResult,
                    ReaderNarrationContinuationDispatchStatus.NOT_SCHEDULED
            );
        }

        // 5. Playable -> Dispatch asynchronous background continuation
        ReaderNarrationContinuationCommand continuationCommand = new ReaderNarrationContinuationCommand(
                chapterId,
                segmentId,
                managedVoiceId,
                immediateResult.refreshRecommended()
        );

        ReaderNarrationContinuationDispatchStatus dispatchStatus =
                continuationDispatcher.dispatch(continuationCommand);

        return new PrepareReaderNarrationPlaybackResult(
                immediateResult,
                dispatchStatus
        );
    }
}
