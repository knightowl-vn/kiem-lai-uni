package com.universe.novel.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate Root đại diện cho một chương trong Novel Module.
 *
 * Quản lý:
 * - thông tin chương thuộc một Volume (qua volumeId);
 * - vòng đời DRAFT → PUBLISHED → ARCHIVED;
 * - quy tắc chỉnh sửa, di chuyển, sắp xếp và xuất bản;
 * - aggregate version và content version.
 *
 * Chapter không chứa đối tượng Volume.
 */
public class Chapter {

    private static final int MIN_TITLE_LENGTH =
            2;

    private static final int MAX_TITLE_LENGTH =
            250;

    private static final int MAX_SUMMARY_LENGTH =
            1000;

    private static final int MAX_CONTENT_LENGTH =
            500_000;

    private final UUID id;

    private UUID volumeId;

    private Integer chapterNumber;

    private int sortOrder;

    private String title;

    private Slug slug;

    private String summary;

    private String content;

    private ChapterStatus status;

    private final UUID createdBy;

    private UUID updatedBy;

    private UUID publishedBy;

    private UUID archivedBy;

    private final Instant createdAt;

    private Instant updatedAt;

    private Instant publishedAt;

    private Instant archivedAt;

    private long aggregateVersion;

    private long contentVersion;

    private Chapter(
            UUID id,
            UUID volumeId,
            Integer chapterNumber,
            int sortOrder,
            String title,
            Slug slug,
            String summary,
            String content,
            ChapterStatus status,
            UUID createdBy,
            UUID updatedBy,
            UUID publishedBy,
            UUID archivedBy,
            Instant createdAt,
            Instant updatedAt,
            Instant publishedAt,
            Instant archivedAt,
            long aggregateVersion,
            long contentVersion
    ) {
        this.id =
                Objects.requireNonNull(
                        id,
                        "Chapter ID không được để trống."
                );

        this.volumeId =
                Objects.requireNonNull(
                        volumeId,
                        "Volume ID không được để trống."
                );

        this.chapterNumber =
                validateChapterNumber(
                        chapterNumber
                );

        this.sortOrder =
                validateSortOrder(sortOrder);

        this.title =
                validateTitle(title);

        this.slug =
                Objects.requireNonNull(
                        slug,
                        "Slug không được để trống."
                );

        this.summary =
                validateSummary(summary);

        this.content =
                validateContent(content);

        this.status =
                Objects.requireNonNull(
                        status,
                        "Trạng thái chương không được để trống."
                );

        this.createdBy =
                Objects.requireNonNull(
                        createdBy,
                        "Người tạo chương không được để trống."
                );

        this.updatedBy =
                Objects.requireNonNull(
                        updatedBy,
                        "Người cập nhật chương không được để trống."
                );

        this.publishedBy =
                publishedBy;

        this.archivedBy =
                archivedBy;

        this.createdAt =
                Objects.requireNonNull(
                        createdAt,
                        "Thời gian tạo chương không được để trống."
                );

        this.updatedAt =
                Objects.requireNonNull(
                        updatedAt,
                        "Thời gian cập nhật chương không được để trống."
                );

        this.publishedAt =
                publishedAt;

        this.archivedAt =
                archivedAt;

        if (aggregateVersion < 1L) {
            throw new IllegalArgumentException(
                    "Aggregate version phải lớn hơn hoặc bằng 1."
            );
        }

        this.aggregateVersion =
                aggregateVersion;

        if (contentVersion < 1L) {
            throw new IllegalArgumentException(
                    "Content version phải lớn hơn hoặc bằng 1."
            );
        }

        this.contentVersion =
                contentVersion;

        validateLifecycleAuditConsistency();
    }

    /**
     * Tạo một chương mới ở trạng thái DRAFT.
     */
    public static Chapter createDraft(
            UUID id,
            UUID volumeId,
            Integer chapterNumber,
            int sortOrder,
            String title,
            Slug slug,
            String summary,
            String content,
            UUID createdBy,
            Instant createdAt
    ) {
        Objects.requireNonNull(
                createdAt,
                "Thời gian tạo chương không được để trống."
        );

        return new Chapter(
                id,
                volumeId,
                chapterNumber,
                sortOrder,
                title,
                slug,
                summary,
                content,
                ChapterStatus.DRAFT,
                createdBy,
                createdBy,
                null,
                null,
                createdAt,
                createdAt,
                null,
                null,
                1L,
                1L
        );
    }

