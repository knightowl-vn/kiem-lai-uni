package com.universe.novel.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

/**
 * Ngoại lệ ứng dụng đại diện cho xung đột đồng thời hoặc vi phạm tính duy nhất
 * khi ghi nhận tiến độ đọc của người dùng.
 */
public class ReadingProgressConcurrencyException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public ReadingProgressConcurrencyException(UUID userId, Throwable cause) {
        super(
                "READING_PROGRESS_CONCURRENCY_CONFLICT",
                "Xung đột đồng thời khi ghi nhận tiến độ đọc cho người dùng: " + userId,
                cause
        );
    }

    public ReadingProgressConcurrencyException(String message, Throwable cause) {
        super(
                "READING_PROGRESS_CONCURRENCY_CONFLICT",
                message,
                cause
        );
    }
}
