package com.universe.novel.application.ports;

import com.universe.novel.contracts.dto.reader.ReaderChapterListItemDTO;

import java.util.List;
import java.util.UUID;

public interface ReaderChapterListQueryPort {

    List<ReaderChapterListItemDTO>
            findPublishedByVolumeIdOrderByChapterNumber(
                    UUID volumeId
            );
}