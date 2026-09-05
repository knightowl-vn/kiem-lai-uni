package com.universe.novel.application.narration;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.ports.ChapterNarrationSegmentRepositoryPort;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.narration.ChapterNarrationSegment;
import com.universe.novel.domain.narration.NarrationTextSegment;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Use case that reconciles chapter narration text segments against the current chapter Markdown content.
 * <p>
 * Reconciles desired speakable text segments with existing persisted segment rows in a deterministic,
 * one-to-one fashion within a single database transaction:
 * <ol>
 *     <li>Loads chapter content via {@link ChapterRepositoryPort} (throwing {@link ChapterNotFoundException} if not found).</li>
 *     <li>Runs {@link NarrationTextSegmenter} to derive the current speakable segments manifest.</li>
 *     <li>Loads existing persisted segments for the chapter and sorts them deterministically by (segmentIndex ASC, createdAt ASC, id ASC).</li>
 *     <li>For each desired segment, attempts one-to-one matching:
 *         <ul>
 *             <li>First: unmatched {@code CURRENT} segment with exact position + exact {@code contentHash} + {@code text}.</li>
 *             <li>Second: unmatched {@code CURRENT} segment with exact {@code contentHash} + {@code text}.</li>
 *             <li>Third: unmatched {@code RETIRED} segment with exact {@code contentHash} + {@code text} (restores to CURRENT).</li>
 *             <li>Fourth: creates a new {@code CURRENT} segment identity.</li>
 *         </ul>
 *     </li>
 *     <li>Any previously {@code CURRENT} segment that was not matched to any desired segment is transitioned to {@code RETIRED}.</li>
 *     <li>Only segments that were actually created, repositioned, restored, or retired are persisted via {@link ChapterNarrationSegmentRepositoryPort}.</li>
 *     <li>Returns a passive {@link ReconcileChapterNarrationSegmentsResult} containing summary counts.</li>
 * </ol>
 */
@Service
public class ReconcileChapterNarrationSegmentsUseCase {

    private static final Comparator<ChapterNarrationSegment> DETERMINISTIC_SEGMENT_ORDER =
            Comparator.comparingInt(ChapterNarrationSegment::getSegmentIndex)
                    .thenComparing(ChapterNarrationSegment::getCreatedAt)
                    .thenComparing(ChapterNarrationSegment::getId);

    private final ChapterRepositoryPort chapterRepositoryPort;
    private final ChapterNarrationSegmentRepositoryPort segmentRepositoryPort;
    private final NarrationTextSegmenter narrationTextSegmenter;
    private final IdGeneratorPort idGeneratorPort;
    private final ClockPort clockPort;

    public ReconcileChapterNarrationSegmentsUseCase(
            ChapterRepositoryPort chapterRepositoryPort,
            ChapterNarrationSegmentRepositoryPort segmentRepositoryPort,
            NarrationTextSegmenter narrationTextSegmenter,
            IdGeneratorPort idGeneratorPort,
            ClockPort clockPort
    ) {
        this.chapterRepositoryPort = Objects.requireNonNull(chapterRepositoryPort, "chapterRepositoryPort must not be null");
        this.segmentRepositoryPort = Objects.requireNonNull(segmentRepositoryPort, "segmentRepositoryPort must not be null");
        this.narrationTextSegmenter = Objects.requireNonNull(narrationTextSegmenter, "narrationTextSegmenter must not be null");
        this.idGeneratorPort = Objects.requireNonNull(idGeneratorPort, "idGeneratorPort must not be null");
        this.clockPort = Objects.requireNonNull(clockPort, "clockPort must not be null");
    }

    /**
     * Executes narration segment reconciliation for the given command.
     */
    @Transactional
    public ReconcileChapterNarrationSegmentsResult execute(ReconcileChapterNarrationSegmentsCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        return execute(command.chapterId());
    }

