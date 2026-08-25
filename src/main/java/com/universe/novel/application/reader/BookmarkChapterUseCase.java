package com.universe.novel.application.reader;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.DuplicateChapterBookmarkException;
import com.universe.novel.application.ports.ChapterBookmarkRepositoryPort;
import com.universe.novel.application.ports.ReaderChapterAccessQueryPort;
import com.universe.novel.domain.reader.UserChapterBookmark;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Use case thực hiện đánh dấu một chương cho người dùng đã xác thực.
 *
 * Đảm bảo:
 * 1. Chương phải công khai khả dụng (Chapter PUBLISHED và Volume PUBLISHED);
 * 2. Tối ưu kiểm tra tồn tại trước khi chèn bản ghi mới;
 * 3. Xử lý race-condition cạnh tranh đồng thời qua ngoại lệ trùng khóa duy nhất
 *    (DuplicateChapterBookmarkException) như một hành động idempotent thành công;
 * 4. Không nuốt các lỗi toàn vẹn cơ sở dữ liệu khác;
 * 5. Hoàn toàn độc lập với Reading Progress và Reading History.
 */
@Service
@Transactional
public class BookmarkChapterUseCase {

    private final ReaderChapterAccessQueryPort readerChapterAccessQueryPort;
    private final ChapterBookmarkRepositoryPort chapterBookmarkRepositoryPort;
    private final IdGeneratorPort idGeneratorPort;
    private final ClockPort clockPort;

    public BookmarkChapterUseCase(
            ReaderChapterAccessQueryPort readerChapterAccessQueryPort,
            ChapterBookmarkRepositoryPort chapterBookmarkRepositoryPort,
            IdGeneratorPort idGeneratorPort,
            ClockPort clockPort
    ) {
        this.readerChapterAccessQueryPort = Objects.requireNonNull(
                readerChapterAccessQueryPort,
                "ReaderChapterAccessQueryPort không được để trống."
        );
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

    public void execute(BookmarkChapterCommand command) {
        Objects.requireNonNull(command, "BookmarkChapterCommand không được để trống.");

        UUID userId = command.userId();
        UUID chapterId = command.chapterId();

        // 1. Kiểm tra tính công khai khả dụng của chương
        readerChapterAccessQueryPort.findPublishedById(chapterId)
                .orElseThrow(() -> new ChapterNotFoundException(chapterId));

        // 2. Kiểm tra tối ưu nếu đã bookmark trước đó
        if (chapterBookmarkRepositoryPort.existsByUserIdAndChapterId(userId, chapterId)) {
            return;
        }

        // 3. Khởi tạo bookmark aggregate qua abstraction định danh và thời gian
        UUID bookmarkId = idGeneratorPort.generate();
        Instant now = clockPort.now();
        UserChapterBookmark bookmark = UserChapterBookmark.create(
                bookmarkId,
                userId,
                chapterId,
                now
        );

        // 4. Lưu vào cơ sở dữ liệu, xử lý idempotent nếu gặp race duplicate insert
        try {
            chapterBookmarkRepositoryPort.save(bookmark);
        } catch (DuplicateChapterBookmarkException ex) {
            // Concurrent race đã được ghi nhận thành công bởi request khác
        }
    }
}
