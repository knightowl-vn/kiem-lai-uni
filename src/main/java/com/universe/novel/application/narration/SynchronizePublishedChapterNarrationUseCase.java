package com.universe.novel.application.narration;

import com.universe.novel.application.ports.ChapterNarrationManifestRepositoryPort;
import com.universe.novel.domain.narration.ChapterNarrationManifest;
import com.universe.shared.time.ClockPort;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestration use case that synchronizes CURRENT chapter narration text segments
 * and the chapter-level published narration manifest metadata for a PUBLISHED chapter.
 * <p>
 * Execution sequence:
 * <ol>
 *     <li>Delegates to {@link ReconcileChapterNarrationSegmentsUseCase} to reconcile text segments
 *         against the published chapter Markdown content and persist segment deltas.</li>
 *     <li>Queries {@link ChapterNarrationManifestRepositoryPort} for existing manifest metadata.</li>
 *     <li>Synchronizes manifest metadata:
 *         <ul>
 *             <li>If missing: creates and persists a new {@link ChapterNarrationManifest}.</li>
 *             <li>If existing but changed: calls {@link ChapterNarrationManifest#reconcileTo(long, String, Instant)}
 *                 and persists the updated manifest.</li>
 *             <li>If existing and identical: performs a no-op (no mutations or database writes).</li>
 *         </ul>
 *     </li>
 *     <li>Returns the immutable {@link ReconcileChapterNarrationSegmentsResult}.</li>
 * </ol>
 */
@Service
public class SynchronizePublishedChapterNarrationUseCase {

    private final ReconcileChapterNarrationSegmentsUseCase reconcileSegmentsUseCase;
    private final ChapterNarrationManifestRepositoryPort manifestRepositoryPort;
    private final ClockPort clockPort;

    public SynchronizePublishedChapterNarrationUseCase(
            ReconcileChapterNarrationSegmentsUseCase reconcileSegmentsUseCase,
            ChapterNarrationManifestRepositoryPort manifestRepositoryPort,
            ClockPort clockPort
    ) {
        this.reconcileSegmentsUseCase = Objects.requireNonNull(reconcileSegmentsUseCase, "reconcileSegmentsUseCase must not be null");
        this.manifestRepositoryPort = Objects.requireNonNull(manifestRepositoryPort, "manifestRepositoryPort must not be null");
        this.clockPort = Objects.requireNonNull(clockPort, "clockPort must not be null");
    }

    /**
     * Synchronizes narration segments and manifest metadata for a published chapter.
     *
     * @param chapterId the published chapter UUID
     * @return the immutable reconciliation result
     */
    @Transactional
    public ReconcileChapterNarrationSegmentsResult execute(UUID chapterId) {
        Objects.requireNonNull(chapterId, "chapterId must not be null");

        // 1. Reconcile segments against published content
        ReconcileChapterNarrationSegmentsResult reconciliationResult =
                reconcileSegmentsUseCase.execute(chapterId);

        // 2. Query existing manifest
        Optional<ChapterNarrationManifest> existingManifestOpt =
                manifestRepositoryPort.findByChapterId(chapterId);

        // 3. Synchronize manifest
        if (existingManifestOpt.isEmpty()) {
            Instant now = clockPort.now();
            ChapterNarrationManifest newManifest = ChapterNarrationManifest.create(
                    chapterId,
                    reconciliationResult.sourceContentVersion(),
                    reconciliationResult.manifestHash(),
                    now
            );
            manifestRepositoryPort.save(newManifest);
        } else {
            ChapterNarrationManifest manifest = existingManifestOpt.get();
            boolean isIdentical = manifest.getSourceContentVersion() == reconciliationResult.sourceContentVersion()
                    && Objects.equals(manifest.getManifestHash(), reconciliationResult.manifestHash());

            if (!isIdentical) {
                Instant now = clockPort.now();
                manifest.reconcileTo(
                        reconciliationResult.sourceContentVersion(),
                        reconciliationResult.manifestHash(),
                        now
                );
                manifestRepositoryPort.save(manifest);
            }
        }

        return reconciliationResult;
    }
}
