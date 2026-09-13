package com.universe.novel.application.narration;

import com.universe.novel.application.ports.ReaderChapterAccessQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Background worker executing Reader chapter narration preparation (MS-04.9H.9, H.9I5B).
 * <p>
 * <strong>Workflow:</strong>
 * <ol>
 *     <li>Re-checks Reader publication access before starting expensive work.</li>
 *     <li>Executes segment-level generation via {@link GenerateChapterNarrationUseCase}.</li>
 *     <li>Validates that generation produced CURRENT segments and was completely successful.</li>
 *     <li>Re-checks Reader publication access before building playback artifact.</li>
 *     <li>Builds and publishes chapter playback via {@link BuildChapterNarrationPlaybackUseCase}.</li>
 * </ol>
 * Catches and isolates {@link RuntimeException} at the worker boundary.
 */
@Component
public class ReaderChapterNarrationPreparationWorker {

    private static final Logger log = LoggerFactory.getLogger(ReaderChapterNarrationPreparationWorker.class);

    private final ReaderChapterAccessQueryPort readerChapterAccessQueryPort;
    private final GenerateChapterNarrationUseCase generateChapterNarrationUseCase;
    private final BuildChapterNarrationPlaybackUseCase buildChapterNarrationPlaybackUseCase;

    public ReaderChapterNarrationPreparationWorker(
            ReaderChapterAccessQueryPort readerChapterAccessQueryPort,
            GenerateChapterNarrationUseCase generateChapterNarrationUseCase,
            BuildChapterNarrationPlaybackUseCase buildChapterNarrationPlaybackUseCase
    ) {
        this.readerChapterAccessQueryPort = Objects.requireNonNull(
                readerChapterAccessQueryPort, "readerChapterAccessQueryPort must not be null"
        );
        this.generateChapterNarrationUseCase = Objects.requireNonNull(
                generateChapterNarrationUseCase, "generateChapterNarrationUseCase must not be null"
        );
        this.buildChapterNarrationPlaybackUseCase = Objects.requireNonNull(
                buildChapterNarrationPlaybackUseCase, "buildChapterNarrationPlaybackUseCase must not be null"
        );
    }

    /**
     * Runs the background chapter narration preparation workflow.
     *
     * @param command input preparation command
     */
    public void runPreparation(ReaderChapterNarrationPreparationCommand command) {
        if (command == null) {
            log.warn("Reader chapter narration preparation worker received null command. Aborting.");
            return;
        }

        try {
            // 1. Pre-check Reader publication visibility before expensive work
            if (readerChapterAccessQueryPort.findPublishedById(command.chapterId()).isEmpty()) {
                log.warn("Reader publication pre-check failed for chapter [{}]; aborting narration preparation.",
                        command.chapterId());
                return;
            }

            // 2. Generate/reconcile segments
            GenerateChapterNarrationResult result = generateChapterNarrationUseCase.execute(
                    command.chapterId(), command.managedVoiceId()
            );

            // 3. Stop if no CURRENT segments exist
            if (result.isEmpty()) {
                log.warn("Chapter narration generation result is empty for chapter [{}]; skipping playback build.",
                        command.chapterId());
                return;
            }

            // 4. Stop if generation had failures or incomplete work
            if (!result.isCompleteSuccess()) {
                log.warn("Chapter narration generation incomplete for chapter [{}] and voice [{}]: remaining={}; skipping playback build.",
                        command.chapterId(), command.managedVoiceId(), result.remainingWorkCount());
                return;
            }

            // 5. Post-check Reader publication visibility after long segment generation
            if (readerChapterAccessQueryPort.findPublishedById(command.chapterId()).isEmpty()) {
                log.warn("Reader publication post-check failed for chapter [{}] after generation; aborting playback build.",
                        command.chapterId());
                return;
            }

            // 6. Build and publish whole-chapter playback artifact
            buildChapterNarrationPlaybackUseCase.execute(
                    new BuildChapterNarrationPlaybackCommand(command.chapterId(), command.managedVoiceId())
            );
            log.info("Reader chapter narration preparation successfully completed for chapter [{}] and voice [{}]",
                    command.chapterId(), command.managedVoiceId());
        } catch (RuntimeException ex) {
            log.warn("Exception during reader chapter narration preparation for chapter [{}] and voice [{}]: {}",
                    command.chapterId(), command.managedVoiceId(), ex.getMessage(), ex);
        }
    }
}
