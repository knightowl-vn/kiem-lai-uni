package com.universe.novel.application.reader;

import com.universe.novel.application.exceptions.ReadingProgressConcurrencyException;
import com.universe.shared.time.ClockPort;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;

@Service
public class RecordReadingProgressUseCase {

    private final RecordReadingProgressAttemptExecutor attemptExecutor;
    private final ClockPort clockPort;

    public RecordReadingProgressUseCase(
            RecordReadingProgressAttemptExecutor attemptExecutor,
            ClockPort clockPort
    ) {
        this.attemptExecutor = Objects.requireNonNull(
                attemptExecutor,
                "RecordReadingProgressAttemptExecutor không được để trống."
        );
        this.clockPort = Objects.requireNonNull(
                clockPort,
                "ClockPort không được để trống."
        );
    }

    public void execute(RecordReadingProgressCommand command) {
        Objects.requireNonNull(
                command,
                "RecordReadingProgressCommand không được để trống."
        );

        Instant observedAt = clockPort.now();

        try {
            attemptExecutor.executeAttempt(
                    command.userId(),
                    command.chapterId(),
                    observedAt
            );
        } catch (ReadingProgressConcurrencyException ex) {
            // Thử lại chính xác 1 lần trong transaction mới độc lập, tái sử dụng cùng observedAt
            attemptExecutor.executeAttempt(
                    command.userId(),
                    command.chapterId(),
                    observedAt
            );
        }
    }
}
