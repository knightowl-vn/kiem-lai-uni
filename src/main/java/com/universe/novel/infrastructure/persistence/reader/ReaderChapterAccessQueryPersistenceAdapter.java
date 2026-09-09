package com.universe.novel.infrastructure.persistence.reader;

import com.universe.novel.application.ports.ReaderChapterAccessQueryPort;
import com.universe.novel.application.ports.ReaderChapterAccessQueryPort.ReadableNarrationChapterReference;
import com.universe.novel.application.ports.ReaderChapterAccessQueryPort.ReadableChapterReference;
import com.universe.novel.infrastructure.persistence.chapter.ReadableChapterAccessProjection;
import com.universe.novel.infrastructure.persistence.chapter.ReadableChapterNarrationAccessProjection;
import com.universe.novel.infrastructure.persistence.chapter.SpringDataChapterJpaRepository;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class ReaderChapterAccessQueryPersistenceAdapter implements ReaderChapterAccessQueryPort {

    private final SpringDataChapterJpaRepository chapterRepository;

    public ReaderChapterAccessQueryPersistenceAdapter(
            SpringDataChapterJpaRepository chapterRepository
    ) {
        this.chapterRepository = Objects.requireNonNull(
                chapterRepository,
                "SpringDataChapterJpaRepository không được để trống."
        );
    }

    @Override
    public Optional<ReadableChapterReference> findPublishedById(UUID chapterId) {
        if (chapterId == null) {
            return Optional.empty();
        }

        return chapterRepository
                .findPublishedAccessById(chapterId.toString())
                .map(this::toReference);
    }

    @Override
    public Optional<ReadableNarrationChapterReference> findPublishedNarrationById(UUID chapterId) {
        if (chapterId == null) {
            return Optional.empty();
        }

        return chapterRepository
                .findPublishedNarrationAccessById(chapterId.toString())
                .map(this::toNarrationReference);
    }

    private ReadableChapterReference toReference(ReadableChapterAccessProjection projection) {
        return new ReadableChapterReference(
                UUID.fromString(projection.getId()),
                projection.getChapterNumber()
        );
    }

    private ReadableNarrationChapterReference toNarrationReference(
            ReadableChapterNarrationAccessProjection projection
    ) {
        return new ReadableNarrationChapterReference(
                UUID.fromString(projection.getId()),
                projection.getContentVersion()
        );
    }
}
