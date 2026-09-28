package com.universe.novel.infrastructure.persistence.locator;

import com.universe.novel.application.ports.PublishedChapterLocatorQueryPort;
import com.universe.novel.application.ports.PublishedChapterLocatorQueryPort.PublishedChapterLocatorRecord;
import com.universe.novel.infrastructure.persistence.chapter.PublishedChapterLocatorProjection;
import com.universe.novel.infrastructure.persistence.chapter.SpringDataChapterJpaRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence adapter implementing PublishedChapterLocatorQueryPort using Spring Data JPA.
 */
@Component
public class PublishedChapterLocatorPersistenceAdapter implements PublishedChapterLocatorQueryPort {

    private final SpringDataChapterJpaRepository chapterRepository;

    public PublishedChapterLocatorPersistenceAdapter(SpringDataChapterJpaRepository chapterRepository) {
        this.chapterRepository = Objects.requireNonNull(
                chapterRepository,
                "SpringDataChapterJpaRepository must not be null"
        );
    }

    @Override
    public Optional<PublishedChapterLocatorRecord> findPublishedByChapterNumber(int chapterNumber) {
        return chapterRepository.findPublishedChapterByNumber(chapterNumber)
                .map(this::toRecord);
    }

    @Override
    public List<PublishedChapterLocatorRecord> findPublishedByTitleKeyword(
            String exactFoldedKeyword,
            String likeFoldedKeyword,
            int limit
    ) {
        return chapterRepository.findPublishedChaptersByTitleKeyword(
                exactFoldedKeyword,
                likeFoldedKeyword,
                limit
        ).stream().map(this::toRecord).toList();
    }

    private PublishedChapterLocatorRecord toRecord(PublishedChapterLocatorProjection projection) {
        return new PublishedChapterLocatorRecord(
                UUID.fromString(projection.getId()),
                projection.getChapterNumber(),
                projection.getTitle(),
                projection.getSlug(),
                projection.getVolumeSortOrder(),
                projection.getVolumeTitle()
        );
    }
}
