package com.universe.novel.infrastructure.persistence.reader;

import java.time.Instant;

/**
 * Spring Data JPA Projection cho truy vấn danh sách lịch sử đọc chương của người đọc.
 *
 * Chỉ load các trường metadata cần thiết, không load MEDIUMTEXT content hay summary.
 */
public interface ReaderReadingHistoryProjection {

    String getChapterId();

    int getChapterNumber();

    String getChapterTitle();

    String getChapterSlug();

    String getVolumeTitle();

    Instant getLastReadAt();
}
