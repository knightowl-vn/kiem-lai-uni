package com.universe.novel.application.reader;

import com.universe.novel.application.exceptions.DuplicateReadingHistoryException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Use case ghi nhận lịch sử đọc một chương cho người dùng đã xác thực.
 *
 * Điều phối thực thi qua RecordReadingHistoryAttemptExecutor (chạy trong transaction REQUIRES_NEW).
 * Khi xảy ra các lỗi tranh chấp tạm thời (DuplicateReadingHistoryException hoặc ConcurrencyFailureException như deadlock MySQL 1213),
 * use case thực hiện thử lại tối đa 3 lần trong transaction mới độc lập.
 */
@Service
public class RecordReadingHistoryUseCase {

    public static final int MAX_ATTEMPTS = 3;

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

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                attemptExecutor.executeAttempt(
                        command.userId(),
                        command.chapterId()
                );
                return;
            } catch (DuplicateReadingHistoryException | ConcurrencyFailureException ex) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw ex;
                }
                // Thử lại trong transaction mới độc lập ở vòng lặp kế tiếp
            }
        }
    }
}
