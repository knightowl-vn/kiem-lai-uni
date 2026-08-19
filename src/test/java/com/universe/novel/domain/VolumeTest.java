package com.universe.novel.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VolumeTest {

    private static final UUID VOLUME_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final UUID OTHER_ADMIN_ID =
            UUID.fromString(
                    "33333333-3333-3333-3333-333333333333"
            );

    private static final Instant CREATED_AT =
            Instant.parse(
                    "2026-08-06T02:00:00Z"
            );

    private static final Instant UPDATED_AT =
            Instant.parse(
                    "2026-08-06T03:00:00Z"
            );

    private static final Slug DEFAULT_SLUG =
            new Slug(
                    "quyen-mot"
            );

    @Test
    @DisplayName(
            "Tạo tập mới ở trạng thái DRAFT"
    )
    void shouldCreateVolumeAsDraft() {
        Volume volume =
                createDraft();

        assertThat(volume.getId())
                .isEqualTo(VOLUME_ID);

        assertThat(volume.getTitle())
                .isEqualTo(
                        "Quyển Một"
                );

        assertThat(volume.getSlug().value())
                .isEqualTo(
                        "quyen-mot"
                );

        assertThat(volume.getDescription())
                .isEqualTo(
                        "Mở đầu hành trình"
                );

        assertThat(volume.getSortOrder())
                .isEqualTo(1);

        assertThat(volume.getStatus())
                .isEqualTo(
                        VolumeStatus.DRAFT
                );

        assertThat(volume.getCreatedBy())
                .isEqualTo(ADMIN_ID);

        assertThat(volume.getUpdatedBy())
                .isEqualTo(ADMIN_ID);

        assertThat(volume.getCreatedAt())
                .isEqualTo(CREATED_AT);

        assertThat(volume.getUpdatedAt())
                .isEqualTo(CREATED_AT);

        assertThat(volume.getPublishedBy())
                .isNull();

        assertThat(volume.getPublishedAt())
                .isNull();

        assertThat(volume.getArchivedBy())
                .isNull();

        assertThat(volume.getArchivedAt())
                .isNull();

        assertThat(volume.getAggregateVersion())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName(
            "Chuẩn hóa tiêu đề và mô tả khi tạo bản nháp"
    )
    void shouldTrimTitleAndDescriptionOnCreate() {
        Volume volume =
                Volume.createDraft(
                        VOLUME_ID,
                        "  Quyển Một  ",
                        DEFAULT_SLUG,
                        "  Mở đầu hành trình  ",
                        1,
                        ADMIN_ID,
                        CREATED_AT
                );

        assertThat(volume.getTitle())
                .isEqualTo(
                        "Quyển Một"
                );

        assertThat(volume.getDescription())
                .isEqualTo(
                        "Mở đầu hành trình"
                );
    }

    @Test
    @DisplayName(
            "Null description trở thành chuỗi rỗng"
    )
    void shouldTreatNullDescriptionAsEmpty() {
        Volume volume =
                Volume.createDraft(
                        VOLUME_ID,
                        "Quyển Một",
                        DEFAULT_SLUG,
                        null,
                        1,
                        ADMIN_ID,
                        CREATED_AT
                );

        assertThat(volume.getDescription())
                .isEmpty();
    }

    @Test
    @DisplayName(
            "Từ chối Volume ID null"
    )
    void shouldRejectNullId() {
        assertThatThrownBy(() ->
                Volume.createDraft(
                        null,
                        "Quyển Một",
                        DEFAULT_SLUG,
                        "Mô tả",
                        1,
                        ADMIN_ID,
                        CREATED_AT
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "Volume ID không được để trống."
                );
    }

    @Test
    @DisplayName(
            "Từ chối tiêu đề rỗng"
    )
    void shouldRejectBlankTitle() {
        assertThatThrownBy(() ->
                Volume.createDraft(
                        VOLUME_ID,
                        "   ",
                        DEFAULT_SLUG,
                        "Mô tả",
                        1,
                        ADMIN_ID,
                        CREATED_AT
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "Tiêu đề tập"
                );
    }

    @Test
    @DisplayName(
            "Từ chối tiêu đề ngắn hơn 2 ký tự"
    )
    void shouldRejectTitleShorterThanMinimum() {
        assertThatThrownBy(() ->
                Volume.createDraft(
                        VOLUME_ID,
                        "A",
                        DEFAULT_SLUG,
                        "Mô tả",
                        1,
                        ADMIN_ID,
                        CREATED_AT
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "ít nhất 2"
                );
    }

    @Test
    @DisplayName(
            "Từ chối tiêu đề dài hơn 200 ký tự"
    )
    void shouldRejectTitleLongerThanMaximum() {
        String tooLong =
                "A".repeat(201);

        assertThatThrownBy(() ->
                Volume.createDraft(
                        VOLUME_ID,
                        tooLong,
                        DEFAULT_SLUG,
                        "Mô tả",
                        1,
                        ADMIN_ID,
                        CREATED_AT
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "200"
                );
    }

    @Test
    @DisplayName(
            "Từ chối slug null"
    )
    void shouldRejectNullSlug() {
        assertThatThrownBy(() ->
                Volume.createDraft(
                        VOLUME_ID,
                        "Quyển Một",
                        null,
                        "Mô tả",
                        1,
                        ADMIN_ID,
                        CREATED_AT
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "Slug không được để trống."
                );
    }

    @Test
    @DisplayName(
            "Từ chối sortOrder nhỏ hơn hoặc bằng 0"
    )
    void shouldRejectNonPositiveSortOrder() {
        assertThatThrownBy(() ->
                Volume.createDraft(
                        VOLUME_ID,
                        "Quyển Một",
                        DEFAULT_SLUG,
                        "Mô tả",
                        0,
                        ADMIN_ID,
                        CREATED_AT
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Thứ tự sắp xếp phải lớn hơn hoặc bằng 1."
                );
    }

    @Test
    @DisplayName(
            "Từ chối mô tả dài hơn 1000 ký tự"
    )
    void shouldRejectDescriptionLongerThanMaximum() {
        String tooLong =
                "A".repeat(1001);

        assertThatThrownBy(() ->
                Volume.createDraft(
                        VOLUME_ID,
                        "Quyển Một",
                        DEFAULT_SLUG,
                        tooLong,
                        1,
                        ADMIN_ID,
                        CREATED_AT
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "1000"
                );
    }

    @Test
    @DisplayName(
            "Từ chối người tạo null"
    )
    void shouldRejectNullCreator() {
        assertThatThrownBy(() ->
                Volume.createDraft(
                        VOLUME_ID,
                        "Quyển Một",
                        DEFAULT_SLUG,
                        "Mô tả",
                        1,
                        null,
                        CREATED_AT
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "Người tạo tập không được để trống."
                );
    }

    @Test
    @DisplayName(
            "Từ chối thời gian tạo null"
    )
    void shouldRejectNullCreatedAt() {
        assertThatThrownBy(() ->
                Volume.createDraft(
                        VOLUME_ID,
                        "Quyển Một",
                        DEFAULT_SLUG,
                        "Mô tả",
                        1,
                        ADMIN_ID,
                        null
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "Thời gian tạo tập không được để trống."
                );
    }

    @Test
    @DisplayName(
            "Cập nhật bản nháp thành công"
    )
    void shouldUpdateDraft() {
        Volume volume =
                createDraft();

        Slug slugBefore =
                volume.getSlug();

        volume.updateDraft(
                "Quyển Một Mới",
                "Mô tả đã cập nhật",
                OTHER_ADMIN_ID,
                UPDATED_AT
        );

        assertThat(volume.getTitle())
                .isEqualTo(
                        "Quyển Một Mới"
                );

        assertThat(volume.getSlug())
                .isEqualTo(slugBefore);

        assertThat(volume.getSortOrder())
                .isEqualTo(1);

        assertThat(volume.getDescription())
                .isEqualTo(
                        "Mô tả đã cập nhật"
                );

        assertThat(volume.getUpdatedBy())
                .isEqualTo(OTHER_ADMIN_ID);

        assertThat(volume.getUpdatedAt())
                .isEqualTo(UPDATED_AT);

        assertThat(volume.getAggregateVersion())
                .isEqualTo(2L);
    }

    @Test
    @DisplayName(
            "Không mutate khi cập nhật bản nháp với cùng tiêu đề và mô tả"
    )
    void shouldNoOpUpdateDraftWhenTitleAndDescriptionUnchanged() {
        Volume volume =
                createDraft();

        UUID updatedByBefore =
                volume.getUpdatedBy();

        Instant updatedAtBefore =
                volume.getUpdatedAt();

        long versionBefore =
                volume.getAggregateVersion();

        volume.updateDraft(
                "Quyển Một",
                "Mở đầu hành trình",
                OTHER_ADMIN_ID,
                UPDATED_AT
        );

        assertThat(volume.getTitle())
                .isEqualTo(
                        "Quyển Một"
                );

        assertThat(volume.getDescription())
                .isEqualTo(
                        "Mở đầu hành trình"
                );

        assertThat(volume.getUpdatedBy())
                .isEqualTo(updatedByBefore);

        assertThat(volume.getUpdatedAt())
                .isEqualTo(updatedAtBefore);

        assertThat(volume.getAggregateVersion())
                .isEqualTo(versionBefore);
    }

    @Test
    @DisplayName(
            "Không mutate khi tiêu đề và mô tả chỉ khác khoảng trắng"
    )
    void shouldNoOpUpdateDraftWhenValuesDifferOnlyByWhitespace() {
        Volume volume =
                createDraft();

        UUID updatedByBefore =
                volume.getUpdatedBy();

        Instant updatedAtBefore =
                volume.getUpdatedAt();

        long versionBefore =
                volume.getAggregateVersion();

        volume.updateDraft(
                "  Quyển Một  ",
                "  Mở đầu hành trình  ",
                OTHER_ADMIN_ID,
                UPDATED_AT
        );

        assertThat(volume.getTitle())
                .isEqualTo(
                        "Quyển Một"
                );

        assertThat(volume.getDescription())
                .isEqualTo(
                        "Mở đầu hành trình"
                );

        assertThat(volume.getUpdatedBy())
                .isEqualTo(updatedByBefore);

        assertThat(volume.getUpdatedAt())
                .isEqualTo(updatedAtBefore);

        assertThat(volume.getAggregateVersion())
                .isEqualTo(versionBefore);
    }

    @Test
    @DisplayName(
            "Không mutate khi mô tả null tương đương mô tả rỗng"
    )
    void shouldNoOpUpdateDraftWhenNullDescriptionMatchesEmpty() {
        Volume volume =
                Volume.createDraft(
                        VOLUME_ID,
                        "Quyển Một",
                        DEFAULT_SLUG,
                        "",
                        1,
                        ADMIN_ID,
                        CREATED_AT
                );

        UUID updatedByBefore =
                volume.getUpdatedBy();

        Instant updatedAtBefore =
                volume.getUpdatedAt();

        long versionBefore =
                volume.getAggregateVersion();

        volume.updateDraft(
                "Quyển Một",
                null,
                OTHER_ADMIN_ID,
                UPDATED_AT
        );

        assertThat(volume.getDescription())
                .isEqualTo("");

        assertThat(volume.getUpdatedBy())
                .isEqualTo(updatedByBefore);

        assertThat(volume.getUpdatedAt())
                .isEqualTo(updatedAtBefore);

        assertThat(volume.getAggregateVersion())
                .isEqualTo(versionBefore);
    }

    @Test
    @DisplayName(
            "Từ chối cập nhật bản nháp khi đã PUBLISHED"
    )
    void shouldRejectUpdateDraftWhenPublished() {
        Volume volume =
                createDraft();

        volume.publish(
                ADMIN_ID,
                UPDATED_AT
        );

        long versionBefore =
                volume.getAggregateVersion();

        String titleBefore =
                volume.getTitle();

        assertThatThrownBy(() ->
                volume.updateDraft(
                        "Tiêu đề mới",
                        "Mô tả mới",
                        OTHER_ADMIN_ID,
                        UPDATED_AT.plusSeconds(60)
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Chỉ được cập nhật nội dung khi tập còn là bản nháp."
                );

        assertThat(volume.getTitle())
                .isEqualTo(titleBefore);

        assertThat(volume.getAggregateVersion())
                .isEqualTo(versionBefore);
    }

    @Test
    @DisplayName(
            "Từ chối cập nhật bản nháp khi đã ARCHIVED"
    )
    void shouldRejectUpdateDraftWhenArchived() {
        Volume volume =
                createDraft();

        volume.archive(
                ADMIN_ID,
                UPDATED_AT
        );

        long versionBefore =
                volume.getAggregateVersion();

        assertThatThrownBy(() ->
                volume.updateDraft(
                        "Tiêu đề mới",
                        "Mô tả mới",
                        OTHER_ADMIN_ID,
                        UPDATED_AT.plusSeconds(60)
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                );

        assertThat(volume.getAggregateVersion())
                .isEqualTo(versionBefore);
    }

    @Test
    @DisplayName(
            "Cập nhật bản nháp không hợp lệ không làm thay đổi trạng thái"
    )
    void shouldNotMutateWhenUpdateDraftIsInvalid() {
        Volume volume =
                createDraft();

        long versionBefore =
                volume.getAggregateVersion();

        String titleBefore =
                volume.getTitle();

        Slug slugBefore =
                volume.getSlug();

        String descriptionBefore =
                volume.getDescription();

        assertThatThrownBy(() ->
                volume.updateDraft(
                        " ",
                        "Mô tả mới",
                        OTHER_ADMIN_ID,
                        UPDATED_AT
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                );

        assertThat(volume.getTitle())
                .isEqualTo(titleBefore);

        assertThat(volume.getSlug())
                .isEqualTo(slugBefore);

        assertThat(volume.getDescription())
                .isEqualTo(descriptionBefore);

        assertThat(volume.getAggregateVersion())
                .isEqualTo(versionBefore);
    }

    @Test
    @DisplayName(
            "Xuất bản tập DRAFT thành công"
    )
    void shouldPublishDraftVolume() {
        Volume volume =
                createDraft();

        volume.publish(
                OTHER_ADMIN_ID,
                UPDATED_AT
        );

        assertThat(volume.getStatus())
                .isEqualTo(
                        VolumeStatus.PUBLISHED
                );

        assertThat(volume.getPublishedBy())
                .isEqualTo(OTHER_ADMIN_ID);

        assertThat(volume.getPublishedAt())
                .isEqualTo(UPDATED_AT);

        assertThat(volume.getUpdatedBy())
                .isEqualTo(OTHER_ADMIN_ID);

        assertThat(volume.getUpdatedAt())
                .isEqualTo(UPDATED_AT);

        assertThat(volume.getAggregateVersion())
                .isEqualTo(2L);

        assertThat(volume.getSlug())
                .isEqualTo(DEFAULT_SLUG);

        assertThat(volume.getSortOrder())
                .isEqualTo(1);
    }

    @Test
    @DisplayName(
            "Cho phép xuất bản tập có mô tả rỗng"
    )
    void shouldAllowPublishingVolumeWithBlankDescription() {
        Volume volume =
                Volume.createDraft(
                        VOLUME_ID,
                        "Quyển Một",
                        DEFAULT_SLUG,
                        null,
                        1,
                        ADMIN_ID,
                        CREATED_AT
                );

        volume.publish(
                ADMIN_ID,
                UPDATED_AT
        );

        assertThat(volume.getStatus())
                .isEqualTo(
                        VolumeStatus.PUBLISHED
                );

        assertThat(volume.getDescription())
                .isEmpty();
    }

    @Test
    @DisplayName(
            "Từ chối xuất bản tập đã PUBLISHED"
    )
    void shouldRejectPublishingPublishedVolume() {
        Volume volume =
                createDraft();

        volume.publish(
                ADMIN_ID,
                UPDATED_AT
        );

        long versionBefore =
                volume.getAggregateVersion();

        assertThatThrownBy(() ->
                volume.publish(
                        OTHER_ADMIN_ID,
                        UPDATED_AT.plusSeconds(60)
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Chỉ tập ở trạng thái DRAFT mới được xuất bản."
                );

        assertThat(volume.getAggregateVersion())
                .isEqualTo(versionBefore);
    }

    @Test
    @DisplayName(
            "Từ chối xuất bản tập đã ARCHIVED"
    )
    void shouldRejectPublishingArchivedVolume() {
        Volume volume =
                createDraft();

        volume.archive(
                ADMIN_ID,
                UPDATED_AT
        );

        long versionBefore =
                volume.getAggregateVersion();

        assertThatThrownBy(() ->
                volume.publish(
                        OTHER_ADMIN_ID,
                        UPDATED_AT.plusSeconds(60)
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                );

        assertThat(volume.getAggregateVersion())
                .isEqualTo(versionBefore);
    }

    @Test
    @DisplayName(
            "Lưu trữ tập DRAFT thành công"
    )
    void shouldArchiveDraftVolume() {
        Volume volume =
                createDraft();

        volume.archive(
                OTHER_ADMIN_ID,
                UPDATED_AT
        );

        assertThat(volume.getStatus())
                .isEqualTo(
                        VolumeStatus.ARCHIVED
                );

        assertThat(volume.getArchivedBy())
                .isEqualTo(OTHER_ADMIN_ID);

        assertThat(volume.getArchivedAt())
                .isEqualTo(UPDATED_AT);

        assertThat(volume.getUpdatedBy())
                .isEqualTo(OTHER_ADMIN_ID);

        assertThat(volume.getUpdatedAt())
                .isEqualTo(UPDATED_AT);

        assertThat(volume.getAggregateVersion())
                .isEqualTo(2L);

        assertThat(volume.getSlug())
                .isEqualTo(DEFAULT_SLUG);

        assertThat(volume.getSortOrder())
                .isEqualTo(1);
    }

    @Test
    @DisplayName(
            "Lưu trữ tập PUBLISHED và giữ metadata xuất bản"
    )
    void shouldArchivePublishedVolumeAndPreservePublishAudit() {
        Volume volume =
                createDraft();

        volume.publish(
                ADMIN_ID,
                UPDATED_AT
        );

        Instant archivedAt =
                UPDATED_AT.plusSeconds(120);

        volume.archive(
                OTHER_ADMIN_ID,
                archivedAt
        );

        assertThat(volume.getStatus())
                .isEqualTo(
                        VolumeStatus.ARCHIVED
                );

        assertThat(volume.getArchivedBy())
                .isEqualTo(OTHER_ADMIN_ID);

        assertThat(volume.getArchivedAt())
                .isEqualTo(archivedAt);

        assertThat(volume.getPublishedBy())
                .isEqualTo(ADMIN_ID);

        assertThat(volume.getPublishedAt())
                .isEqualTo(UPDATED_AT);

        assertThat(volume.getUpdatedBy())
                .isEqualTo(OTHER_ADMIN_ID);

        assertThat(volume.getUpdatedAt())
                .isEqualTo(archivedAt);

        assertThat(volume.getAggregateVersion())
                .isEqualTo(3L);

        assertThat(volume.getSlug())
                .isEqualTo(DEFAULT_SLUG);

        assertThat(volume.getSortOrder())
                .isEqualTo(1);
    }

    @Test
    @DisplayName(
            "Từ chối lưu trữ lại tập đã ARCHIVED"
    )
    void shouldRejectArchivingAlreadyArchivedVolume() {
        Volume volume =
                createDraft();

        volume.archive(
                ADMIN_ID,
                UPDATED_AT
        );

        long versionBefore =
                volume.getAggregateVersion();

        UUID archivedByBefore =
                volume.getArchivedBy();

        Instant archivedAtBefore =
                volume.getArchivedAt();

        assertThatThrownBy(() ->
                volume.archive(
                        OTHER_ADMIN_ID,
                        UPDATED_AT.plusSeconds(60)
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Tập đã ở trạng thái ARCHIVED."
                );

        assertThat(volume.getArchivedBy())
                .isEqualTo(archivedByBefore);

        assertThat(volume.getArchivedAt())
                .isEqualTo(archivedAtBefore);

        assertThat(volume.getAggregateVersion())
                .isEqualTo(versionBefore);
    }

    @Test
    @DisplayName(
            "Khôi phục tập ARCHIVED về DRAFT và xóa metadata lưu trữ/xuất bản"
    )
    void shouldRestoreArchivedVolumeToDraftAndClearMetadata() {
        Volume volume =
                createDraft();

        volume.publish(
                ADMIN_ID,
                UPDATED_AT
        );

        Instant archivedAt =
                UPDATED_AT.plusSeconds(60);

        volume.archive(
                OTHER_ADMIN_ID,
                archivedAt
        );

        Instant restoredAt =
                archivedAt.plusSeconds(30);

        volume.restoreToDraft(
                ADMIN_ID,
                restoredAt
        );

        assertThat(volume.getStatus())
                .isEqualTo(VolumeStatus.DRAFT);

        assertThat(volume.getArchivedBy())
                .isNull();

        assertThat(volume.getArchivedAt())
                .isNull();

        assertThat(volume.getPublishedBy())
                .isNull();

        assertThat(volume.getPublishedAt())
                .isNull();

        assertThat(volume.getUpdatedBy())
                .isEqualTo(ADMIN_ID);

        assertThat(volume.getUpdatedAt())
                .isEqualTo(restoredAt);

        assertThat(volume.getAggregateVersion())
                .isEqualTo(4L);

        assertThat(volume.getId())
                .isEqualTo(VOLUME_ID);

        assertThat(volume.getTitle())
                .isEqualTo("Quyển Một");

        assertThat(volume.getSlug())
                .isEqualTo(DEFAULT_SLUG);

        assertThat(volume.getSortOrder())
                .isEqualTo(1);

        assertThat(volume.getCreatedBy())
                .isEqualTo(ADMIN_ID);

        assertThat(volume.getCreatedAt())
                .isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName(
            "Từ chối khôi phục tập DRAFT mà không mutate"
    )
    void shouldRejectRestoreFromDraftWithoutMutation() {
        Volume volume =
                createDraft();

        long versionBefore =
                volume.getAggregateVersion();

        UUID updatedByBefore =
                volume.getUpdatedBy();

        Instant updatedAtBefore =
                volume.getUpdatedAt();

        assertThatThrownBy(() ->
                volume.restoreToDraft(
                        OTHER_ADMIN_ID,
                        UPDATED_AT
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Chỉ tập ở trạng thái ARCHIVED mới được khôi phục về bản nháp."
                );

        assertThat(volume.getStatus())
                .isEqualTo(VolumeStatus.DRAFT);

        assertThat(volume.getUpdatedBy())
                .isEqualTo(updatedByBefore);

        assertThat(volume.getUpdatedAt())
                .isEqualTo(updatedAtBefore);

        assertThat(volume.getAggregateVersion())
                .isEqualTo(versionBefore);
    }

    @Test
    @DisplayName(
            "Từ chối khôi phục tập PUBLISHED mà không mutate"
    )
    void shouldRejectRestoreFromPublishedWithoutMutation() {
        Volume volume =
                createDraft();

        volume.publish(
                ADMIN_ID,
                UPDATED_AT
        );

        long versionBefore =
                volume.getAggregateVersion();

        UUID publishedByBefore =
                volume.getPublishedBy();

        Instant publishedAtBefore =
                volume.getPublishedAt();

        assertThatThrownBy(() ->
                volume.restoreToDraft(
                        OTHER_ADMIN_ID,
                        UPDATED_AT.plusSeconds(30)
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Chỉ tập ở trạng thái ARCHIVED mới được khôi phục về bản nháp."
                );

        assertThat(volume.getStatus())
                .isEqualTo(VolumeStatus.PUBLISHED);

        assertThat(volume.getPublishedBy())
                .isEqualTo(publishedByBefore);

        assertThat(volume.getPublishedAt())
                .isEqualTo(publishedAtBefore);

        assertThat(volume.getAggregateVersion())
                .isEqualTo(versionBefore);
    }

    @Test
    @DisplayName(
            "Rehydrate khôi phục đúng trạng thái đã lưu"
    )
    void shouldRehydratePersistedStateWithoutIncrementingVersion() {
        Volume volume =
                Volume.rehydrate(
                        VOLUME_ID,
                        "Quyển Hai",
                        new Slug("quyen-hai"),
                        "Mô tả đã lưu",
                        2,
                        VolumeStatus.PUBLISHED,
                        ADMIN_ID,
                        OTHER_ADMIN_ID,
                        OTHER_ADMIN_ID,
                        null,
                        CREATED_AT,
                        UPDATED_AT,
                        UPDATED_AT,
                        null,
                        7L
                );

        assertThat(volume.getId())
                .isEqualTo(VOLUME_ID);

        assertThat(volume.getTitle())
                .isEqualTo(
                        "Quyển Hai"
                );

        assertThat(volume.getSlug().value())
                .isEqualTo(
                        "quyen-hai"
                );

        assertThat(volume.getDescription())
                .isEqualTo(
                        "Mô tả đã lưu"
                );

        assertThat(volume.getSortOrder())
                .isEqualTo(2);

        assertThat(volume.getStatus())
                .isEqualTo(
                        VolumeStatus.PUBLISHED
                );

        assertThat(volume.getCreatedBy())
                .isEqualTo(ADMIN_ID);

        assertThat(volume.getUpdatedBy())
                .isEqualTo(OTHER_ADMIN_ID);

        assertThat(volume.getPublishedBy())
                .isEqualTo(OTHER_ADMIN_ID);

        assertThat(volume.getArchivedBy())
                .isNull();

        assertThat(volume.getCreatedAt())
                .isEqualTo(CREATED_AT);

        assertThat(volume.getUpdatedAt())
                .isEqualTo(UPDATED_AT);

        assertThat(volume.getPublishedAt())
                .isEqualTo(UPDATED_AT);

        assertThat(volume.getArchivedAt())
                .isNull();

        assertThat(volume.getAggregateVersion())
                .isEqualTo(7L);
    }

    @Test
    @DisplayName(
            "Rehydrate từ chối updatedBy null"
    )
    void shouldRejectRehydrateWithNullUpdatedBy() {
        assertThatThrownBy(() ->
                Volume.rehydrate(
                        VOLUME_ID,
                        "Quyển Một",
                        DEFAULT_SLUG,
                        "Mở đầu hành trình",
                        1,
                        VolumeStatus.DRAFT,
                        ADMIN_ID,
                        null,
                        null,
                        null,
                        CREATED_AT,
                        UPDATED_AT,
                        null,
                        null,
                        1L
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "Người cập nhật tập không được để trống."
                );
    }

    @Test
    @DisplayName(
            "Rehydrate DRAFT từ chối metadata xuất bản"
    )
    void shouldRejectDraftRehydrateWithPublishAudit() {
        assertThatThrownBy(() ->
                rehydrate(
                        VolumeStatus.DRAFT,
                        ADMIN_ID,
                        UPDATED_AT,
                        null,
                        null,
                        1L
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Tập DRAFT không được có thông tin xuất bản."
                );
    }

    @Test
    @DisplayName(
            "Rehydrate DRAFT từ chối metadata lưu trữ"
    )
    void shouldRejectDraftRehydrateWithArchiveAudit() {
        assertThatThrownBy(() ->
                rehydrate(
                        VolumeStatus.DRAFT,
                        null,
                        null,
                        ADMIN_ID,
                        UPDATED_AT,
                        1L
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Tập DRAFT không được có thông tin lưu trữ."
                );
    }

    @Test
    @DisplayName(
            "Rehydrate PUBLISHED yêu cầu publishedBy"
    )
    void shouldRejectPublishedRehydrateWithoutPublishedBy() {
        assertThatThrownBy(() ->
                rehydrate(
                        VolumeStatus.PUBLISHED,
                        null,
                        UPDATED_AT,
                        null,
                        null,
                        2L
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Thông tin xuất bản của tập không nhất quán."
                );
    }

    @Test
    @DisplayName(
            "Rehydrate PUBLISHED yêu cầu publishedAt"
    )
    void shouldRejectPublishedRehydrateWithoutPublishedAt() {
        assertThatThrownBy(() ->
                rehydrate(
                        VolumeStatus.PUBLISHED,
                        ADMIN_ID,
                        null,
                        null,
                        null,
                        2L
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Thông tin xuất bản của tập không nhất quán."
                );
    }

    @Test
    @DisplayName(
            "Rehydrate PUBLISHED từ chối metadata lưu trữ"
    )
    void shouldRejectPublishedRehydrateWithArchiveAudit() {
        assertThatThrownBy(() ->
                rehydrate(
                        VolumeStatus.PUBLISHED,
                        ADMIN_ID,
                        UPDATED_AT,
                        OTHER_ADMIN_ID,
                        UPDATED_AT,
                        2L
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Tập PUBLISHED không được có thông tin lưu trữ."
                );
    }

    @Test
    @DisplayName(
            "Rehydrate ARCHIVED yêu cầu archivedBy"
    )
    void shouldRejectArchivedRehydrateWithoutArchivedBy() {
        assertThatThrownBy(() ->
                rehydrate(
                        VolumeStatus.ARCHIVED,
                        null,
                        null,
                        null,
                        UPDATED_AT,
                        2L
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Tập ARCHIVED phải có đầy đủ thông tin lưu trữ."
                );
    }

    @Test
    @DisplayName(
            "Rehydrate ARCHIVED yêu cầu archivedAt"
    )
    void shouldRejectArchivedRehydrateWithoutArchivedAt() {
        assertThatThrownBy(() ->
                rehydrate(
                        VolumeStatus.ARCHIVED,
                        null,
                        null,
                        ADMIN_ID,
                        null,
                        2L
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Tập ARCHIVED phải có đầy đủ thông tin lưu trữ."
                );
    }

    @Test
    @DisplayName(
            "Rehydrate ARCHIVED chấp nhận không có metadata xuất bản khi lưu trữ từ DRAFT"
    )
    void shouldAcceptArchivedRehydrateWithoutPublishAudit() {
        Volume volume =
                rehydrate(
                        VolumeStatus.ARCHIVED,
                        null,
                        null,
                        OTHER_ADMIN_ID,
                        UPDATED_AT,
                        2L
                );

        assertThat(volume.getStatus())
                .isEqualTo(
                        VolumeStatus.ARCHIVED
                );

        assertThat(volume.getPublishedBy())
                .isNull();

        assertThat(volume.getPublishedAt())
                .isNull();

        assertThat(volume.getArchivedBy())
                .isEqualTo(OTHER_ADMIN_ID);

        assertThat(volume.getArchivedAt())
                .isEqualTo(UPDATED_AT);
    }

    @Test
    @DisplayName(
            "Rehydrate ARCHIVED chấp nhận metadata xuất bản đầy đủ khi lưu trữ sau PUBLISHED"
    )
    void shouldAcceptArchivedRehydrateWithCompletePublishAudit() {
        Volume volume =
                rehydrate(
                        VolumeStatus.ARCHIVED,
                        ADMIN_ID,
                        UPDATED_AT,
                        OTHER_ADMIN_ID,
                        UPDATED_AT.plusSeconds(60),
                        3L
                );

        assertThat(volume.getStatus())
                .isEqualTo(
                        VolumeStatus.ARCHIVED
                );

        assertThat(volume.getPublishedBy())
                .isEqualTo(ADMIN_ID);

        assertThat(volume.getPublishedAt())
                .isEqualTo(UPDATED_AT);

        assertThat(volume.getArchivedBy())
                .isEqualTo(OTHER_ADMIN_ID);

        assertThat(volume.getArchivedAt())
                .isEqualTo(
                        UPDATED_AT.plusSeconds(60)
                );
    }

    @Test
    @DisplayName(
            "Rehydrate từ chối cặp publishedBy/publishedAt không khớp"
    )
    void shouldRejectRehydrateWithMismatchedPublishAuditPair() {
        assertThatThrownBy(() ->
                rehydrate(
                        VolumeStatus.ARCHIVED,
                        ADMIN_ID,
                        null,
                        OTHER_ADMIN_ID,
                        UPDATED_AT,
                        2L
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Thông tin xuất bản của tập không nhất quán."
                );
    }

    private Volume createDraft() {
        return Volume.createDraft(
                VOLUME_ID,
                "Quyển Một",
                DEFAULT_SLUG,
                "Mở đầu hành trình",
                1,
                ADMIN_ID,
                CREATED_AT
        );
    }

    private Volume rehydrate(
            VolumeStatus status,
            UUID publishedBy,
            Instant publishedAt,
            UUID archivedBy,
            Instant archivedAt,
            long aggregateVersion
    ) {
        return Volume.rehydrate(
                VOLUME_ID,
                "Quyển Một",
                DEFAULT_SLUG,
                "Mở đầu hành trình",
                1,
                status,
                ADMIN_ID,
                ADMIN_ID,
                publishedBy,
                archivedBy,
                CREATED_AT,
                UPDATED_AT,
                publishedAt,
                archivedAt,
                aggregateVersion
        );
    }
}
