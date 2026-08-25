package com.universe.novel.application.ports;

import com.universe.novel.contracts.dto.reader.ReaderBookmarkedChapterDTO;

import java.util.List;
import java.util.UUID;

/**
 * Query Port cho các tác vụ đọc/truy vấn danh sách và trạng thái dấu trang của độc giả.
 */
public interface ReaderBookmarkedChaptersQueryPort {

    List<ReaderBookmarkedChapterDTO> findBookmarkedChaptersByUserId(UUID userId);

    boolean isBookmarked(UUID userId, UUID chapterId);
}
