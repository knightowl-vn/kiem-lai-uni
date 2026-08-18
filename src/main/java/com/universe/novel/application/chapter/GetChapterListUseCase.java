package com.universe.novel.application.chapter;

import com.universe.novel.application.exceptions.VolumeNotFoundException;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.application.ports.VolumeRepositoryPort;
import com.universe.novel.contracts.dto.ChapterDTO;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class GetChapterListUseCase {

    private final ChapterRepositoryPort
            chapterRepositoryPort;

    private final VolumeRepositoryPort
            volumeRepositoryPort;

    public GetChapterListUseCase(
            ChapterRepositoryPort chapterRepositoryPort,
            VolumeRepositoryPort volumeRepositoryPort
    ) {
        this.chapterRepositoryPort =
                chapterRepositoryPort;

        this.volumeRepositoryPort =
                volumeRepositoryPort;
    }

    @Transactional(readOnly = true)
    public List<ChapterDTO> execute(
            UUID volumeId
    ) {
        Objects.requireNonNull(
                volumeId,
                "Volume ID không được để trống."
        );

        ensureVolumeExists(
                volumeId
        );

        return chapterRepositoryPort
                .findAllByVolumeIdOrderBySortOrder(
                        volumeId
                )
                .stream()
                .map(
                        ChapterDTOMapper::toDTO
                )
                .toList();
    }

    private void ensureVolumeExists(
            UUID volumeId
    ) {
        if (volumeRepositoryPort
                .findById(
                        volumeId
                )
                .isEmpty()) {

            throw new VolumeNotFoundException(
                    volumeId
            );
        }
    }
}