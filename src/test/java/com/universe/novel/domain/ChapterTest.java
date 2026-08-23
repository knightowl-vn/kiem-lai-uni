package com.universe.novel.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChapterTest {

    private static final UUID CHAPTER_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            );

    private static final UUID VOLUME_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID OTHER_VOLUME_ID =
            UUID.fromString(
                    "44444444-4444-4444-4444-444444444444"
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
                    "chuong-mot"
            );

    private static final String DEFAULT_CONTENT =
            "Nội dung chi tiết của chương một.";

    @Test
    @DisplayName(
            "Tạo chương mới ở trạng thái DRAFT"
    )
    void shouldCreateChapterAsDraft() {
        Chapter chapter =
                createDraft();

        assertThat(chapter.getId())
                .isEqualTo(CHAPTER_ID);

        assertThat(chapter.getVolumeId())
                .isEqualTo(VOLUME_ID);

        assertThat(chapter.getChapterNumber())
                .isEqualTo(1);

        assertThat(chapter.getTitle())
                .isEqualTo(
                        "Chương Một"
                );

        assertThat(chapter.getSlug().value())
                .isEqualTo(
                        "chuong-mot"
                );

        assertThat(chapter.getSummary())
                .isEqualTo(
                        "Tóm tắt chương"
                );

        assertThat(chapter.getContent())
                .isEqualTo(DEFAULT_CONTENT);

        assertThat(chapter.getStatus())
                .isEqualTo(
                        ChapterStatus.DRAFT
                );

        assertThat(chapter.getCreatedBy())
                .isEqualTo(ADMIN_ID);

        assertThat(chapter.getUpdatedBy())
                .isEqualTo(ADMIN_ID);

        assertThat(chapter.getCreatedAt())
                .isEqualTo(CREATED_AT);

        assertThat(chapter.getUpdatedAt())
                .isEqualTo(CREATED_AT);

        assertThat(chapter.getPublishedBy())
                .isNull();

        assertThat(chapter.getPublishedAt())
                .isNull();

        assertThat(chapter.getArchivedBy())
                .isNull();

        assertThat(chapter.getArchivedAt())
                .isNull();

        assertThat(chapter.getAggregateVersion())
                .isEqualTo(1L);

        assertThat(chapter.getContentVersion())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName(
            "Chuẩn hóa title/summary/content khi tạo bản nháp"
    )
    void shouldTrimFieldsOnCreate() {
        Chapter chapter =
                Chapter.createDraft(                        CHAPTER_ID,
                        VOLUME_ID,
                        1,
                        "  Chương Một  ",
                        DEFAULT_SLUG,
                        "  Tóm tắt chương  ",
                        "  Nội dung chương  ",
                        ADMIN_ID,
                        CREATED_AT);

        assertThat(chapter.getTitle())
                .isEqualTo(
                        "Chương Một"
                );

        assertThat(chapter.getSummary())
                .isEqualTo(
                        "Tóm tắt chương"
                );

        assertThat(chapter.getContent())
                .isEqualTo(
                        "Nội dung chương"
                );
    }

    @Test
    @DisplayName(
            "Null summary và content trở thành chuỗi rỗng"
    )
    void shouldTreatNullSummaryAndContentAsEmpty() {
        Chapter chapter =
                Chapter.createDraft(                        CHAPTER_ID,
                        VOLUME_ID,
                        1,
                        "Chương Một",
                        DEFAULT_SLUG,
                        null,
                        null,
                        ADMIN_ID,
                        CREATED_AT);

        assertThat(chapter.getSummary())
                .isEmpty();

        assertThat(chapter.getContent())
                .isEmpty();
    }

    @Test
    @DisplayName(
            "Từ chối Chapter ID null"
    )
    void shouldRejectNullId() {
        assertThatThrownBy(() ->
                Chapter.createDraft(                        null,
                        VOLUME_ID,
                        1,
                        "Chương Một",
                        DEFAULT_SLUG,
                        "Tóm tắt",
                        DEFAULT_CONTENT,
                        ADMIN_ID,
                        CREATED_AT)
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "Chapter ID không được để trống."
                );
    }

    @Test
    @DisplayName(
            "Từ chối Volume ID null"
    )
    void shouldRejectNullVolumeId() {
        assertThatThrownBy(() ->
                Chapter.createDraft(                        CHAPTER_ID,
                        null,
                        1,
                        "Chương Một",
                        DEFAULT_SLUG,
                        "Tóm tắt",
                        DEFAULT_CONTENT,
                        ADMIN_ID,
                        CREATED_AT)
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
            "Từ chối chapterNumber nhỏ hơn 1"
    )
    void shouldRejectChapterNumberLessThanOne() {
        assertThatThrownBy(() ->
                Chapter.createDraft(                        CHAPTER_ID,
                        VOLUME_ID,
                        0,
                        "Chương Một",
                        DEFAULT_SLUG,
                        "Tóm tắt",
                        DEFAULT_CONTENT,
                        ADMIN_ID,
                        CREATED_AT)
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Số chương phải lớn hơn hoặc bằng 1."
                );
    }

    @Test
    @DisplayName(
            "Từ chối chapterNumber âm"
    )
    void shouldRejectNegativeChapterNumber() {
        assertThatThrownBy(() ->
                Chapter.createDraft(
                        CHAPTER_ID,
                        VOLUME_ID,
                        -1,
                        "Chương Một",
                        DEFAULT_SLUG,
                        "Tóm tắt",
                        DEFAULT_CONTENT,
                        ADMIN_ID,
                        CREATED_AT
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Số chương phải lớn hơn hoặc bằng 1."
                );
    }

    @Test
    @DisplayName(
            "Chấp nhận chapterNumber toàn cục có giá trị lớn"
    )
    void shouldAcceptLargeChapterNumbers() {
        Chapter chapter1500 =
                Chapter.createDraft(
                        CHAPTER_ID,
                        VOLUME_ID,
                        1500,
                        "Chương 1500",
                        new Slug(
                                "quyen-1-chuong-1500"
                        ),
                        "Tóm tắt",
                        DEFAULT_CONTENT,
                        ADMIN_ID,
                        CREATED_AT
                );

        assertThat(chapter1500.getChapterNumber())
                .isEqualTo(1500);

        Chapter chapter1600 =
                Chapter.createDraft(
                        UUID.fromString(
                                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
                        ),
                        VOLUME_ID,
                        1600,
                        "Chương 1600",
                        new Slug(
                                "quyen-1-chuong-1600"
                        ),
                        "Tóm tắt",
                        DEFAULT_CONTENT,
                        ADMIN_ID,
                        CREATED_AT
                );

        assertThat(chapter1600.getChapterNumber())
                .isEqualTo(1600);

        Chapter chapter1527 =
                Chapter.createDraft(
                        UUID.fromString(
                                "cccccccc-cccc-cccc-cccc-cccccccccccc"
                        ),
                        VOLUME_ID,
                        1527,
                        "Chương 1527",
                        new Slug(
                                "quyen-15-chuong-1527"
                        ),
                        "Tóm tắt",
                        DEFAULT_CONTENT,
                        ADMIN_ID,
                        CREATED_AT
                );

        assertThat(chapter1527.getChapterNumber())
                .isEqualTo(1527);
    }

    @Test
    @DisplayName(
            "Từ chối tiêu đề rỗng"
    )
    void shouldRejectBlankTitle() {
        assertThatThrownBy(() ->
                Chapter.createDraft(                        CHAPTER_ID,
                        VOLUME_ID,
                        1,
                        "   ",
                        DEFAULT_SLUG,
                        "Tóm tắt",
                        DEFAULT_CONTENT,
                        ADMIN_ID,
                        CREATED_AT)
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "Tiêu đề chương"
                );
    }

    @Test
    @DisplayName(
            "Từ chối tiêu đề ngắn hơn 2 ký tự"
    )
    void shouldRejectTitleShorterThanMinimum() {
        assertThatThrownBy(() ->
                Chapter.createDraft(                        CHAPTER_ID,
                        VOLUME_ID,
                        1,
                        "A",
                        DEFAULT_SLUG,
                        "Tóm tắt",
                        DEFAULT_CONTENT,
                        ADMIN_ID,
                        CREATED_AT)
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
            "Từ chối tiêu đề dài hơn 250 ký tự"
    )
    void shouldRejectTitleLongerThanMaximum() {
        String tooLong =
                "A".repeat(251);

        assertThatThrownBy(() ->
                Chapter.createDraft(                        CHAPTER_ID,
                        VOLUME_ID,
                        1,
                        tooLong,
                        DEFAULT_SLUG,
                        "Tóm tắt",
                        DEFAULT_CONTENT,
                        ADMIN_ID,
                        CREATED_AT)
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "250"
                );
    }

    @Test
    @DisplayName(
            "Từ chối slug null"
    )
    void shouldRejectNullSlug() {
        assertThatThrownBy(() ->
                Chapter.createDraft(                        CHAPTER_ID,
                        VOLUME_ID,
                        1,
                        "Chương Một",
                        null,
                        "Tóm tắt",
                        DEFAULT_CONTENT,
                        ADMIN_ID,
                        CREATED_AT)
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
            "Từ chối tóm tắt dài hơn 1000 ký tự"
    )
    void shouldRejectSummaryLongerThanMaximum() {
        String tooLong =
                "A".repeat(1001);

        assertThatThrownBy(() ->
                Chapter.createDraft(                        CHAPTER_ID,
                        VOLUME_ID,
                        1,
                        "Chương Một",
                        DEFAULT_SLUG,
                        tooLong,
                        DEFAULT_CONTENT,
                        ADMIN_ID,
                        CREATED_AT)
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
            "Từ chối nội dung dài hơn 500000 ký tự"
    )
    void shouldRejectContentLongerThanMaximum() {
        String tooLong =
                "A".repeat(500_001);

        assertThatThrownBy(() ->
                Chapter.createDraft(                        CHAPTER_ID,
                        VOLUME_ID,
                        1,
                        "Chương Một",
                        DEFAULT_SLUG,
                        "Tóm tắt",
                        tooLong,
                        ADMIN_ID,
                        CREATED_AT)
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "500000"
                );
    }

    @Test
    @DisplayName(
            "Từ chối người tạo null"
    )
    void shouldRejectNullCreator() {
        assertThatThrownBy(() ->
                Chapter.createDraft(                        CHAPTER_ID,
                        VOLUME_ID,
                        1,
                        "Chương Một",
                        DEFAULT_SLUG,
                        "Tóm tắt",
                        DEFAULT_CONTENT,
                        null,
                        CREATED_AT)
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "Người tạo chương không được để trống."
                );
    }

    @Test
    @DisplayName(
            "Từ chối thời gian tạo null"
    )
    void shouldRejectNullCreatedAt() {
        assertThatThrownBy(() ->
                Chapter.createDraft(                        CHAPTER_ID,
                        VOLUME_ID,
                        1,
                        "Chương Một",
                        DEFAULT_SLUG,
                        "Tóm tắt",
                        DEFAULT_CONTENT,
                        ADMIN_ID,
                        null)
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "Thời gian tạo chương không được để trống."
                );
    }

    @Test
    @DisplayName(
            "Cập nhật bản nháp thành công và tăng contentVersion khi đổi nội dung"
    )
    void shouldUpdateDraftAndIncreaseContentVersionWhenContentChanges() {
        Chapter chapter =
                createDraft();

        Slug newSlug =
                new Slug(
                        "chuong-mot-moi"
                );

        chapter.updateDraft(
                2,
                "Chương Một Mới",
                newSlug,
                "Tóm tắt mới",
                "Nội dung mới",
                OTHER_ADMIN_ID,
                UPDATED_AT
        );

        assertThat(chapter.getChapterNumber())
                .isEqualTo(2);

        assertThat(chapter.getTitle())
                .isEqualTo(
                        "Chương Một Mới"
                );

        assertThat(chapter.getSlug())
                .isEqualTo(newSlug);

        assertThat(chapter.getSummary())
                .isEqualTo(
                        "Tóm tắt mới"
                );

        assertThat(chapter.getContent())
                .isEqualTo(
                        "Nội dung mới"
                );

        assertThat(chapter.getUpdatedBy())
                .isEqualTo(OTHER_ADMIN_ID);

        assertThat(chapter.getUpdatedAt())
                .isEqualTo(UPDATED_AT);

        assertThat(chapter.getAggregateVersion())
                .isEqualTo(2L);

        assertThat(chapter.getContentVersion())
                .isEqualTo(2L);
    }

    @Test
    @DisplayName(
            "Chỉ đổi tiêu đề không tăng contentVersion"
    )
    void shouldNotIncreaseContentVersionForTitleOnlyUpdate() {
        Chapter chapter =
                createDraft();

        chapter.updateDraft(
                1,
                "Tiêu đề mới",
                DEFAULT_SLUG,
                "Tóm tắt chương",
                DEFAULT_CONTENT,
                OTHER_ADMIN_ID,
                UPDATED_AT
        );

        assertThat(chapter.getTitle())
                .isEqualTo(
                        "Tiêu đề mới"
                );

        assertThat(chapter.getAggregateVersion())
                .isEqualTo(2L);

        assertThat(chapter.getContentVersion())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName(
    	    "Đổi chapterNumber không làm tăng contentVersion khi content không đổi"
    	)
    	void shouldUpdateChapterNumberWithoutIncreasingContentVersion() {
        Chapter chapter =
                createDraft();

        chapter.updateDraft(
                1266,
                "Chương Một",
                new Slug(
                        "quyen-1-chuong-1266"
                ),
                "Tóm tắt chương",
                DEFAULT_CONTENT,
                OTHER_ADMIN_ID,
                UPDATED_AT
        );

        assertThat(chapter.getChapterNumber())
                .isEqualTo(1266);

        assertThat(chapter.getSlug().value())
                .isEqualTo(
                        "quyen-1-chuong-1266"
                );

        assertThat(chapter.getAggregateVersion())
                .isEqualTo(2L);

        assertThat(chapter.getContentVersion())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName(
            "Chỉ đổi tóm tắt không tăng contentVersion"
    )
    void shouldNotIncreaseContentVersionForSummaryOnlyUpdate() {
        Chapter chapter =
                createDraft();

        chapter.updateDraft(
                1,
                "Chương Một",
                DEFAULT_SLUG,
                "Tóm tắt mới",
                DEFAULT_CONTENT,
                OTHER_ADMIN_ID,
                UPDATED_AT
        );

        assertThat(chapter.getSummary())
                .isEqualTo(
                        "Tóm tắt mới"
                );

        assertThat(chapter.getAggregateVersion())
                .isEqualTo(2L);

        assertThat(chapter.getContentVersion())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName(
            "Nội dung giống nhau sau chuẩn hóa không tăng contentVersion"
    )
    void shouldNotIncreaseContentVersionWhenNormalizedContentUnchanged() {
        Chapter chapter =
                createDraft();

        chapter.updateDraft(
                1,
                "Chương Một",
                DEFAULT_SLUG,
                "Tóm tắt chương",
                "  " + DEFAULT_CONTENT + "  ",
                OTHER_ADMIN_ID,
                UPDATED_AT
        );

        assertThat(chapter.getContent())
                .isEqualTo(DEFAULT_CONTENT);

        assertThat(chapter.getUpdatedBy())
                .isEqualTo(ADMIN_ID);

        assertThat(chapter.getUpdatedAt())
                .isEqualTo(CREATED_AT);

        assertThat(chapter.getAggregateVersion())
                .isEqualTo(1L);

        assertThat(chapter.getContentVersion())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName(
            "Từ chối cập nhật bản nháp khi đã PUBLISHED"
    )
    void shouldRejectUpdateDraftWhenPublished() {
        Chapter chapter =
                createDraft();

        chapter.publish(
                ADMIN_ID,
                UPDATED_AT
        );

        long aggregateBefore =
                chapter.getAggregateVersion();

        long contentBefore =
                chapter.getContentVersion();

        String titleBefore =
                chapter.getTitle();

        assertThatThrownBy(() ->
                chapter.updateDraft(
                        3,
                        "Tiêu đề mới",
                        new Slug("tieu-de-moi"),
                        "Tóm tắt mới",
                        "Nội dung mới",
                        OTHER_ADMIN_ID,
                        UPDATED_AT.plusSeconds(60)
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Chỉ được cập nhật nội dung khi chương còn là bản nháp."
                );

        assertThat(chapter.getTitle())
                .isEqualTo(titleBefore);

        assertThat(chapter.getAggregateVersion())
                .isEqualTo(aggregateBefore);

        assertThat(chapter.getContentVersion())
                .isEqualTo(contentBefore);
    }

    @Test
    @DisplayName(
            "Từ chối cập nhật bản nháp khi đã ARCHIVED"
    )
    void shouldRejectUpdateDraftWhenArchived() {
        Chapter chapter =
                createDraft();

        chapter.archive(
                ADMIN_ID,
                UPDATED_AT
        );

        long aggregateBefore =
                chapter.getAggregateVersion();

        assertThatThrownBy(() ->
                chapter.updateDraft(
                        3,
                        "Tiêu đề mới",
                        new Slug("tieu-de-moi"),
                        "Tóm tắt mới",
                        "Nội dung mới",
                        OTHER_ADMIN_ID,
                        UPDATED_AT.plusSeconds(60)
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                );

        assertThat(chapter.getAggregateVersion())
                .isEqualTo(aggregateBefore);
    }

    @Test
    @DisplayName(
            "Cập nhật bản nháp không hợp lệ không làm thay đổi trạng thái"
    )
    void shouldNotMutateWhenUpdateDraftIsInvalid() {
        Chapter chapter =
                createDraft();

        long aggregateBefore =
                chapter.getAggregateVersion();

        long contentBefore =
                chapter.getContentVersion();

        String titleBefore =
                chapter.getTitle();

        assertThatThrownBy(() ->
                chapter.updateDraft(
                        1,
                        " ",
                        new Slug("chuong-moi"),
                        "Tóm tắt mới",
                        "Nội dung mới",
                        OTHER_ADMIN_ID,
                        UPDATED_AT
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                );

        assertThat(chapter.getTitle())
                .isEqualTo(titleBefore);

        assertThat(chapter.getAggregateVersion())
                .isEqualTo(aggregateBefore);

        assertThat(chapter.getContentVersion())
                .isEqualTo(contentBefore);
    }

    @Test
    @DisplayName(
            "Di chuyển chương DRAFT sang Volume khác"
    )
    void shouldMoveDraftChapterToAnotherVolume() {
        Chapter chapter =
                createDraft();

        chapter.moveToVolume(
                OTHER_VOLUME_ID,
                new Slug(
                        "quyen-2-chuong-1"
                ),
                OTHER_ADMIN_ID,
                UPDATED_AT
        );

        assertThat(chapter.getVolumeId())
                .isEqualTo(OTHER_VOLUME_ID);

        assertThat(chapter.getSlug().value())
                .isEqualTo(
                        "quyen-2-chuong-1"
                );

        assertThat(chapter.getUpdatedBy())
                .isEqualTo(OTHER_ADMIN_ID);

        assertThat(chapter.getUpdatedAt())
                .isEqualTo(UPDATED_AT);

        assertThat(chapter.getAggregateVersion())
                .isEqualTo(2L);

        assertThat(chapter.getContentVersion())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName(
    		"Di chuyển chương 1266 sang Volume khác giữ nguyên số chương"
    )
    void shouldKeepChapterNumberWhenMovingDraftChapter() {
        Chapter chapter =
                Chapter.createDraft(
                        CHAPTER_ID,
                        VOLUME_ID,
                        1266,
                        "Chương 1266",
                        new Slug(
                                "quyen-1-chuong-1266"
                        ),
                        "Tóm tắt chương",
                        DEFAULT_CONTENT,
                        ADMIN_ID,
                        CREATED_AT
                );

        chapter.moveToVolume(
                OTHER_VOLUME_ID,
                new Slug(
                        "quyen-13-chuong-1266"
                ),
                OTHER_ADMIN_ID,
                UPDATED_AT
        );

        assertThat(chapter.getVolumeId())
                .isEqualTo(OTHER_VOLUME_ID);

        assertThat(chapter.getChapterNumber())
                .isEqualTo(1266);

        assertThat(chapter.getSlug().value())
                .isEqualTo(
                        "quyen-13-chuong-1266"
                );

        assertThat(chapter.getAggregateVersion())
                .isEqualTo(2L);

        assertThat(chapter.getContentVersion())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName(
            "Từ chối di chuyển chương PUBLISHED"
    )
    void shouldRejectMoveWhenPublished() {
        Chapter chapter =
                createDraft();

        chapter.publish(
                ADMIN_ID,
                UPDATED_AT
        );

        long aggregateBefore =
                chapter.getAggregateVersion();

        UUID volumeBefore =
                chapter.getVolumeId();

        assertThatThrownBy(() ->
                chapter.moveToVolume(
                        OTHER_VOLUME_ID,
                        new Slug(
                                "quyen-2-chuong-1"
                        ),
                        OTHER_ADMIN_ID,
                        UPDATED_AT.plusSeconds(30)
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Chỉ được di chuyển chương khi còn là bản nháp."
                );

        assertThat(chapter.getVolumeId())
                .isEqualTo(volumeBefore);

        assertThat(chapter.getAggregateVersion())
                .isEqualTo(aggregateBefore);
    }

    @Test
    @DisplayName(
            "Từ chối di chuyển chương ARCHIVED"
    )
    void shouldRejectMoveWhenArchived() {
        Chapter chapter =
                createDraft();

        chapter.archive(
                ADMIN_ID,
                UPDATED_AT
        );

        long aggregateBefore =
                chapter.getAggregateVersion();

        assertThatThrownBy(() ->
                chapter.moveToVolume(
                        OTHER_VOLUME_ID,
                        new Slug(
                                "quyen-2-chuong-1"
                        ),
                        OTHER_ADMIN_ID,
                        UPDATED_AT.plusSeconds(30)
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                );

        assertThat(chapter.getAggregateVersion())
                .isEqualTo(aggregateBefore);
    }

    @Test
    @DisplayName(
            "Di chuyển không hợp lệ không làm thay đổi trạng thái"
    )
    void shouldNotMutateWhenMoveToVolumeIsInvalid() {
        Chapter chapter =
                createDraft();

        long aggregateBefore =
                chapter.getAggregateVersion();

        UUID volumeBefore =
                chapter.getVolumeId();

        int chapterNumberBefore =
                chapter.getChapterNumber();

        assertThatThrownBy(() ->
                chapter.moveToVolume(
                        OTHER_VOLUME_ID,
                        null,
                        OTHER_ADMIN_ID,
                        UPDATED_AT
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "Slug không được để trống."
                );

        assertThat(chapter.getVolumeId())
                .isEqualTo(volumeBefore);

        assertThat(chapter.getChapterNumber())
                 .isEqualTo(chapterNumberBefore);

        assertThat(chapter.getAggregateVersion())
                .isEqualTo(aggregateBefore);
    }

    @Test
    @DisplayName(
            "Xuất bản chương DRAFT có nội dung"
    )
    void shouldPublishDraftChapterWithContent() {
        Chapter chapter =
                createDraft();

        chapter.publish(
                OTHER_ADMIN_ID,
                UPDATED_AT
        );

        assertThat(chapter.getStatus())
                .isEqualTo(
                        ChapterStatus.PUBLISHED
                );

        assertThat(chapter.getPublishedBy())
                .isEqualTo(OTHER_ADMIN_ID);

        assertThat(chapter.getPublishedAt())
                .isEqualTo(UPDATED_AT);

        assertThat(chapter.getUpdatedBy())
                .isEqualTo(OTHER_ADMIN_ID);

        assertThat(chapter.getUpdatedAt())
                .isEqualTo(UPDATED_AT);

        assertThat(chapter.getAggregateVersion())
                .isEqualTo(2L);

        assertThat(chapter.getContentVersion())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName(
            "Từ chối xuất bản chương không có nội dung"
    )
    void shouldRejectPublishingChapterWithoutContent() {
        Chapter chapter =
                Chapter.createDraft(                        CHAPTER_ID,
                        VOLUME_ID,
                        1,
                        "Chương Một",
                        DEFAULT_SLUG,
                        "Tóm tắt",
                        null,
                        ADMIN_ID,
                        CREATED_AT);

        long aggregateBefore =
                chapter.getAggregateVersion();

        long contentBefore =
                chapter.getContentVersion();

        assertThatThrownBy(() ->
                chapter.publish(
                        ADMIN_ID,
                        UPDATED_AT
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Chương phải có nội dung trước khi xuất bản."
                );

        assertThat(chapter.getStatus())
                .isEqualTo(
                        ChapterStatus.DRAFT
                );

        assertThat(chapter.getAggregateVersion())
                .isEqualTo(aggregateBefore);

        assertThat(chapter.getContentVersion())
                .isEqualTo(contentBefore);
    }

    @Test
    @DisplayName(
            "Từ chối xuất bản chương đã PUBLISHED"
    )
    void shouldRejectPublishingPublishedChapter() {
        Chapter chapter =
                createDraft();

        chapter.publish(
                ADMIN_ID,
                UPDATED_AT
        );

        long aggregateBefore =
                chapter.getAggregateVersion();

        assertThatThrownBy(() ->
                chapter.publish(
                        OTHER_ADMIN_ID,
                        UPDATED_AT.plusSeconds(60)
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Chỉ chương ở trạng thái DRAFT mới được xuất bản."
                );

        assertThat(chapter.getAggregateVersion())
                .isEqualTo(aggregateBefore);
    }

    @Test
    @DisplayName(
            "Từ chối xuất bản chương đã ARCHIVED"
    )
    void shouldRejectPublishingArchivedChapter() {
        Chapter chapter =
                createDraft();

        chapter.archive(
                ADMIN_ID,
                UPDATED_AT
        );

        long aggregateBefore =
                chapter.getAggregateVersion();

        assertThatThrownBy(() ->
                chapter.publish(
                        OTHER_ADMIN_ID,
                        UPDATED_AT.plusSeconds(60)
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                );

        assertThat(chapter.getAggregateVersion())
                .isEqualTo(aggregateBefore);
    }

    @Test
    @DisplayName(
            "Lưu trữ chương DRAFT thành công"
    )
    void shouldArchiveDraftChapter() {
        Chapter chapter =
                createDraft();

        chapter.archive(
                OTHER_ADMIN_ID,
                UPDATED_AT
        );

        assertThat(chapter.getStatus())
                .isEqualTo(
                        ChapterStatus.ARCHIVED
                );

        assertThat(chapter.getArchivedBy())
                .isEqualTo(OTHER_ADMIN_ID);

        assertThat(chapter.getArchivedAt())
                .isEqualTo(UPDATED_AT);

        assertThat(chapter.getUpdatedBy())
                .isEqualTo(OTHER_ADMIN_ID);

        assertThat(chapter.getUpdatedAt())
                .isEqualTo(UPDATED_AT);

        assertThat(chapter.getAggregateVersion())
                .isEqualTo(2L);

        assertThat(chapter.getContentVersion())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName(
            "Lưu trữ chương PUBLISHED và giữ metadata xuất bản"
    )
    void shouldArchivePublishedChapterAndPreservePublishAudit() {
        Chapter chapter =
                createDraft();

        chapter.publish(
                ADMIN_ID,
                UPDATED_AT
        );

        Instant archivedAt =
                UPDATED_AT.plusSeconds(120);

        chapter.archive(
                OTHER_ADMIN_ID,
                archivedAt
        );

        assertThat(chapter.getStatus())
                .isEqualTo(
                        ChapterStatus.ARCHIVED
                );

        assertThat(chapter.getArchivedBy())
                .isEqualTo(OTHER_ADMIN_ID);

        assertThat(chapter.getArchivedAt())
                .isEqualTo(archivedAt);

        assertThat(chapter.getPublishedBy())
                .isEqualTo(ADMIN_ID);

        assertThat(chapter.getPublishedAt())
                .isEqualTo(UPDATED_AT);

        assertThat(chapter.getAggregateVersion())
                .isEqualTo(3L);

        assertThat(chapter.getContentVersion())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName(
            "Từ chối lưu trữ lại chương đã ARCHIVED"
    )
    void shouldRejectArchivingAlreadyArchivedChapter() {
        Chapter chapter =
                createDraft();

        chapter.archive(
                ADMIN_ID,
                UPDATED_AT
        );

        long aggregateBefore =
                chapter.getAggregateVersion();

        assertThatThrownBy(() ->
                chapter.archive(
                        OTHER_ADMIN_ID,
                        UPDATED_AT.plusSeconds(60)
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Chương đã ở trạng thái ARCHIVED."
                );

        assertThat(chapter.getAggregateVersion())
                .isEqualTo(aggregateBefore);
    }

    @Test
    @DisplayName(
            "Rehydrate khôi phục đúng trạng thái đã lưu"
    )
    void shouldRehydratePersistedStateWithoutChangingVersions() {
        Chapter chapter =
        		Chapter.rehydrate(
        		        CHAPTER_ID,
        		        VOLUME_ID,
        		        7,
        		        "Chương Bảy",
        		        new Slug("chuong-bay"),
        		        "Tóm tắt đã lưu",
        		        "Nội dung đã lưu",
        		        ChapterStatus.PUBLISHED,
        		        ADMIN_ID,
        		        OTHER_ADMIN_ID,
        		        OTHER_ADMIN_ID,
        		        null,
        		        CREATED_AT,
        		        UPDATED_AT,
        		        UPDATED_AT,
        		        null,
        		        9L,
        		        4L
        		);

        assertThat(chapter.getId())
                .isEqualTo(CHAPTER_ID);

        assertThat(chapter.getVolumeId())
                .isEqualTo(VOLUME_ID);

        assertThat(chapter.getChapterNumber())
                .isEqualTo(7);

        assertThat(chapter.getTitle())
                .isEqualTo(
                        "Chương Bảy"
                );

        assertThat(chapter.getSlug().value())
                .isEqualTo(
                        "chuong-bay"
                );

        assertThat(chapter.getSummary())
                .isEqualTo(
                        "Tóm tắt đã lưu"
                );

        assertThat(chapter.getContent())
                .isEqualTo(
                        "Nội dung đã lưu"
                );

        assertThat(chapter.getStatus())
                .isEqualTo(
                        ChapterStatus.PUBLISHED
                );

        assertThat(chapter.getCreatedBy())
                .isEqualTo(ADMIN_ID);

        assertThat(chapter.getUpdatedBy())
                .isEqualTo(OTHER_ADMIN_ID);

        assertThat(chapter.getPublishedBy())
                .isEqualTo(OTHER_ADMIN_ID);

        assertThat(chapter.getPublishedAt())
                .isEqualTo(UPDATED_AT);

        assertThat(chapter.getArchivedBy())
                .isNull();

        assertThat(chapter.getArchivedAt())
                .isNull();

        assertThat(chapter.getAggregateVersion())
                .isEqualTo(9L);

        assertThat(chapter.getContentVersion())
                .isEqualTo(4L);
    }

    @Test
    @DisplayName(
            "Rehydrate từ chối updatedBy null"
    )
    void shouldRejectRehydrateWithNullUpdatedBy() {
        assertThatThrownBy(() ->
                Chapter.rehydrate(
                        CHAPTER_ID,
                        VOLUME_ID,
                        1,
                        "Chương Một",
                        DEFAULT_SLUG,
                        "Tóm tắt",
                        DEFAULT_CONTENT,
                        ChapterStatus.DRAFT,
                        ADMIN_ID,
                        null,
                        null,
                        null,
                        CREATED_AT,
                        UPDATED_AT,
                        null,
                        null,
                        1L,
                        1L
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "Người cập nhật chương không được để trống."
                );
    }

    @Test
    @DisplayName(
            "Rehydrate DRAFT từ chối metadata xuất bản"
    )
    void shouldRejectDraftRehydrateWithPublishAudit() {
        assertThatThrownBy(() ->
                rehydrate(
                        ChapterStatus.DRAFT,
                        ADMIN_ID,
                        UPDATED_AT,
                        null,
                        null,
                        1L,
                        1L
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Chương DRAFT không được có thông tin xuất bản."
                );
    }

    @Test
    @DisplayName(
            "Rehydrate DRAFT từ chối metadata lưu trữ"
    )
    void shouldRejectDraftRehydrateWithArchiveAudit() {
        assertThatThrownBy(() ->
                rehydrate(
                        ChapterStatus.DRAFT,
                        null,
                        null,
                        ADMIN_ID,
                        UPDATED_AT,
                        1L,
                        1L
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Chương DRAFT không được có thông tin lưu trữ."
                );
    }

    @Test
    @DisplayName(
            "Rehydrate PUBLISHED yêu cầu metadata xuất bản đầy đủ"
    )
    void shouldRejectPublishedRehydrateWithoutPublishAudit() {
        assertThatThrownBy(() ->
                rehydrate(
                        ChapterStatus.PUBLISHED,
                        null,
                        null,
                        null,
                        null,
                        2L,
                        1L
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Chương PUBLISHED phải có đầy đủ thông tin xuất bản."
                );
    }

    @Test
    @DisplayName(
            "Rehydrate PUBLISHED từ chối metadata lưu trữ"
    )
    void shouldRejectPublishedRehydrateWithArchiveAudit() {
        assertThatThrownBy(() ->
                rehydrate(
                        ChapterStatus.PUBLISHED,
                        ADMIN_ID,
                        UPDATED_AT,
                        OTHER_ADMIN_ID,
                        UPDATED_AT,
                        2L,
                        1L
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Chương PUBLISHED không được có thông tin lưu trữ."
                );
    }

    @Test
    @DisplayName(
            "Rehydrate ARCHIVED yêu cầu metadata lưu trữ đầy đủ"
    )
    void shouldRejectArchivedRehydrateWithoutArchiveAudit() {
        assertThatThrownBy(() ->
                rehydrate(
                        ChapterStatus.ARCHIVED,
                        null,
                        null,
                        null,
                        null,
                        2L,
                        1L
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Chương ARCHIVED phải có đầy đủ thông tin lưu trữ."
                );
    }

    @Test
    @DisplayName(
            "Rehydrate ARCHIVED chấp nhận không có metadata xuất bản"
    )
    void shouldAcceptArchivedRehydrateWithoutPublishAudit() {
        Chapter chapter =
                rehydrate(
                        ChapterStatus.ARCHIVED,
                        null,
                        null,
                        OTHER_ADMIN_ID,
                        UPDATED_AT,
                        2L,
                        1L
                );

        assertThat(chapter.getPublishedBy())
                .isNull();

        assertThat(chapter.getPublishedAt())
                .isNull();

        assertThat(chapter.getArchivedBy())
                .isEqualTo(OTHER_ADMIN_ID);
    }

    @Test
    @DisplayName(
            "Rehydrate ARCHIVED chấp nhận metadata xuất bản đầy đủ"
    )
    void shouldAcceptArchivedRehydrateWithCompletePublishAudit() {
        Chapter chapter =
                rehydrate(
                        ChapterStatus.ARCHIVED,
                        ADMIN_ID,
                        UPDATED_AT,
                        OTHER_ADMIN_ID,
                        UPDATED_AT.plusSeconds(60),
                        3L,
                        2L
                );

        assertThat(chapter.getPublishedBy())
                .isEqualTo(ADMIN_ID);

        assertThat(chapter.getPublishedAt())
                .isEqualTo(UPDATED_AT);

        assertThat(chapter.getArchivedBy())
                .isEqualTo(OTHER_ADMIN_ID);
    }

    @Test
    @DisplayName(
            "Rehydrate từ chối cặp publishedBy/publishedAt không khớp"
    )
    void shouldRejectRehydrateWithMismatchedPublishAuditPair() {
        assertThatThrownBy(() ->
                rehydrate(
                        ChapterStatus.ARCHIVED,
                        ADMIN_ID,
                        null,
                        OTHER_ADMIN_ID,
                        UPDATED_AT,
                        2L,
                        1L
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Thông tin xuất bản của chương không nhất quán."
                );
    }

    @Test
    @DisplayName(
            "Rehydrate từ chối aggregateVersion nhỏ hơn 1"
    )
    void shouldRejectRehydrateWithInvalidAggregateVersion() {
        assertThatThrownBy(() ->
                rehydrate(
                        ChapterStatus.DRAFT,
                        null,
                        null,
                        null,
                        null,
                        0L,
                        1L
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Aggregate version phải lớn hơn hoặc bằng 1."
                );
    }

    @Test
    @DisplayName(
            "Rehydrate từ chối contentVersion nhỏ hơn 1"
    )
    void shouldRejectRehydrateWithInvalidContentVersion() {
        assertThatThrownBy(() ->
                rehydrate(
                        ChapterStatus.DRAFT,
                        null,
                        null,
                        null,
                        null,
                        1L,
                        0L
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Content version phải lớn hơn hoặc bằng 1."
                );
    }

    private Chapter createDraft() {
        return Chapter.createDraft(                CHAPTER_ID,
                VOLUME_ID,
                1,
                "Chương Một",
                DEFAULT_SLUG,
                "Tóm tắt chương",
                DEFAULT_CONTENT,
                ADMIN_ID,
                CREATED_AT);
    }

    private Chapter rehydrate(
            ChapterStatus status,
            UUID publishedBy,
            Instant publishedAt,
            UUID archivedBy,
            Instant archivedAt,
            long aggregateVersion,
            long contentVersion
    ) {
    	return Chapter.rehydrate(
    	        CHAPTER_ID,
    	        VOLUME_ID,
    	        1,
    	        "Chương Một",
    	        DEFAULT_SLUG,
    	        "Tóm tắt chương",
    	        DEFAULT_CONTENT,
    	        status,
    	        ADMIN_ID,
    	        ADMIN_ID,
    	        publishedBy,
    	        archivedBy,
    	        CREATED_AT,
    	        UPDATED_AT,
    	        publishedAt,
    	        archivedAt,
    	        aggregateVersion,
    	        contentVersion
    	);
    }
}
