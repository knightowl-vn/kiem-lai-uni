package com.universe.novel.infrastructure.persistence.reader;

import com.universe.novel.application.ports.ReaderContinueReadingQueryPort;
import com.universe.novel.application.ports.ReaderContinueReadingQueryPort.ReadableChapterDestination;
import com.universe.novel.infrastructure.persistence.chapter.ReaderChapterListItemProjection;
import com.universe.novel.infrastructure.persistence.chapter.SpringDataChapterJpaRepository;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class ReaderContinueReadingQueryPersistenceAdapter
        implements ReaderContinueReadingQueryPort {

    private final SpringDataChapterJpaRepository chapterRepository;

    public ReaderContinueReadingQueryPersistenceAdapter(
            SpringDataChapterJpaRepository chapterRepository
    ) {
        this.chapterRepository = Objects.requireNonNull(
                chapterRepository,
                "SpringDataChapterJpaRepository không được để trống."
        );
    }

    @Override
    public Optional<ReadableChapterDestination> findPublishedChapterById(
            UUID chapterId
    ) {
        if (chapterId == null) {
            return Optional.empty();
        }

        return chapterRepository
                .findPublishedReaderChapterById(chapterId.toString())
                .map(this::toDestination);
    }

    @Override
    public Optional<Integer> findChapterNumberById(
            UUID chapterId
    ) {
        if (chapterId == null) {
            return Optional.empty();
        }

        return chapterRepository
                .findChapterNumberById(chapterId.toString());
    }

    @Override
    public Optional<ReadableChapterDestination> findPreviousPublishedChapter(
            int chapterNumber
    ) {
        return chapterRepository
                .findPreviousPublishedReaderChapter(chapterNumber)
                .map(this::toDestination);
    }

    @Override
    public Optional<ReadableChapterDestination> findNextPublishedChapter(
            int chapterNumber
    ) {
        return chapterRepository
                .findNextPublishedReaderChapter(chapterNumber)
                .map(this::toDestination);
    }

    private ReadableChapterDestination toDestination(
            ReaderChapterListItemProjection projection
    ) {
        return new ReadableChapterDestination(
                UUID.fromString(projection.getId()),
                projection.getChapterNumber(),
                projection.getTitle(),
                projection.getSlug()
        );
    }
}
