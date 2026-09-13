package com.universe.novel.application.ports;

import com.universe.novel.contracts.dto.reader.ReaderChapterRenderedSnapshotDTO;

public record PublicReaderRenderedChapterLoadResult(
    ReaderChapterRenderedSnapshotDTO snapshot,
    boolean cacheable
) {}
