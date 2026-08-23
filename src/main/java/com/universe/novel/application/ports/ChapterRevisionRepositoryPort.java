package com.universe.novel.application.ports;

import com.universe.novel.domain.revision.ChapterRevision;

import java.util.Optional;
import java.util.UUID;

public interface ChapterRevisionRepositoryPort {

    void save(ChapterRevision revision);

    Optional<ChapterRevision> findByChapterIdAndRevisionNumber(
            UUID chapterId,
            long revisionNumber
    );

    /**
     * Kiểm tra điều kiện nghiêm ngặt để cho phép xóa cứng bản nháp:
     * - Lịch sử revision không rỗng;
     * - Tồn tại ít nhất một revision CREATE_DRAFT;
     * - Không có BASELINE, PUBLISH, UNPUBLISH, ARCHIVE, RESTORE_TO_DRAFT;
     * - Tất cả revision đều có trạng thái DRAFT;
     * - Tất cả changeType đều thuộc tập {CREATE_DRAFT, UPDATE_DRAFT, MOVE_VOLUME, RESTORE_REVISION}.
     */
    boolean canSafelyHardDelete(UUID chapterId);

    void deleteAllByChapterId(UUID chapterId);
}
