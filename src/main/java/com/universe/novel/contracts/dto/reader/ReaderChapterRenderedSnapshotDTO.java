package com.universe.novel.contracts.dto.reader;

import java.util.UUID;

public record ReaderChapterRenderedSnapshotDTO(
    UUID id,
    int chapterNumber,
    String title,
    String slug,
    String contentHtml,
    long contentVersion,
    ReaderVolumeSummaryDTO volume
) {
    public ReaderChapterRenderedSnapshotDTO {
        if (contentVersion < 1L) {
            throw new IllegalArgumentException(
                    "Content version phải lớn hơn hoặc bằng 1."
            );
        }
    }
}