    /**
     * Khôi phục Aggregate từ dữ liệu persistence.
     *
     * Method này không tạo thêm thay đổi nghiệp vụ.
     */
    public static Chapter rehydrate(
            UUID id,
            UUID volumeId,
            Integer chapterNumber,
            int sortOrder,
            String title,
            Slug slug,
            String summary,
            String content,
            ChapterStatus status,
            UUID createdBy,
            UUID updatedBy,
            UUID publishedBy,
            UUID archivedBy,
            Instant createdAt,
            Instant updatedAt,
            Instant publishedAt,
            Instant archivedAt,
            long aggregateVersion,
            long contentVersion
    ) {
        return new Chapter(
                id,
                volumeId,
                chapterNumber,
                sortOrder,
                title,
                slug,
                summary,
                content,
                status,
                createdBy,
                updatedBy,
                publishedBy,
                archivedBy,
                createdAt,
                updatedAt,
                publishedAt,
                archivedAt,
                aggregateVersion,
                contentVersion
        );
    }

    /**
     * Cập nhật thông tin của bản nháp.
     *
     * Chỉ được phép khi chương còn ở trạng thái DRAFT.
     */
    public void updateDraft(
            Integer chapterNumber,
            String title,
            Slug slug,
            String summary,
            String content,
            UUID updatedBy,
            Instant updatedAt
    ) {
        requireStatus(
                ChapterStatus.DRAFT,
                "Chỉ được cập nhật nội dung khi chương còn là bản nháp."
        );

        Integer normalizedChapterNumber =
                validateChapterNumber(
                        chapterNumber
                );

        String normalizedTitle =
                validateTitle(title);

        Slug normalizedSlug =
                Objects.requireNonNull(
                        slug,
                        "Slug không được để trống."
                );

        String normalizedSummary =
                validateSummary(summary);

        String normalizedContent =
                validateContent(content);

        UUID normalizedUpdatedBy =
                Objects.requireNonNull(
                        updatedBy,
                        "Người cập nhật không được để trống."
                );

        Instant normalizedUpdatedAt =
                Objects.requireNonNull(
                        updatedAt,
                        "Thời gian cập nhật không được để trống."
                );

        boolean contentChanged =
                !Objects.equals(
                        this.content,
                        normalizedContent
                );

        this.chapterNumber =
                normalizedChapterNumber;

        this.title =
                normalizedTitle;

        this.slug =
                normalizedSlug;

        this.summary =
                normalizedSummary;

        this.content =
                normalizedContent;

        this.updatedBy =
                normalizedUpdatedBy;

        this.updatedAt =
                normalizedUpdatedAt;

        increaseAggregateVersion();

        if (contentChanged) {
            increaseContentVersion();
        }
    }

    /**
     * Di chuyển chương sang một Volume khác.
     *
     * Chỉ được phép khi chương còn ở trạng thái DRAFT.
     *
     * Method này chỉ áp dụng chuyển trạng thái nội bộ của Chapter
     * (cập nhật volumeId, sortOrder và audit metadata).
     *
     * Application layer phải kiểm tra trước khi gọi:
     * - Volume đích có tồn tại hay không;
     * - việc chuyển sang Volume đó có được phép hay không;
     * - sortOrder có xung đột với chương khác trong Volume đích hay không.
     */
    public void moveToVolume(
            UUID volumeId,
            int sortOrder,
            UUID updatedBy,
            Instant updatedAt
    ) {
        requireStatus(
                ChapterStatus.DRAFT,
                "Chỉ được di chuyển chương khi còn là bản nháp."
        );

        UUID normalizedVolumeId =
                Objects.requireNonNull(
                        volumeId,
                        "Volume ID không được để trống."
                );

        int normalizedSortOrder =
                validateSortOrder(sortOrder);

        UUID normalizedUpdatedBy =
                Objects.requireNonNull(
                        updatedBy,
                        "Người cập nhật không được để trống."
                );

        Instant normalizedUpdatedAt =
                Objects.requireNonNull(
                        updatedAt,
                        "Thời gian cập nhật không được để trống."
                );

        this.volumeId =
                normalizedVolumeId;

        this.sortOrder =
                normalizedSortOrder;

        this.updatedBy =
                normalizedUpdatedBy;

        this.updatedAt =
                normalizedUpdatedAt;

        increaseAggregateVersion();
    }

