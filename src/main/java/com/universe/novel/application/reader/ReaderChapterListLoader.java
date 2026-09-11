package com.universe.novel.application.reader;

import com.universe.novel.application.ports.ReaderChapterListQueryPort;
import com.universe.novel.contracts.dto.reader.ReaderChapterListItemDTO;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
public class ReaderChapterListLoader {

    private final ReaderChapterListQueryPort readerChapterListQueryPort;

    public ReaderChapterListLoader(
            ReaderChapterListQueryPort readerChapterListQueryPort
    ) {
        this.readerChapterListQueryPort = Objects.requireNonNull(
                readerChapterListQueryPort,
                "readerChapterListQueryPort must not be null"
        );
    }

    @Transactional(readOnly = true)
    public List<ReaderChapterListItemDTO> load(UUID volumeId) {
        Objects.requireNonNull(volumeId, "volumeId must not be null");
        return readerChapterListQueryPort.findPublishedByVolumeIdOrderByChapterNumber(volumeId);
    }
}
