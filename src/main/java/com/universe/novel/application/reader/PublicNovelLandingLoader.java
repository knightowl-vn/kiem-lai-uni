package com.universe.novel.application.reader;

import com.universe.novel.application.ports.ReaderNovelLandingQueryPort;
import com.universe.novel.contracts.dto.reader.ReaderChapterNavigationDTO;
import com.universe.novel.contracts.dto.reader.ReaderNovelLandingDTO;
import com.universe.novel.contracts.dto.reader.ReaderNovelOverviewDTO;
import com.universe.novel.contracts.dto.reader.ReaderVolumeListItemDTO;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
public class PublicNovelLandingLoader {

    private final ReaderNovelLandingQueryPort readerNovelLandingQueryPort;

    public PublicNovelLandingLoader(ReaderNovelLandingQueryPort readerNovelLandingQueryPort) {
        this.readerNovelLandingQueryPort = Objects.requireNonNull(readerNovelLandingQueryPort, "readerNovelLandingQueryPort must not be null");
    }

    @Transactional(readOnly = true)
    public ReaderNovelLandingDTO load() {
        ReaderNovelOverviewDTO novel =
                readerNovelLandingQueryPort
                        .findNovelOverview()
                        .orElseThrow(
                                () -> new IllegalStateException(
                                        "Không tìm thấy thông tin tiểu thuyết."
                                )
                        );

        List<ReaderVolumeListItemDTO> volumes =
                readerNovelLandingQueryPort
                        .findPublishedVolumes();

        ReaderChapterNavigationDTO firstChapter =
                readerNovelLandingQueryPort
                        .findFirstPublishedChapter()
                        .orElse(null);

        return new ReaderNovelLandingDTO(
                novel,
                volumes,
                firstChapter
        );
    }
}
