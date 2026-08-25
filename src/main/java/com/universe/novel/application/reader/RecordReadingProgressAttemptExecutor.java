package com.universe.novel.application.reader;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.ports.ReaderChapterAccessQueryPort;
import com.universe.novel.application.ports.ReaderChapterAccessQueryPort.ReadableChapterReference;
import com.universe.novel.application.ports.ReadingProgressRepositoryPort;
import com.universe.novel.domain.reader.UserReadingProgress;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class RecordReadingProgressAttemptExecutor {

    private final ReaderChapterAccessQueryPort readerChapterAccessQueryPort;
    private final ReadingProgressRepositoryPort readingProgressRepositoryPort;
    private final IdGeneratorPort idGeneratorPort;
    private final ClockPort clockPort;

    public RecordReadingProgressAttemptExecutor(
            ReaderChapterAccessQueryPort readerChapterAccessQueryPort,
            ReadingProgressRepositoryPort readingProgressRepositoryPort,
            IdGeneratorPort idGeneratorPort,
            ClockPort clockPort
    ) {
        this.readerChapterAccessQueryPort = Objects.requireNonNull(
                readerChapterAccessQueryPort,
                "ReaderChapterAccessQueryPort không được để trống."
        );
        this.readingProgressRepositoryPort = Objects.requireNonNull(
                readingProgressRepositoryPort,
                "ReadingProgressRepositoryPort không được để trống."
        );
        this.idGeneratorPort = Objects.requireNonNull(
                idGeneratorPort,
                "IdGeneratorPort không được để trống."
        );
        this.clockPort = Objects.requireNonNull(
                clockPort,
                "ClockPort không được để trống."
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void executeAttempt(UUID userId, UUID chapterId) {
        Objects.requireNonNull(
                userId,
                "ID người dùng không được để trống."
        );
        Objects.requireNonNull(
                chapterId,
                "ID chương không được để trống."
        );

        Instant now = clockPort.now();

        ReadableChapterReference chapter = readerChapterAccessQueryPort
                .findPublishedById(chapterId)
                .orElseThrow(() -> new ChapterNotFoundException(chapterId));

        Optional<UserReadingProgress> existingProgress =
                readingProgressRepositoryPort.findByUserId(userId);

        if (existingProgress.isPresent()) {
            UserReadingProgress progress = existingProgress.get();
            boolean changed = progress.recordChapterAccess(
                    chapter.chapterId(),
                    chapter.chapterNumber(),
                    now
            );
            if (changed) {
                readingProgressRepositoryPort.save(progress);
            }
        } else {
            UUID progressId = idGeneratorPort.generate();
            UserReadingProgress newProgress = UserReadingProgress.createInitial(
                    progressId,
                    userId,
                    chapter.chapterId(),
                    chapter.chapterNumber(),
                    now
            );
            readingProgressRepositoryPort.save(newProgress);
        }
    }
}
