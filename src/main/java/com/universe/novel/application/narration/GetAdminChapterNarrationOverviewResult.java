package com.universe.novel.application.narration;

import com.universe.novel.application.voice.dto.ManagedVoiceDTO;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.contracts.dto.VolumeDTO;

import java.util.List;

/**
 * Read model result containing all data required to render the Admin chapter narration overview page
 * and inspect chapter-level narration health and obsolete retired audio diagnostics (MS-04.9H.8E4).
 */
public record GetAdminChapterNarrationOverviewResult(
        ChapterDTO chapter,
        VolumeDTO volume,
        List<ManagedVoiceDTO> voices,
        ManagedVoiceDTO selectedVoice,
        List<AdminChapterNarrationSegmentViewDTO> segments,
        int currentSegmentCount,
        int readyCount,
        int outdatedCount,
        int missingCount,
        int failedCount,
        int currentGenerationRequiredCount,
        int retiredSegmentCount,
        int obsoleteRetiredSegmentCount,
        int obsoleteRetiredAudioCount,
        boolean contentChangeWarning,
        AdminChapterNarrationPlaybackDTO chapterPlayback
) {
    /**
     * Backward-compatible alias for {@link #currentSegmentCount()}.
     */
    public int totalSegments() {
        return currentSegmentCount;
    }
}
