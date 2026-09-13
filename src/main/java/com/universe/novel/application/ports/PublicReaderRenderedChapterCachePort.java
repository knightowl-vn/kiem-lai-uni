package com.universe.novel.application.ports;

import com.universe.novel.contracts.dto.reader.ReaderChapterRenderedSnapshotDTO;
import java.util.function.Supplier;

public interface PublicReaderRenderedChapterCachePort {
    ReaderChapterRenderedSnapshotDTO getOrLoad(String normalizedSlug, Supplier<PublicReaderRenderedChapterLoadResult> loader);
    void invalidate(String normalizedSlug);
}
