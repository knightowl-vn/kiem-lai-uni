package com.universe.novel.application.exceptions;

import java.util.UUID;

/**
 * Ngoại lệ được ném khi xảy ra tranh chấp ghi nhận lịch sử đọc trùng lặp
 * cho cùng một cặp (userId, chapterId).
 */
public class DuplicateReadingHistoryException extends RuntimeException {

    private final UUID userId;
    private final UUID chapterId;

    public DuplicateReadingHistoryException(UUID userId, UUID chapterId) {
        super(String.format("Lịch sử đọc chương đã tồn tại cho người dùng %s và chương %s.", userId, chapterId));
        this.userId = userId;
        this.chapterId = chapterId;
    }

    public DuplicateReadingHistoryException(UUID userId, UUID chapterId, Throwable cause) {
        super(String.format("Lịch sử đọc chương đã tồn tại cho người dùng %s và chương %s.", userId, chapterId), cause);
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
