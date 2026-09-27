package com.universe.novel.application.reader;

import com.universe.novel.application.exceptions.DuplicateReadingHistoryException;
import com.universe.shared.time.ClockPort;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;

/**
 * Use case ghi nhận lịch sử đọc một chương cho người dùng đã xác thực.
 *
 * Điều phối thực thi qua RecordReadingHistoryAttemptExecutor (chạy trong transaction REQUIRES_NEW).
 * Thời điểm quan sát observedAt được ghi nhận một lần duy nhất trước vòng lặp thử lại.
 * Khi xảy ra các lỗi tranh chấp tạm thời (DuplicateReadingHistoryException hoặc ConcurrencyFailureException như deadlock MySQL 1213),
 * use case thực hiện thử lại tối đa 3 lần trong transaction mới độc lập với cùng mốc observedAt.
 */
@Service
public class RecordReadingHistoryUseCase {

    public static final int MAX_ATTEMPTS = 3;

    private final RecordReadingHistoryAttemptExecutor attemptExecutor;
    private final ClockPort clockPort;

    public RecordReadingHistoryUseCase(
            RecordReadingHistoryAttemptExecutor attemptExecutor,
            ClockPort clockPort
    ) {
        this.attemptExecutor = Objects.requireNonNull(
                attemptExecutor,
                "RecordReadingHistoryAttemptExecutor không được để trống."
        );
        this.clockPort = Objects.requireNonNull(
                clockPort,
                "ClockPort không được để trống."
        );
    }

    public void execute(RecordReadingHistoryCommand command) {
        Objects.requireNonNull(
                command,
                "RecordReadingHistoryCommand không được để trống."
        );

        Instant observedAt = clockPort.now();

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                attemptExecutor.executeAttempt(
                        command.userId(),
                        command.chapterId(),
                        observedAt
                );
                return;
            } catch (DuplicateReadingHistoryException | ConcurrencyFailureException ex) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw ex;
                }
                // Thử lại trong transaction mới độc lập ở vòng lặp kế tiếp với cùng observedAt
            }
        }
    }
}
