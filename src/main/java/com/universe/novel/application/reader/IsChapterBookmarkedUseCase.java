package com.universe.novel.application.reader;

import com.universe.novel.application.ports.ReaderBookmarkedChaptersQueryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Use case kiểm tra trạng thái đánh dấu của một chương đối với người dùng.
 *
 * Kiểm tra sự tồn tại của bản ghi bookmark (private reader state) độc lập với
 * trạng thái xuất bản của chương.
 */
@Service
@Transactional(readOnly = true)
public class IsChapterBookmarkedUseCase {

    private final ReaderBookmarkedChaptersQueryPort readerBookmarkedChaptersQueryPort;

    public IsChapterBookmarkedUseCase(
            ReaderBookmarkedChaptersQueryPort readerBookmarkedChaptersQueryPort
    ) {
        this.readerBookmarkedChaptersQueryPort = Objects.requireNonNull(
                readerBookmarkedChaptersQueryPort,
                "ReaderBookmarkedChaptersQueryPort không được để trống."
        );
    }

    public boolean execute(UUID userId, UUID chapterId) {
        Objects.requireNonNull(userId, "ID người dùng không được để trống.");
        Objects.requireNonNull(chapterId, "ID chương không được để trống.");
        return readerBookmarkedChaptersQueryPort.isBookmarked(userId, chapterId);
    }
}
