package com.universe.novel.application.ports;

import com.universe.novel.contracts.dto.reader.ReaderChapterNavigationDTO;
import com.universe.novel.contracts.dto.reader.ReaderChapterTocItemDTO;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReaderChapterDetailQueryPort {

    Optional<ReaderChapterRecord> findPublishedChapterBySlug(
            String chapterSlug
    );

    Optional<ReaderChapterNavigationDTO> findPreviousPublishedChapter(
            int currentChapterNumber
    );

    Optional<ReaderChapterNavigationDTO> findNextPublishedChapter(
            int currentChapterNumber
    );

    List<ReaderChapterTocItemDTO> findAllPublishedChaptersForToc();

    record ReaderChapterRecord(
            UUID id,
            UUID volumeId,
            int chapterNumber,
            String title,
            String slug,
            String rawContent,
            long contentVersion,
            String volumeTitle,
            String volumeSlug,
            int volumeSortOrder
    ) {
        public ReaderChapterRecord {
            if (contentVersion < 1L) {
                throw new IllegalArgumentException(
                        "Content version phải lớn hơn hoặc bằng 1."
                );
            }
        }
    }
}
