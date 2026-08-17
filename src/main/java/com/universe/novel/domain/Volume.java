package com.universe.novel.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate Root đại diện cho một tập (volume) trong Novel Module.
 *
 * Quản lý:
 * - thông tin chung của tập;
 * - vòng đời DRAFT → PUBLISHED → ARCHIVED;
 * - các quy tắc chỉnh sửa, sắp xếp và xuất bản;
 * - aggregate version.
 *
 * Volume không chứa danh sách Chapter.
 */
public class Volume {

    private static final int MIN_TITLE_LENGTH =
            2;

    private static final int MAX_TITLE_LENGTH =
            200;

    private static final int MAX_DESCRIPTION_LENGTH =
            1000;

    private final UUID id;

    private String title;

    private Slug slug;

    private String description;

    private int sortOrder;

    private VolumeStatus status;

    private final UUID createdBy;

    private UUID updatedBy;

    private UUID publishedBy;

    private UUID archivedBy;

    private final Instant createdAt;

    private Instant updatedAt;

    private Instant publishedAt;

    private Instant archivedAt;

    private long aggregateVersion;

    private Volume(
            UUID id,
            String title,
            Slug slug,
            String description,
            int sortOrder,
            VolumeStatus status,
            UUID createdBy,
            UUID updatedBy,
            UUID publishedBy,
            UUID archivedBy,
            Instant createdAt,
            Instant updatedAt,
            Instant publishedAt,
            Instant archivedAt,
            long aggregateVersion
    ) {
        this.id =
                Objects.requireNonNull(
                        id,
                        "Volume ID không được để trống."
                );

        this.title =
                validateTitle(title);

        this.slug =
                Objects.requireNonNull(
                        slug,
                        "Slug không được để trống."
                );

        this.description =
                validateDescription(description);

        this.sortOrder =
                validateSortOrder(sortOrder);

        this.status =
                Objects.requireNonNull(
                        status,
                        "Trạng thái tập không được để trống."
                );

        this.createdBy =
                Objects.requireNonNull(
                        createdBy,
                        "Người tạo tập không được để trống."
                );

        this.updatedBy =
                Objects.requireNonNull(
                        updatedBy,
                        "Người cập nhật tập không được để trống."
                );

        this.publishedBy =
                publishedBy;

        this.archivedBy =
                archivedBy;

        this.createdAt =
                Objects.requireNonNull(
                        createdAt,
                        "Thời gian tạo tập không được để trống."
                );

        this.updatedAt =
                Objects.requireNonNull(
                        updatedAt,
                        "Thời gian cập nhật tập không được để trống."
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

        validateLifecycleAuditConsistency();
    }

    /**
     * Tạo một tập mới ở trạng thái DRAFT.
     */
    public static Volume createDraft(
            UUID id,
            String title,
            Slug slug,
            String description,
            int sortOrder,
            UUID createdBy,
            Instant createdAt
    ) {
        Objects.requireNonNull(
                createdAt,
                "Thời gian tạo tập không được để trống."
        );

        return new Volume(
                id,
                title,
                slug,
                description,
                sortOrder,
                VolumeStatus.DRAFT,
                createdBy,
                createdBy,
                null,
                null,
                createdAt,
                createdAt,
                null,
                null,
                1L
        );
    }

    /**
     * Khôi phục Aggregate từ dữ liệu persistence.
     *
     * Method này không tạo thêm thay đổi nghiệp vụ.
     */
    public static Volume rehydrate(
            UUID id,
            String title,
            Slug slug,
            String description,
            int sortOrder,
            VolumeStatus status,
            UUID createdBy,
            UUID updatedBy,
            UUID publishedBy,
            UUID archivedBy,
            Instant createdAt,
            Instant updatedAt,
            Instant publishedAt,
            Instant archivedAt,
            long aggregateVersion
    ) {
        return new Volume(
                id,
                title,
                slug,
                description,
                sortOrder,
                status,
                createdBy,
                updatedBy,
                publishedBy,
                archivedBy,
                createdAt,
                updatedAt,
                publishedAt,
                archivedAt,
                aggregateVersion
        );
    }

    /**
     * Cập nhật thông tin của bản nháp.
     *
     * Tiêu đề, slug và mô tả chỉ được thay đổi
     * khi tập vẫn còn ở trạng thái DRAFT.
     */
    public void updateDraft(
            String title,
            Slug slug,
            String description,
            UUID updatedBy,
            Instant updatedAt
    ) {
        requireStatus(
                VolumeStatus.DRAFT,
                "Chỉ được cập nhật nội dung khi tập còn là bản nháp."
        );

        String normalizedTitle =
                validateTitle(title);

        Slug normalizedSlug =
                Objects.requireNonNull(
                        slug,
                        "Slug không được để trống."
                );

        String normalizedDescription =
                validateDescription(description);

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

        this.title =
                normalizedTitle;

        this.slug =
                normalizedSlug;

        this.description =
                normalizedDescription;

        this.updatedBy =
                normalizedUpdatedBy;

        this.updatedAt =
                normalizedUpdatedAt;

        increaseVersion();
    }

    /**
     * Thay đổi thứ tự sắp xếp của tập.
     *
     * Được phép khi tập còn DRAFT hoặc PUBLISHED.
     */
    public void reorder(
            int sortOrder,
            UUID updatedBy,
            Instant updatedAt
    ) {
        if (status == VolumeStatus.ARCHIVED) {
            throw new IllegalStateException(
                    "Không thể sắp xếp lại tập đã lưu trữ."
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

        increaseVersion();
    }

    /**
     * Xuất bản tập.
     */
    public void publish(
            UUID publishedBy,
            Instant publishedAt
    ) {
        requireStatus(
                VolumeStatus.DRAFT,
                "Chỉ tập ở trạng thái DRAFT mới được xuất bản."
        );

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
                VolumeStatus.PUBLISHED;

        this.publishedBy =
                normalizedPublishedBy;

        this.publishedAt =
                normalizedPublishedAt;

        this.updatedBy =
                normalizedPublishedBy;

        this.updatedAt =
                normalizedPublishedAt;

        increaseVersion();
    }

    /**
     * Lưu trữ tập.
     *
     * Nếu tập đã xuất bản trước đó, metadata publish được giữ nguyên.
     */
    public void archive(
            UUID archivedBy,
            Instant archivedAt
    ) {
        if (status == VolumeStatus.ARCHIVED) {
            throw new IllegalStateException(
                    "Tập đã ở trạng thái ARCHIVED."
            );
        }

        UUID normalizedArchivedBy =
                Objects.requireNonNull(
                        archivedBy,
                        "Người lưu trữ tập không được để trống."
                );

        Instant normalizedArchivedAt =
                Objects.requireNonNull(
                        archivedAt,
                        "Thời gian lưu trữ tập không được để trống."
                );

        this.status =
                VolumeStatus.ARCHIVED;

        this.archivedBy =
                normalizedArchivedBy;

        this.archivedAt =
                normalizedArchivedAt;

        this.updatedBy =
                normalizedArchivedBy;

        this.updatedAt =
                normalizedArchivedAt;

        increaseVersion();
    }

    private void requireStatus(
            VolumeStatus requiredStatus,
            String errorMessage
    ) {
        if (status != requiredStatus) {
            throw new IllegalStateException(
                    errorMessage
            );
        }
    }

    private void increaseVersion() {
        this.aggregateVersion++;
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
                    "Thông tin xuất bản của tập không nhất quán."
            );
        }

        switch (status) {
            case DRAFT -> {
                if (hasPublishedBy
                        || hasPublishedAt) {
                    throw new IllegalArgumentException(
                            "Tập DRAFT không được có thông tin xuất bản."
                    );
                }

                if (archivedBy != null
                        || archivedAt != null) {
                    throw new IllegalArgumentException(
                            "Tập DRAFT không được có thông tin lưu trữ."
                    );
                }
            }
            case PUBLISHED -> {
                if (!hasPublishedBy
                        || !hasPublishedAt) {
                    throw new IllegalArgumentException(
                            "Tập PUBLISHED phải có đầy đủ thông tin xuất bản."
                    );
                }

                if (archivedBy != null
                        || archivedAt != null) {
                    throw new IllegalArgumentException(
                            "Tập PUBLISHED không được có thông tin lưu trữ."
                    );
                }
            }
            case ARCHIVED -> {
                if (archivedBy == null
                        || archivedAt == null) {
                    throw new IllegalArgumentException(
                            "Tập ARCHIVED phải có đầy đủ thông tin lưu trữ."
                    );
                }
            }
        }
    }

    private static String validateTitle(
            String title
    ) {
        if (title == null) {
            throw new IllegalArgumentException(
                    "Tiêu đề tập không được để trống."
            );
        }

        String normalizedTitle =
                title.trim();

        if (normalizedTitle.length()
                < MIN_TITLE_LENGTH) {

            throw new IllegalArgumentException(
                    "Tiêu đề tập phải có ít nhất "
                            + MIN_TITLE_LENGTH
                            + " ký tự."
            );
        }

        if (normalizedTitle.length()
                > MAX_TITLE_LENGTH) {

            throw new IllegalArgumentException(
                    "Tiêu đề tập không được vượt quá "
                            + MAX_TITLE_LENGTH
                            + " ký tự."
            );
        }

        return normalizedTitle;
    }

    private static String validateDescription(
            String description
    ) {
        if (description == null) {
            return "";
        }

        String normalizedDescription =
                description.trim();

        if (normalizedDescription.length()
                > MAX_DESCRIPTION_LENGTH) {

            throw new IllegalArgumentException(
                    "Mô tả không được vượt quá "
                            + MAX_DESCRIPTION_LENGTH
                            + " ký tự."
            );
        }

        return normalizedDescription;
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

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public Slug getSlug() {
        return slug;
    }

    public String getDescription() {
        return description;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public VolumeStatus getStatus() {
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
}
