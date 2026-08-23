package com.universe.novel.application.reader;

import com.universe.novel.application.ports.ReaderNovelLandingQueryPort;
import com.universe.novel.contracts.dto.reader.ReaderNovelLandingDTO;
import com.universe.novel.contracts.dto.reader.ReaderNovelOverviewDTO;
import com.universe.novel.contracts.dto.reader.ReaderVolumeListItemDTO;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class GetReaderNovelLandingUseCase {

    private final ReaderNovelLandingQueryPort
            readerNovelLandingQueryPort;

    public GetReaderNovelLandingUseCase(
            ReaderNovelLandingQueryPort readerNovelLandingQueryPort
    ) {
        this.readerNovelLandingQueryPort =
                readerNovelLandingQueryPort;
    }

    @Transactional(readOnly = true)
    public ReaderNovelLandingDTO execute() {

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

        return new ReaderNovelLandingDTO(
                novel,
                volumes
        );
    }
}