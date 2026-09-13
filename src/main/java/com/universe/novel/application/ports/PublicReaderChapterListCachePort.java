package com.universe.novel.application.ports;

import com.universe.novel.contracts.dto.reader.ReaderChapterListItemDTO;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public interface PublicReaderChapterListCachePort {

    List<ReaderChapterListItemDTO> getOrLoad(
            UUID volumeId,
            Supplier<List<ReaderChapterListItemDTO>> loader
    );

    void invalidate(UUID volumeId);
}
