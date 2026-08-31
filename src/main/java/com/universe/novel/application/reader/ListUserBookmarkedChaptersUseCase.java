package com.universe.novel.application.reader;

import com.universe.novel.application.ports.ReaderBookmarkedChaptersQueryPort;
import com.universe.novel.contracts.dto.reader.ReaderBookmarkedChapterDTO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Use case truy vấn danh sách các chương đã đánh dấu của người dùng.
 *
 * Chỉ trả về các chương hiện đang công khai (Chapter PUBLISHED và Volume PUBLISHED),
 * sắp xếp theo thời gian đánh dấu mới nhất trước.
 */
@Service
@Transactional(readOnly = true)
public class ListUserBookmarkedChaptersUseCase {

    private final ReaderBookmarkedChaptersQueryPort readerBookmarkedChaptersQueryPort;

    public ListUserBookmarkedChaptersUseCase(
            ReaderBookmarkedChaptersQueryPort readerBookmarkedChaptersQueryPort
    ) {
        this.readerBookmarkedChaptersQueryPort = Objects.requireNonNull(
                readerBookmarkedChaptersQueryPort,
                "ReaderBookmarkedChaptersQueryPort không được để trống."
        );
    }

    public List<ReaderBookmarkedChapterDTO> execute(UUID userId) {
        Objects.requireNonNull(userId, "ID người dùng không được để trống.");
        return readerBookmarkedChaptersQueryPort.findBookmarkedChaptersByUserId(userId);
    }
}