    /**
     * Thay đổi thứ tự sắp xếp của chương trong Volume hiện tại.
     *
     * Được phép khi chương còn DRAFT hoặc PUBLISHED.
     *
     * Chapter chỉ kiểm tra giá trị sortOrder cục bộ và vòng đời của chính mình.
     * Tính duy nhất / xung đột sortOrder với các Chapter khác thuộc
     * Application hoặc Persistence layer.
     */
    public void reorder(
            int sortOrder,
            UUID updatedBy,
            Instant updatedAt
    ) {
        if (status == ChapterStatus.ARCHIVED) {
            throw new IllegalStateException(
                    "Không thể sắp xếp lại chương đã lưu trữ."
            );
        }

        int normalizedSortOrder =
                validateSortOrder(sortOrder);

        UUID normalizedUpdatedBy =
                Objects.requireNonNull(
                        updatedBy,
                        "Người cập nhật không được để trống."
                );

        Instant normalizedUpdatedAt =
                Objects.requireNonNull(
                        updatedAt,
                        "Thời gian cập nhật không được để trống."
                );

        this.sortOrder =
                normalizedSortOrder;

        this.updatedBy =
                normalizedUpdatedBy;

        this.updatedAt =
                normalizedUpdatedAt;

        increaseAggregateVersion();
    }

    /**
     * Xuất bản chương.
     *
     * Chapter chỉ kiểm tra khả năng xuất bản của chính mình
     * (trạng thái DRAFT và nội dung không được để trống).
     *
     * Application layer phải kiểm tra trước khi gọi:
     * - Volume cha tồn tại;
     * - Volume cha đang ở trạng thái PUBLISHED.
     */
    public void publish(
            UUID publishedBy,
            Instant publishedAt
    ) {
        requireStatus(
                ChapterStatus.DRAFT,
                "Chỉ chương ở trạng thái DRAFT mới được xuất bản."
        );

        if (content == null
                || content.isBlank()) {
            throw new IllegalStateException(
                    "Chương phải có nội dung trước khi xuất bản."
            );
        }

        UUID normalizedPublishedBy =
                Objects.requireNonNull(
                        publishedBy,
                        "Người xuất bản không được để trống."
                );

        Instant normalizedPublishedAt =
                Objects.requireNonNull(
                        publishedAt,
                        "Thời gian xuất bản không được để trống."
                );

        this.status =
                ChapterStatus.PUBLISHED;

        this.publishedBy =
                normalizedPublishedBy;

        this.publishedAt =
                normalizedPublishedAt;

        this.updatedBy =
                normalizedPublishedBy;

        this.updatedAt =
                normalizedPublishedAt;

        increaseAggregateVersion();
    }

    /**
     * Lưu trữ chương.
     *
     * Nếu chương đã xuất bản trước đó, metadata publish được giữ nguyên.
     */
    public void archive(
            UUID archivedBy,
            Instant archivedAt
    ) {
        if (status == ChapterStatus.ARCHIVED) {
            throw new IllegalStateException(
                    "Chương đã ở trạng thái ARCHIVED."
            );
        }

        UUID normalizedArchivedBy =
                Objects.requireNonNull(
                        archivedBy,
                        "Người lưu trữ chương không được để trống."
                );

        Instant normalizedArchivedAt =
                Objects.requireNonNull(
                        archivedAt,
                        "Thời gian lưu trữ chương không được để trống."
                );

        this.status =
                ChapterStatus.ARCHIVED;

        this.archivedBy =
                normalizedArchivedBy;

        this.archivedAt =
                normalizedArchivedAt;

        this.updatedBy =
                normalizedArchivedBy;

        this.updatedAt =
                normalizedArchivedAt;

        increaseAggregateVersion();
    }

    private void requireStatus(
            ChapterStatus requiredStatus,
            String errorMessage
    ) {
        if (status != requiredStatus) {
            throw new IllegalStateException(
                    errorMessage
            );
        }
    }

    private void increaseAggregateVersion() {
        this.aggregateVersion++;
    }

    private void increaseContentVersion() {
        this.contentVersion++;
    }

