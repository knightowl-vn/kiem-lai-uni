package com.universe.novel.application.narration;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/**
 * Pure, stateless planner that maps a CURRENT segment's health status into a {@link ReaderNarrationPreparationDecision} (MS-04.9H.7C2A).
 * <p>
 * <strong>Planning Rules:</strong>
 * <ul>
 *     <li>{@link ChapterNarrationAudioHealthStatus#READY} &rarr; {@link ReaderNarrationPreparationAction#PLAY_NOW}</li>
 *     <li>{@link ChapterNarrationAudioHealthStatus#OUTDATED} &rarr; {@link ReaderNarrationPreparationAction#PLAY_NOW_AND_REFRESH}</li>
 *     <li>{@link ChapterNarrationAudioHealthStatus#MISSING} &rarr; {@link ReaderNarrationPreparationAction#PREPARE}</li>
 *     <li>{@link ChapterNarrationAudioHealthStatus#FAILED} &rarr; {@link ReaderNarrationPreparationAction#RETRY_PREPARE}</li>
 * </ul>
 */
@Component
public class ReaderNarrationPreparationDecisionPlanner {

    /**
     * Determines the reader preparation decision for a segment based on its health status.
     *
     * @param segmentId    identity of the narration segment (non-null)
     * @param segmentIndex zero-based index of the segment (>= 0)
     * @param health       health status of the segment (non-null)
     * @return immutable {@link ReaderNarrationPreparationDecision}
     */
    public ReaderNarrationPreparationDecision plan(
            UUID segmentId,
            int segmentIndex,
            ChapterNarrationAudioHealthStatus health
    ) {
        Objects.requireNonNull(segmentId, "segmentId must not be null");
        if (segmentIndex < 0) {
            throw new IllegalArgumentException("segmentIndex must not be negative: " + segmentIndex);
        }
        Objects.requireNonNull(health, "health must not be null");

        ReaderNarrationPreparationAction action = resolveAction(health);
        return new ReaderNarrationPreparationDecision(segmentId, segmentIndex, health, action);
    }

    /**
     * Maps health status to the corresponding preparation action.
     *
     * @param health health status (non-null)
     * @return reader preparation action
     */
    public ReaderNarrationPreparationAction resolveAction(ChapterNarrationAudioHealthStatus health) {
        Objects.requireNonNull(health, "health must not be null");
        return switch (health) {
            case READY -> ReaderNarrationPreparationAction.PLAY_NOW;
            case OUTDATED -> ReaderNarrationPreparationAction.PLAY_NOW_AND_REFRESH;
            case MISSING -> ReaderNarrationPreparationAction.PREPARE;
            case FAILED -> ReaderNarrationPreparationAction.RETRY_PREPARE;
        };
    }
}
