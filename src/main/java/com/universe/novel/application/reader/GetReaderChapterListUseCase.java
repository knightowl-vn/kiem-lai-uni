package com.universe.novel.application.reader;

import com.universe.novel.application.ports.PublicReaderChapterListCachePort;
import com.universe.novel.contracts.dto.reader.ReaderChapterListItemDTO;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class GetReaderChapterListUseCase {

    private final PublicReaderChapterListCachePort cachePort;
    private final ReaderChapterListLoader loader;

    public GetReaderChapterListUseCase(
            PublicReaderChapterListCachePort cachePort,
            ReaderChapterListLoader loader
    ) {
        this.cachePort = Objects.requireNonNull(cachePort, "cachePort must not be null");
        this.loader = Objects.requireNonNull(loader, "loader must not be null");
    }

    public List<ReaderChapterListItemDTO> execute(
            UUID volumeId
    ) {
        Objects.requireNonNull(
                volumeId,
                "Volume ID không được để trống."
        );

        return cachePort.getOrLoad(
                volumeId,
                () -> loader.load(volumeId)
        );
    }
}