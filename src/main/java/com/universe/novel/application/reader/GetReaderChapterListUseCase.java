package com.universe.novel.application.reader;

import com.universe.novel.application.ports.ReaderChapterListQueryPort;
import com.universe.novel.contracts.dto.reader.ReaderChapterListItemDTO;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class GetReaderChapterListUseCase {

    private final ReaderChapterListQueryPort
            readerChapterListQueryPort;

    public GetReaderChapterListUseCase(
            ReaderChapterListQueryPort readerChapterListQueryPort
    ) {
        this.readerChapterListQueryPort =
                readerChapterListQueryPort;
    }

    @Transactional(readOnly = true)
    public List<ReaderChapterListItemDTO> execute(
            UUID volumeId
    ) {
        Objects.requireNonNull(
                volumeId,
                "Volume ID không được để trống."
        );

        return readerChapterListQueryPort
                .findPublishedByVolumeIdOrderByChapterNumber(
                        volumeId
                );
    }
}