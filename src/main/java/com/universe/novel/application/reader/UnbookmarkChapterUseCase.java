package com.universe.novel.application.reader;

import com.universe.novel.application.ports.ChapterBookmarkRepositoryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Use case thực hiện hủy đánh dấu một chương cho người dùng đã xác thực.
 *
 * Đảm bảo:
 * 1. Xóa chính xác theo cặp (userId, chapterId);
 * 2. Idempotent: thành công cho cả trường hợp bookmark đã tồn tại hoặc chưa từng tồn tại;
 * 3. Không phụ thuộc vào trạng thái công khai của chương, cho phép người dùng dọn dẹp
 *    dấu trang cũ của các chương hoặc quyển đã bị hạ (DRAFT / ARCHIVED);
 * 4. Hoàn toàn độc lập với Reading Progress và Reading History.
 */
@Service
@Transactional
public class UnbookmarkChapterUseCase {

    private final ChapterBookmarkRepositoryPort chapterBookmarkRepositoryPort;

    public UnbookmarkChapterUseCase(
            ChapterBookmarkRepositoryPort chapterBookmarkRepositoryPort
    ) {
        this.chapterBookmarkRepositoryPort = Objects.requireNonNull(
                chapterBookmarkRepositoryPort,
                "ChapterBookmarkRepositoryPort không được để trống."
        );
    }

    public void execute(UnbookmarkChapterCommand command) {
        Objects.requireNonNull(command, "UnbookmarkChapterCommand không được để trống.");

        UUID userId = command.userId();
        UUID chapterId = command.chapterId();

        chapterBookmarkRepositoryPort.deleteByUserIdAndChapterId(userId, chapterId);
    }
}
