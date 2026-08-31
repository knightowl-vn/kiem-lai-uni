package com.universe.novel.application.exceptions;

import java.util.UUID;

/**
 * Ngoại lệ được ném khi người dùng cố gắng đánh dấu một chương đã được đánh dấu trước đó.
 */
public class DuplicateChapterBookmarkException extends RuntimeException {

    private final UUID userId;
    private final UUID chapterId;

    public DuplicateChapterBookmarkException(UUID userId, UUID chapterId) {
        super(String.format("Dấu trang chương đã tồn tại cho người dùng %s và chương %s.", userId, chapterId));
        this.userId = userId;
        this.chapterId = chapterId;
    }

    public DuplicateChapterBookmarkException(UUID userId, UUID chapterId, Throwable cause) {
        super(String.format("Dấu trang chương đã tồn tại cho người dùng %s và chương %s.", userId, chapterId), cause);
        this.userId = userId;
        this.chapterId = chapterId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getChapterId() {
        return chapterId;
    }
}
