package com.universe.novel.application.reader;

import com.universe.novel.application.exceptions.DuplicateReadingHistoryException;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Use case ghi nhận lịch sử đọc một chương cho người dùng đã xác thực.
 *
 * Điều phối thực thi qua RecordReadingHistoryAttemptExecutor.
 * Khi xảy ra DuplicateReadingHistoryException do xung đột chèn đồng thời giữa 2 request,
 * use case thực hiện thử lại chính xác 1 lần trong transaction mới độc lập (REQUIRES_NEW)
 * để cập nhật lại thời điểm đọc mà không gặp lỗi rollback-only.
 */
@Service
public class RecordReadingHistoryUseCase {

    private final RecordReadingHistoryAttemptExecutor attemptExecutor;

    public RecordReadingHistoryUseCase(
            RecordReadingHistoryAttemptExecutor attemptExecutor
    ) {
        this.attemptExecutor = Objects.requireNonNull(
                attemptExecutor,
                "RecordReadingHistoryAttemptExecutor không được để trống."
        );
    }

    public void execute(RecordReadingHistoryCommand command) {
        Objects.requireNonNull(
                command,
                "RecordReadingHistoryCommand không được để trống."
        );

        try {
            attemptExecutor.executeAttempt(
                    command.userId(),
                    command.chapterId()
            );
        } catch (DuplicateReadingHistoryException ex) {
            // Thử lại chính xác 1 lần trong transaction mới độc lập
            attemptExecutor.executeAttempt(
                    command.userId(),
                    command.chapterId()
            );
        }
    }
}
