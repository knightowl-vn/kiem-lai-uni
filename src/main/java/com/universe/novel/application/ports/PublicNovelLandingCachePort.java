package com.universe.novel.application.ports;

import com.universe.novel.contracts.dto.reader.ReaderNovelLandingDTO;

import java.util.function.Supplier;

public interface PublicNovelLandingCachePort {

    ReaderNovelLandingDTO getOrLoad(Supplier<ReaderNovelLandingDTO> loader);

    void invalidate();
}
