package com.universe.novel.application.chapter;

import com.universe.novel.application.exceptions.VolumeNotFoundException;
import com.universe.novel.application.ports.ChapterListQueryPort;
import com.universe.novel.application.ports.VolumeRepositoryPort;
import com.universe.novel.contracts.dto.ChapterListPageDTO;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
public class GetChapterListUseCase {

	private static final int MAX_PAGE_SIZE = 100;

	private final ChapterListQueryPort
    chapterListQueryPort;

    private final VolumeRepositoryPort
            volumeRepositoryPort;

    public GetChapterListUseCase(
            ChapterListQueryPort chapterListQueryPort,
            VolumeRepositoryPort volumeRepositoryPort
    ) {
        this.chapterListQueryPort =
                chapterListQueryPort;

        this.volumeRepositoryPort =
                volumeRepositoryPort;
    }

    @Transactional(readOnly = true)
    public ChapterListPageDTO execute(
            UUID volumeId,
            String keyword,
            String status,
            int page,
            int size
    ) {
        Objects.requireNonNull(
                volumeId,
                "Volume ID không được để trống."
        );

        validatePagination(
                page,
                size
        );

        ensureVolumeExists(
                volumeId
        );

        String normalizedKeyword =
                normalizeOptionalFilter(
                        keyword
                );

        String normalizedStatus =
                normalizeOptionalFilter(
                        status
                );

        return chapterListQueryPort
                .findAllByVolumeIdOrderByChapterNumber(
                        volumeId,
                        normalizedKeyword,
                        normalizedStatus,
                        page,
                        size
                );
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

    private void validatePagination(
            int page,
            int size
    ) {
        if (page < 1) {
            throw new IllegalArgumentException(
                    "Số trang phải lớn hơn hoặc bằng 1."
            );
        }

        if (size < 1
                || size > MAX_PAGE_SIZE) {

            throw new IllegalArgumentException(
                    "Kích thước trang phải từ 1 đến "
                            + MAX_PAGE_SIZE
                            + "."
            );
        }
    }

    private String normalizeOptionalFilter(
            String value
    ) {
        if (value == null) {
            return null;
        }

        String normalized =
                value.trim();

        return normalized.isEmpty()
                ? null
                : normalized;
    }
}
