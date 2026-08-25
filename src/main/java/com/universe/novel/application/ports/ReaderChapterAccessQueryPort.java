package com.universe.novel.application.ports;

import java.util.Optional;
import java.util.UUID;

public interface ReaderChapterAccessQueryPort {

    Optional<ReadableChapterReference> findPublishedById(
            UUID chapterId
    );

    record ReadableChapterReference(
            UUID chapterId,
            int chapterNumber
    ) {
    }
}
