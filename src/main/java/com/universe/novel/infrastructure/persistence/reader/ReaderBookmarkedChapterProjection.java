package com.universe.novel.infrastructure.persistence.reader;

import java.time.Instant;

/**
 * Spring Data JPA Projection cho truy vấn danh sách dấu trang chương của người đọc.
 *
 * Chỉ load các trường metadata cần thiết, không load MEDIUMTEXT content.
 */
public interface ReaderBookmarkedChapterProjection {

    String getChapterId();

    int getChapterNumber();

    String getChapterTitle();

    String getChapterSlug();

    String getVolumeTitle();

    Instant getBookmarkedAt();
}
