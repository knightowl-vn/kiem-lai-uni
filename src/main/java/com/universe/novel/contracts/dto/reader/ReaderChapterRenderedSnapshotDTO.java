package com.universe.novel.contracts.dto.reader;

import java.util.UUID;

public record ReaderChapterRenderedSnapshotDTO(
    UUID id,
    int chapterNumber,
    String title,
    String slug,
    String contentHtml,
    ReaderVolumeSummaryDTO volume
) {}
