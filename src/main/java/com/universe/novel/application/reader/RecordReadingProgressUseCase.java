package com.universe.novel.application.reader;

import com.universe.novel.application.exceptions.ReadingProgressConcurrencyException;

import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class RecordReadingProgressUseCase {

    private final RecordReadingProgressAttemptExecutor attemptExecutor;

    public RecordReadingProgressUseCase(
            RecordReadingProgressAttemptExecutor attemptExecutor
    ) {
        this.attemptExecutor = Objects.requireNonNull(
                attemptExecutor,
                "RecordReadingProgressAttemptExecutor không được để trống."
        );
    }

    public void execute(RecordReadingProgressCommand command) {
        Objects.requireNonNull(
                command,
                "RecordReadingProgressCommand không được để trống."
        );

        try {
            attemptExecutor.executeAttempt(
                    command.userId(),
                    command.chapterId()
            );
        } catch (ReadingProgressConcurrencyException ex) {
            // Thử lại chính xác 1 lần trong transaction mới độc lập
            attemptExecutor.executeAttempt(
                    command.userId(),
                    command.chapterId()
            );
        }
    }
}
