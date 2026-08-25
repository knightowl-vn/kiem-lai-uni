package com.universe.novel.application.reader;

import java.util.Objects;
import java.util.UUID;

/**
 * Command yêu cầu đánh dấu một chương cho người dùng đã xác thực.
 */
public record BookmarkChapterCommand(
        UUID userId,
        UUID chapterId
) {
    public BookmarkChapterCommand {
        Objects.requireNonNull(userId, "ID người dùng không được để trống.");
        Objects.requireNonNull(chapterId, "ID chương không được để trống.");
    }
}
