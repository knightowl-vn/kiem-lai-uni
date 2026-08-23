package com.universe.novel.domain.revision;

import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.ChapterStatus;
import com.universe.novel.domain.Slug;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Snapshot bất biến đại diện cho trạng thái lịch sử của một Chapter tại một thời điểm.
 *
 * revisionNumber:
 * - Số thứ tự mutation của Chapter Aggregate (khớp aggregateVersion tại thời điểm snapshot).
 *
 * contentVersion:
 * - Phiên bản nội dung văn bản biên tập.
 */
public record ChapterRevision(
        UUID id,
        UUID chapterId,
        UUID volumeId,
        long revisionNumber,
        long contentVersion,
        int chapterNumber,
        String title,
        Slug slug,
        String summary,
        String content,
        ChapterStatus status,
        ChapterRevisionChangeType changeType,
        String editSummary,
        UUID editedBy,
        Instant createdAt
) {

    private static final int MIN_TITLE_LENGTH = 2;
    private static final int MAX_TITLE_LENGTH = 250;
    private static final int MAX_SUMMARY_LENGTH = 1000;
    private static final int MAX_CONTENT_LENGTH = 500_000;
    private static final int MAX_EDIT_SUMMARY_LENGTH = 500;

    public ChapterRevision {
        Objects.requireNonNull(id, "Revision ID không được để trống.");
        Objects.requireNonNull(chapterId, "Chapter ID không được để trống.");
        Objects.requireNonNull(volumeId, "Volume ID không được để trống.");

        if (revisionNumber < 1L) {
            throw new IllegalArgumentException("Revision number phải lớn hơn hoặc bằng 1.");
        }

        if (contentVersion < 1L) {
            throw new IllegalArgumentException("Content version phải lớn hơn hoặc bằng 1.");
        }

        if (chapterNumber < 1) {
            throw new IllegalArgumentException("Số chương phải lớn hơn hoặc bằng 1.");
        }

        title = validateTitle(title);
        Objects.requireNonNull(slug, "Slug không được để trống.");
        summary = validateSummary(summary);
        content = validateContent(content);
        Objects.requireNonNull(status, "Trạng thái chương không được để trống.");
        Objects.requireNonNull(changeType, "Loại thay đổi không được để trống.");
        editSummary = normalizeEditSummary(editSummary);
        Objects.requireNonNull(editedBy, "Người thực hiện chỉnh sửa không được để trống.");
        Objects.requireNonNull(createdAt, "Thời gian tạo revision không được để trống.");
    }

    /**
     * Tạo snapshot từ trạng thái hiện tại của Chapter Aggregate.
     */
    public static ChapterRevision createSnapshot(
            UUID revisionId,
            Chapter chapter,
            ChapterRevisionChangeType changeType,
            String editSummary,
            UUID editedBy,
            Instant createdAt
    ) {
        Objects.requireNonNull(chapter, "Chapter không được để trống.");
        Objects.requireNonNull(editedBy, "Người thực hiện chỉnh sửa không được để trống.");
        Objects.requireNonNull(createdAt, "Thời gian tạo revision không được để trống.");

        return new ChapterRevision(
                revisionId,
                chapter.getId(),
                chapter.getVolumeId(),
                chapter.getAggregateVersion(),
                chapter.getContentVersion(),
                chapter.getChapterNumber(),
                chapter.getTitle(),
                chapter.getSlug(),
                chapter.getSummary(),
                chapter.getContent(),
                chapter.getStatus(),
                changeType,
                editSummary,
                editedBy,
                createdAt
        );
    }

    private static String validateTitle(String title) {
        if (title == null) {
            throw new IllegalArgumentException("Tiêu đề chương không được để trống.");
        }

        String normalizedTitle = title.trim();
        if (normalizedTitle.length() < MIN_TITLE_LENGTH) {
            throw new IllegalArgumentException(
                    "Tiêu đề chương phải có ít nhất " + MIN_TITLE_LENGTH + " ký tự."
            );
        }

        if (normalizedTitle.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException(
                    "Tiêu đề chương không được vượt quá " + MAX_TITLE_LENGTH + " ký tự."
            );
        }

        return normalizedTitle;
    }

    private static String validateSummary(String summary) {
        if (summary == null) {
            return "";
        }

        String normalizedSummary = summary.trim();
        if (normalizedSummary.length() > MAX_SUMMARY_LENGTH) {
            throw new IllegalArgumentException(
                    "Tóm tắt không được vượt quá " + MAX_SUMMARY_LENGTH + " ký tự."
            );
        }

        return normalizedSummary;
    }

    private static String validateContent(String content) {
        if (content == null) {
            return "";
        }

        String normalizedContent = content.trim();
        if (normalizedContent.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException(
                    "Nội dung chương không được vượt quá " + MAX_CONTENT_LENGTH + " ký tự."
            );
        }

        return normalizedContent;
    }

    private static String normalizeEditSummary(String editSummary) {
        if (editSummary == null || editSummary.isBlank()) {
            return null;
        }

        String normalizedValue = editSummary.trim();
        if (normalizedValue.length() > MAX_EDIT_SUMMARY_LENGTH) {
            throw new IllegalArgumentException(
                    "Mô tả chỉnh sửa không được vượt quá " + MAX_EDIT_SUMMARY_LENGTH + " ký tự."
            );
        }

        return normalizedValue;
    }
}
