package com.universe.novel.application.ports;

import java.util.Optional;
import java.util.UUID;

public interface ReaderContinueReadingQueryPort {

    Optional<ReadableChapterDestination> findPublishedChapterById(
            UUID chapterId
    );

    Optional<Integer> findChapterNumberById(
            UUID chapterId
    );

    Optional<ReadableChapterDestination> findPreviousPublishedChapter(
            int chapterNumber
    );

    Optional<ReadableChapterDestination> findNextPublishedChapter(
            int chapterNumber
    );

    record ReadableChapterDestination(
            UUID chapterId,
            int chapterNumber,
            String title,
            String slug
    ) {
    }
}
