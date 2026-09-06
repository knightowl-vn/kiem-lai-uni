package com.universe.novel.application.narration;

import com.universe.novel.application.voice.dto.ManagedVoiceDTO;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.contracts.dto.VolumeDTO;

import java.util.List;

/**
 * Read model result containing all data required to render the Admin chapter narration overview page.
 */
public record GetAdminChapterNarrationOverviewResult(
        ChapterDTO chapter,
        VolumeDTO volume,
        List<ManagedVoiceDTO> voices,
        ManagedVoiceDTO selectedVoice,
        List<AdminChapterNarrationSegmentViewDTO> segments,
        int totalSegments,
        int readyCount,
        int outdatedCount,
        int missingCount,
        int failedCount
) {
}
