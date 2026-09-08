package com.universe.novel.application.narration;

import java.time.Instant;
import java.util.Objects;

/**
 * Read-only status DTO returned by Admin narration polling endpoint (MS-04.9H.7D4B).
 * Combines in-memory operation state with durable H.8E4 health counters.
 * <p>
 * <strong>Security & Clean Architecture:</strong>
 * Never exposes raw exception stack traces, provider identifiers, storage keys, or segment text.
 *
 * @param operationStatus                lifecycle status of the background operation (IDLE, RUNNING, SUCCEEDED, PARTIAL, FAILED)
 * @param operationMessage               safe human-readable status or diagnostic summary message
 * @param startedAt                      timestamp when operation was initiated (null if IDLE)
 * @param completedAt                    timestamp when operation completed (null if IDLE or RUNNING)
 * @param currentSegmentCount            total number of CURRENT text segments in this chapter
 * @param readyCount                     number of current segments with READY audio
 * @param outdatedCount                  number of current segments with OUTDATED audio
 * @param missingCount                   number of current segments with MISSING audio
 * @param failedCount                    number of current segments with FAILED audio
 * @param currentGenerationRequiredCount count of segments requiring work (outdated + missing + failed)
 * @param contentChangeWarning           flag indicating chapter text has changed and has obsolete retired audios
 * @param obsoleteRetiredAudioCount      count of retired audios that no longer match current chapter text
 */
public record AdminChapterNarrationStatusDTO(
        String operationStatus,
        String operationMessage,
        Instant startedAt,
        Instant completedAt,
        int currentSegmentCount,
        int readyCount,
        int outdatedCount,
        int missingCount,
        int failedCount,
        int currentGenerationRequiredCount,
        boolean contentChangeWarning,
        int obsoleteRetiredAudioCount
) {
    public AdminChapterNarrationStatusDTO {
        Objects.requireNonNull(operationStatus, "operationStatus must not be null");
        Objects.requireNonNull(operationMessage, "operationMessage must not be null");
    }
}
