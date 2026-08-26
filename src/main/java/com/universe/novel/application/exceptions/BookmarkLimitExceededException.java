package com.universe.novel.application.exceptions;

import java.util.UUID;

/**
 * Ngoại lệ được ném khi người dùng cố gắng đánh dấu một chương mới
 * vượt quá giới hạn tối đa cho phép (100 dấu trang).
 */
public class BookmarkLimitExceededException extends RuntimeException {

    private final UUID userId;
    private final int limit;

    public BookmarkLimitExceededException(UUID userId, int limit) {
        super(String.format("Người dùng %s đã đạt giới hạn tối đa %d dấu trang chương.", userId, limit));
        this.userId = userId;
        this.limit = limit;
    }

    public UUID getUserId() {
        return userId;
    }

    public int getLimit() {
        return limit;
    }
}
