package com.universe.novel.contracts.dto.reader;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Lightweight DTO đại diện cho một chương được đánh dấu trong danh sách dấu trang của người đọc.
 *
 * Chỉ chứa thông tin metadata cần thiết cho giao diện danh sách, không load content hoặc summary.
 */
public record ReaderBookmarkedChapterDTO(
        UUID chapterId,
        int chapterNumber,
        String chapterTitle,
        String chapterSlug,
        String volumeTitle,
        Instant bookmarkedAt
) {
    public ReaderBookmarkedChapterDTO {
        Objects.requireNonNull(chapterId, "ID chương không được để trống.");
        Objects.requireNonNull(chapterTitle, "Tiêu đề chương không được để trống.");
        Objects.requireNonNull(chapterSlug, "Slug chương không được để trống.");
        Objects.requireNonNull(volumeTitle, "Tiêu đề quyển không được để trống.");
        Objects.requireNonNull(bookmarkedAt, "Thời gian đánh dấu không được để trống.");
    }
}
