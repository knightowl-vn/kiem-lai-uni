package com.universe.novel.application.ports;

import com.universe.novel.contracts.dto.reader.ReaderChapterNavigationDTO;
import com.universe.novel.contracts.dto.reader.ReaderNovelOverviewDTO;
import com.universe.novel.contracts.dto.reader.ReaderVolumeListItemDTO;

import java.util.List;
import java.util.Optional;

public interface ReaderNovelLandingQueryPort {

    Optional<ReaderNovelOverviewDTO> findNovelOverview();

    List<ReaderVolumeListItemDTO> findPublishedVolumes();

    Optional<ReaderChapterNavigationDTO> findFirstPublishedChapter();
}