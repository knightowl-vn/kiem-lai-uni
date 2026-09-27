package com.universe.novel.application.reader;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.ports.ReaderChapterAccessQueryPort;
import com.universe.novel.application.ports.ReadingHistoryRepositoryPort;
import com.universe.novel.domain.reader.UserChapterReadingHistory;
import com.universe.shared.id.IdGeneratorPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Thực thi một lượt ghi nhận lịch sử đọc trong transaction độc lập (REQUIRES_NEW).
 * Cho phép retry an toàn khi xảy ra race-condition mà không bị ảnh hưởng bởi cờ rollback-only.
 *
 * Đồng thời tự động thu dọn (pruning) các bản ghi cũ nhất khi tạo bản ghi mới vượt quá giới hạn 50 mục/người dùng.
 */
@Component
public class RecordReadingHistoryAttemptExecutor {

    public static final int MAX_HISTORY_RETENTION = 50;

    private final ReaderChapterAccessQueryPort readerChapterAccessQueryPort;
    private final ReadingHistoryRepositoryPort readingHistoryRepositoryPort;
    private final IdGeneratorPort idGeneratorPort;

    public RecordReadingHistoryAttemptExecutor(
            ReaderChapterAccessQueryPort readerChapterAccessQueryPort,
            ReadingHistoryRepositoryPort readingHistoryRepositoryPort,
            IdGeneratorPort idGeneratorPort
    ) {
        this.readerChapterAccessQueryPort = Objects.requireNonNull(
                readerChapterAccessQueryPort,
                "ReaderChapterAccessQueryPort không được để trống."
        );
        this.readingHistoryRepositoryPort = Objects.requireNonNull(
                readingHistoryRepositoryPort,
                "ReadingHistoryRepositoryPort không được để trống."
        );
        this.idGeneratorPort = Objects.requireNonNull(
                idGeneratorPort,
                "IdGeneratorPort không được để trống."
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void executeAttempt(UUID userId, UUID chapterId, Instant observedAt) {
        Objects.requireNonNull(
                userId,
                "ID người dùng không được để trống."
        );
        Objects.requireNonNull(
                chapterId,
                "ID chương không được để trống."
        );
        Objects.requireNonNull(
                observedAt,
                "Thời gian quan sát không được để trống."
        );

        // 1. Kiểm tra tính công khai khả dụng của chương
        readerChapterAccessQueryPort.findPublishedById(chapterId)
                .orElseThrow(() -> new ChapterNotFoundException(chapterId));

        // 2. Tìm kiếm bản ghi hiện có
        Optional<UserChapterReadingHistory> existingHistoryOpt =
                readingHistoryRepositoryPort.findByUserIdAndChapterId(userId, chapterId);

        if (existingHistoryOpt.isPresent()) {
            UserChapterReadingHistory history = existingHistoryOpt.get();
            boolean changed = history.recordRead(observedAt);
            if (changed) {
                readingHistoryRepositoryPort.save(history);
            }
        } else {
            UUID historyId = idGeneratorPort.generate();
            UserChapterReadingHistory newHistory = UserChapterReadingHistory.createInitial(
                    historyId,
                    userId,
                    chapterId,
                    observedAt
            );
            readingHistoryRepositoryPort.save(newHistory);
            readingHistoryRepositoryPort.pruneOldestEntriesExceedingLimit(userId, MAX_HISTORY_RETENTION);
        }
    }
}
