package com.universe.novel.application.ports;

import com.universe.novel.domain.reader.UserChapterBookmark;

import java.util.UUID;

public interface ChapterBookmarkRepositoryPort {

    boolean existsByUserIdAndChapterId(UUID userId, UUID chapterId);

    long countByUserIdForUpdate(UUID userId);

    boolean existsByUserIdAndChapterIdForUpdate(UUID userId, UUID chapterId);

    void save(UserChapterBookmark bookmark);

    int deleteByUserIdAndChapterId(UUID userId, UUID chapterId);
}
