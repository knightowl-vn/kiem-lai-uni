package com.universe.novel.application.ports;

import com.universe.novel.contracts.dto.reader.ReaderChapterTocItemDTO;

import java.util.List;
import java.util.function.Supplier;

public interface PublicReaderNavigationIndexCachePort {

    List<ReaderChapterTocItemDTO> getOrLoad(Supplier<List<ReaderChapterTocItemDTO>> loader);

    void invalidate();
}