    /**
     * Executes narration segment reconciliation for the given chapter ID.
     */
    @Transactional
    public ReconcileChapterNarrationSegmentsResult execute(UUID chapterId) {
        Objects.requireNonNull(chapterId, "Chapter ID must not be null.");

        Chapter chapter = chapterRepositoryPort.findById(chapterId)
                .orElseThrow(() -> new ChapterNotFoundException(chapterId));

        List<NarrationTextSegment> desiredSegments = narrationTextSegmenter.segment(chapter.getContent());
        List<ChapterNarrationSegment> existingSegments = segmentRepositoryPort.findByChapterId(chapterId);

        // Deterministic sorting independent of repository iteration order
        List<ChapterNarrationSegment> sortedExisting = new ArrayList<>(existingSegments);
        sortedExisting.sort(DETERMINISTIC_SEGMENT_ORDER);

        Instant now = clockPort.now();
        Set<UUID> claimedSegmentIds = new HashSet<>();
        List<ChapterNarrationSegment> toSave = new ArrayList<>();

        int reusedCurrentCount = 0;
        int restoredCount = 0;
        int createdCount = 0;
        int retiredCount = 0;

        for (NarrationTextSegment desired : desiredSegments) {
            int desiredIndex = desired.index();
            String desiredHash = desired.contentHash();
            String desiredText = desired.text();

            // 1. Prefer unmatched CURRENT segment with exact contentHash and text
            ChapterNarrationSegment matchedCurrent = null;

            // Prioritize matching exact position among CURRENT candidates
            for (ChapterNarrationSegment candidate : sortedExisting) {
                if (!claimedSegmentIds.contains(candidate.getId())
                        && candidate.isCurrent()
                        && candidate.getSegmentIndex() == desiredIndex
                        && candidate.getContentHash().equals(desiredHash)
                        && candidate.getText().equals(desiredText)) {
                    matchedCurrent = candidate;
                    break;
                }
            }

            // If no exact positional match, pick the first available unmatched CURRENT candidate in deterministic order
            if (matchedCurrent == null) {
                for (ChapterNarrationSegment candidate : sortedExisting) {
                    if (!claimedSegmentIds.contains(candidate.getId())
                            && candidate.isCurrent()
                            && candidate.getContentHash().equals(desiredHash)
                            && candidate.getText().equals(desiredText)) {
                        matchedCurrent = candidate;
                        break;
                    }
                }
            }

            if (matchedCurrent != null) {
                claimedSegmentIds.add(matchedCurrent.getId());
                if (matchedCurrent.getSegmentIndex() != desiredIndex) {
                    matchedCurrent.reposition(desiredIndex, now);
                    toSave.add(matchedCurrent);
                }
                reusedCurrentCount++;
                continue;
            }

            // 2. Otherwise prefer unmatched RETIRED segment with exact contentHash and text
            ChapterNarrationSegment matchedRetired = null;
            for (ChapterNarrationSegment candidate : sortedExisting) {
                if (!claimedSegmentIds.contains(candidate.getId())
                        && candidate.isRetired()
                        && candidate.getContentHash().equals(desiredHash)
                        && candidate.getText().equals(desiredText)) {
                    matchedRetired = candidate;
                    break;
                }
            }

            if (matchedRetired != null) {
                claimedSegmentIds.add(matchedRetired.getId());
                matchedRetired.restore(desiredIndex, now);
                restoredCount++;
                toSave.add(matchedRetired);
                continue;
            }

            // 3. Otherwise create a new persisted segment identity
            UUID newId = idGeneratorPort.generate();
            ChapterNarrationSegment newSegment = ChapterNarrationSegment.create(
                    newId,
                    chapterId,
                    desiredIndex,
                    desiredText,
                    desiredHash,
                    now
            );
            claimedSegmentIds.add(newId);
            createdCount++;
            toSave.add(newSegment);
        }

        // 4. Retire any previously CURRENT segment that was not matched
        for (ChapterNarrationSegment candidate : sortedExisting) {
            if (!claimedSegmentIds.contains(candidate.getId()) && candidate.isCurrent()) {
                candidate.retire(now);
                retiredCount++;
                toSave.add(candidate);
            }
        }

        // 5. Persist ONLY segments that were created, repositioned, restored, or retired
        if (!toSave.isEmpty()) {
            segmentRepositoryPort.saveAll(toSave);
        }

        return new ReconcileChapterNarrationSegmentsResult(
                desiredSegments.size(),
                reusedCurrentCount,
                restoredCount,
                createdCount,
                retiredCount
        );
    }
}
