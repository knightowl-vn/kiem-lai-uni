package com.universe.novel.application.reader;

import com.universe.novel.application.ports.PublicNovelLandingCachePort;
import com.universe.novel.contracts.dto.reader.ReaderNovelLandingDTO;

import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class GetReaderNovelLandingUseCase {

    private final PublicNovelLandingCachePort cachePort;
    private final PublicNovelLandingLoader loader;

    public GetReaderNovelLandingUseCase(
            PublicNovelLandingCachePort cachePort,
            PublicNovelLandingLoader loader
    ) {
        this.cachePort = Objects.requireNonNull(cachePort, "cachePort must not be null");
        this.loader = Objects.requireNonNull(loader, "loader must not be null");
    }

    public ReaderNovelLandingDTO execute() {
        return cachePort.getOrLoad(loader::load);
    }
}