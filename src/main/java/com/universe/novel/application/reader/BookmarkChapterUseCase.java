package com.universe.novel.application.reader;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.DuplicateChapterBookmarkException;
import com.universe.novel.application.ports.ChapterBookmarkRepositoryPort;
import com.universe.novel.application.ports.ReaderChapterAccessQueryPort;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/**
 * Use case thực hiện đánh dấu một chương cho người dùng đã xác thực.
 *
 * Đảm bảo:
 * 1. Chương phải công khai khả dụng (Chapter PUBLISHED và Volume PUBLISHED);
 * 2. Tối ưu kiểm tra nhanh tồn tại trước khi vào authoritative locking transaction;
 * 3. Điều phối thực thi qua BookmarkChapterAttemptExecutor (chạy trong transaction REQUIRES_NEW);
 * 4. Tối đa 3 lần thử lại khi gặp tranh chấp khóa tạm thời (ConcurrencyFailureException);
 * 5. Giới hạn tối đa 100 bookmark cho mỗi người dùng;
 * 6. Hoàn toàn độc lập với Reading Progress và Reading History.
 */
@Service
public class BookmarkChapterUseCase {

    public static final int MAX_ATTEMPTS = 3;

    private final ReaderChapterAccessQueryPort readerChapterAccessQueryPort;
    private final ChapterBookmarkRepositoryPort chapterBookmarkRepositoryPort;
    private final BookmarkChapterAttemptExecutor attemptExecutor;

    public BookmarkChapterUseCase(
            ReaderChapterAccessQueryPort readerChapterAccessQueryPort,
            ChapterBookmarkRepositoryPort chapterBookmarkRepositoryPort,
            BookmarkChapterAttemptExecutor attemptExecutor
    ) {
        this.readerChapterAccessQueryPort = Objects.requireNonNull(
                readerChapterAccessQueryPort,
                "ReaderChapterAccessQueryPort không được để trống."
        );
        this.chapterBookmarkRepositoryPort = Objects.requireNonNull(
                chapterBookmarkRepositoryPort,
                "ChapterBookmarkRepositoryPort không được để trống."
        );
        this.attemptExecutor = Objects.requireNonNull(
                attemptExecutor,
                "BookmarkChapterAttemptExecutor không được để trống."
        );
    }

    public void execute(BookmarkChapterCommand command) {
        Objects.requireNonNull(command, "BookmarkChapterCommand không được để trống.");

        UUID userId = command.userId();
        UUID chapterId = command.chapterId();

        // 1. Kiểm tra tính công khai khả dụng của chương
        readerChapterAccessQueryPort.findPublishedById(chapterId)
                .orElseThrow(() -> new ChapterNotFoundException(chapterId));

        // 2. Fast-path: Kiểm tra nhanh nếu đã bookmark trước đó (non-authoritative)
        if (chapterBookmarkRepositoryPort.existsByUserIdAndChapterId(userId, chapterId)) {
            return;
        }

        // 3. Thực thi authoritative attempt qua attemptExecutor với vòng lặp thử lại có giới hạn (max 3)
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                attemptExecutor.executeAttempt(userId, chapterId);
                return;
            } catch (DuplicateChapterBookmarkException ex) {
                // Concurrent race: bản ghi đã được chèn đồng thời bởi request khác -> idempotent success
                return;
            } catch (ConcurrencyFailureException ex) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw ex;
                }
            }
        }
    }
}