    /**
     * Kiểm tra metadata audit khớp với trạng thái vòng đời hiện tại.
     */
    private void validateLifecycleAuditConsistency() {
        boolean hasPublishedBy =
                publishedBy != null;

        boolean hasPublishedAt =
                publishedAt != null;

        if (hasPublishedBy != hasPublishedAt) {
            throw new IllegalArgumentException(
                    "Thông tin xuất bản của chương không nhất quán."
            );
        }

        switch (status) {
            case DRAFT -> {
                if (hasPublishedBy
                        || hasPublishedAt) {
                    throw new IllegalArgumentException(
                            "Chương DRAFT không được có thông tin xuất bản."
                    );
                }

                if (archivedBy != null
                        || archivedAt != null) {
                    throw new IllegalArgumentException(
                            "Chương DRAFT không được có thông tin lưu trữ."
                    );
                }
            }
            case PUBLISHED -> {
                if (!hasPublishedBy
                        || !hasPublishedAt) {
                    throw new IllegalArgumentException(
                            "Chương PUBLISHED phải có đầy đủ thông tin xuất bản."
                    );
                }

                if (archivedBy != null
                        || archivedAt != null) {
                    throw new IllegalArgumentException(
                            "Chương PUBLISHED không được có thông tin lưu trữ."
                    );
                }
            }
            case ARCHIVED -> {
                if (archivedBy == null
                        || archivedAt == null) {
                    throw new IllegalArgumentException(
                            "Chương ARCHIVED phải có đầy đủ thông tin lưu trữ."
                    );
                }
            }
        }
    }

    private static Integer validateChapterNumber(
            Integer chapterNumber
    ) {
        if (chapterNumber == null) {
            return null;
        }

        if (chapterNumber < 1) {
            throw new IllegalArgumentException(
                    "Số chương phải lớn hơn hoặc bằng 1."
            );
        }

        return chapterNumber;
    }

    private static int validateSortOrder(
            int sortOrder
    ) {
        if (sortOrder < 1) {
            throw new IllegalArgumentException(
                    "Thứ tự sắp xếp phải lớn hơn hoặc bằng 1."
            );
        }

        return sortOrder;
    }

    private static String validateTitle(
            String title
    ) {
        if (title == null) {
            throw new IllegalArgumentException(
                    "Tiêu đề chương không được để trống."
            );
        }

        String normalizedTitle =
                title.trim();

        if (normalizedTitle.length()
                < MIN_TITLE_LENGTH) {

            throw new IllegalArgumentException(
                    "Tiêu đề chương phải có ít nhất "
                            + MIN_TITLE_LENGTH
                            + " ký tự."
            );
        }

        if (normalizedTitle.length()
                > MAX_TITLE_LENGTH) {

            throw new IllegalArgumentException(
                    "Tiêu đề chương không được vượt quá "
                            + MAX_TITLE_LENGTH
                            + " ký tự."
            );
        }

        return normalizedTitle;
    }

    private static String validateSummary(
            String summary
    ) {
        if (summary == null) {
            return "";
        }

        String normalizedSummary =
                summary.trim();

        if (normalizedSummary.length()
                > MAX_SUMMARY_LENGTH) {

            throw new IllegalArgumentException(
                    "Tóm tắt không được vượt quá "
                            + MAX_SUMMARY_LENGTH
                            + " ký tự."
            );
        }

        return normalizedSummary;
    }

    private static String validateContent(
            String content
    ) {
        if (content == null) {
            return "";
        }

        String normalizedContent =
                content.trim();

        if (normalizedContent.length()
                > MAX_CONTENT_LENGTH) {

            throw new IllegalArgumentException(
                    "Nội dung chương không được vượt quá "
                            + MAX_CONTENT_LENGTH
                            + " ký tự."
            );
        }

        return normalizedContent;
    }

    public UUID getId() {
        return id;
    }

    public UUID getVolumeId() {
        return volumeId;
    }

    public Integer getChapterNumber() {
        return chapterNumber;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public String getTitle() {
        return title;
    }

    public Slug getSlug() {
        return slug;
    }

    public String getSummary() {
        return summary;
    }

    public String getContent() {
        return content;
    }

    public ChapterStatus getStatus() {
        return status;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public UUID getPublishedBy() {
        return publishedBy;
    }

    public UUID getArchivedBy() {
        return archivedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public long getAggregateVersion() {
        return aggregateVersion;
    }

    public long getContentVersion() {
        return contentVersion;
    }
}
