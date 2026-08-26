package com.universe.novel.contracts.dto.reader;

import java.util.List;

public record ReaderNovelLandingDTO(
        ReaderNovelOverviewDTO novel,
        List<ReaderVolumeListItemDTO> volumes,
        ReaderChapterNavigationDTO firstChapter
) {
    public ReaderNovelLandingDTO(
            ReaderNovelOverviewDTO novel,
            List<ReaderVolumeListItemDTO> volumes
    ) {
        this(novel, volumes, null);
    }
}