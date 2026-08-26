package com.universe.novel.application.ports;

import com.universe.novel.domain.reader.UserChapterReadingHistory;

import java.util.Optional;
import java.util.UUID;

/**
 * Port giao tiếp persistence cho Aggregate UserChapterReadingHistory.
 *
 * Thuần túy không phụ thuộc JPA Entity, Spring Data, hay các kiểu dữ liệu framework.
 */
public interface ReadingHistoryRepositoryPort {

    Optional<UserChapterReadingHistory> findByUserIdAndChapterId(UUID userId, UUID chapterId);

    UserChapterReadingHistory save(UserChapterReadingHistory history);

    void pruneOldestEntriesExceedingLimit(UUID userId, int retentionLimit);
}
