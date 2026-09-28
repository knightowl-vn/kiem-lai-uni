package com.universe.novel.application.ports;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Port for querying lightweight published chapter data for the chapter locator.
 */
public interface PublishedChapterLocatorQueryPort {

    /**
     * Looks up a published chapter by its exact chapter number.
     * Enforces fail-closed visibility: both chapter and volume must be PUBLISHED.
     *
     * @param chapterNumber globally unique chapter number
     * @return Optional containing the record if found and published, else empty
     */
    Optional<PublishedChapterLocatorRecord> findPublishedByChapterNumber(int chapterNumber);

    /**
     * Looks up published chapters by matching title with unescaped folded and escaped folded keyword parameters,
     * ordered by relevance (exact > prefix > contains) and bounded at the DB level.
     * Enforces fail-closed visibility: both chapter and volume must be PUBLISHED.
     *
     * @param exactFoldedKeyword unescaped keyword with đ/Đ folded to d (for exact SQL equality matching)
     * @param likeFoldedKeyword escaped keyword with đ/Đ folded to d and LIKE wildcards escaped (for SQL LIKE matching)
     * @param limit maximum candidate count to fetch from database
     * @return List of matching published chapter records
     */
    List<PublishedChapterLocatorRecord> findPublishedByTitleKeyword(
            String exactFoldedKeyword,
            String likeFoldedKeyword,
            int limit
    );

    /**
     * Internal projection record within application layer.
     */
    record PublishedChapterLocatorRecord(
            UUID id,
            int chapterNumber,
            String title,
            String slug,
            int volumeSortOrder,
            String volumeTitle
    ) {
        public PublishedChapterLocatorRecord {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(title, "title must not be null");
            Objects.requireNonNull(slug, "slug must not be null");
        }
    }
}
