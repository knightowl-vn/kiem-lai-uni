package com.universe.novel.application.reader;

import com.universe.novel.application.exceptions.BookmarkLimitExceededException;
import com.universe.novel.application.ports.ChapterBookmarkRepositoryPort;
import com.universe.novel.domain.reader.UserChapterBookmark;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Thực thi một lượt đánh dấu chương cho người dùng trong transaction độc lập (REQUIRES_NEW).
 *
 * Thực hiện:
 * 1. Khóa và đếm số lượng bookmark hiện tại của user qua locking query (FOR UPDATE);
 * 2. Thực hiện locking current read kiểm tra lại nếu (userId, chapterId) đã tồn tại;
 * 3. Nếu đã tồn tại -> Trả về thành công idempotent ngay lập tức;
 * 4. Nếu count >= 100 -> Ném BookmarkLimitExceededException;
 * 5. Ngược lại -> Tạo và lưu UserChapterBookmark mới.
 *
 * Lưu ý: DuplicateChapterBookmarkException được để lan truyền ra ngoài nhằm đảm bảo
 * transaction REQUIRES_NEW thất bại được rollback sạch sẽ mà không gặp lỗi rollback-only.
 */
@Component
public class BookmarkChapterAttemptExecutor {

    public static final int MAX_BOOKMARKS = 100;

    private final ChapterBookmarkRepositoryPort chapterBookmarkRepositoryPort;
    private final IdGeneratorPort idGeneratorPort;
    private final ClockPort clockPort;

    public BookmarkChapterAttemptExecutor(
            ChapterBookmarkRepositoryPort chapterBookmarkRepositoryPort,
            IdGeneratorPort idGeneratorPort,
            ClockPort clockPort
    ) {
        this.chapterBookmarkRepositoryPort = Objects.requireNonNull(
                chapterBookmarkRepositoryPort,
                "ChapterBookmarkRepositoryPort không được để trống."
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
        Objects.requireNonNull(userId, "userId không được để trống.");
        Objects.requireNonNull(chapterId, "chapterId không được để trống.");

        // 1. Khóa và lấy số lượng bookmark hiện tại của người dùng
        long currentCount = chapterBookmarkRepositoryPort.countByUserIdForUpdate(userId);

        // 2. Kiểm tra tồn tại với locking current read sau khi đã giữ khóa
        boolean alreadyBookmarked = chapterBookmarkRepositoryPort.existsByUserIdAndChapterIdForUpdate(userId, chapterId);
        if (alreadyBookmarked) {
            // 3. Đã được bookmark -> thành công idempotent
            return;
        }

        // 4. Nếu đạt ngưỡng 100 -> từ chối với ngoại lệ giới hạn
        if (currentCount >= MAX_BOOKMARKS) {
            throw new BookmarkLimitExceededException(userId, MAX_BOOKMARKS);
        }

        // 5. Khởi tạo bookmark aggregate và lưu vào cơ sở dữ liệu
        UUID bookmarkId = idGeneratorPort.generate();
        Instant now = clockPort.now();
        UserChapterBookmark bookmark = UserChapterBookmark.create(
                bookmarkId,
                userId,
                chapterId,
                now
        );

        chapterBookmarkRepositoryPort.save(bookmark);
    }
}
