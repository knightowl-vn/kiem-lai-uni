package com.universe.novel.application.reader;

import java.util.Objects;
import java.util.UUID;

/**
 * Command yêu cầu ghi nhận lịch sử đọc chương cho người dùng đã xác thực.
 */
public record RecordReadingHistoryCommand(
        UUID userId,
        UUID chapterId
) {
    public RecordReadingHistoryCommand {
        Objects.requireNonNull(
                userId,
                "ID người dùng không được để trống."
        );
        Objects.requireNonNull(
                chapterId,
                "ID chương không được để trống."
        );
    }
}
