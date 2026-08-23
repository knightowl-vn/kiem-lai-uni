package com.universe.novel.application.ports;

import com.universe.novel.contracts.dto.ChapterListPageDTO;

import java.util.UUID;

public interface ChapterListQueryPort {

    ChapterListPageDTO findAllByVolumeIdOrderByChapterNumber(
            UUID volumeId,
            String keyword,
            String status,
            int page,
            int size
    );
}
