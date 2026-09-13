package com.universe.novel.application.narration;

import java.util.UUID;

/**
 * Passive DTO representing one CURRENT chapter narration segment with its derived audio health
 * and diagnostics for a selected managed voice in the Admin overview page.
 */
public record AdminChapterNarrationSegmentViewDTO(
        UUID segmentId,
        int segmentIndex,
        String text,
        int characterCount,
        ChapterNarrationAudioHealthStatus healthStatus,
        UUID audioAssignmentId,
        UUID mediaAssetId,
        Long generatedSynthesisRevision,
        Long currentVoiceSynthesisRevision,
        NarrationAudioFailureDiagnosticsDTO failureDiagnostics
) {
    public boolean isReady() {
        return healthStatus == ChapterNarrationAudioHealthStatus.READY;
    }

    public boolean isOutdated() {
        return healthStatus == ChapterNarrationAudioHealthStatus.OUTDATED;
    }

    public boolean isMissing() {
        return healthStatus == ChapterNarrationAudioHealthStatus.MISSING;
    }

    public boolean isFailed() {
        return healthStatus == ChapterNarrationAudioHealthStatus.FAILED;
    }

    public boolean hasFailureDiagnostics() {
        return failureDiagnostics != null;
    }
}
