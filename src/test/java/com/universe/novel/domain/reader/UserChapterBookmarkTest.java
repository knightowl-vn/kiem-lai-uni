package com.universe.novel.domain.reader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserChapterBookmarkTest {

    private static final UUID BOOKMARK_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID USER_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID CHAPTER_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final Instant CREATED_AT =
            Instant.parse("2026-08-25T10:00:00Z");

    @Test
    @DisplayName("Tạo mới UserChapterBookmark hợp lệ qua factory method create")
    void shouldCreateBookmarkSuccessfully() {
        UserChapterBookmark bookmark = UserChapterBookmark.create(
                BOOKMARK_ID,
                USER_ID,
                CHAPTER_ID,
                CREATED_AT
        );

        assertThat(bookmark).isNotNull();
        assertThat(bookmark.getId()).isEqualTo(BOOKMARK_ID);
        assertThat(bookmark.getUserId()).isEqualTo(USER_ID);
        assertThat(bookmark.getChapterId()).isEqualTo(CHAPTER_ID);
        assertThat(bookmark.getCreatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("Khôi phục UserChapterBookmark từ persistence qua factory method rehydrate")
    void shouldRehydrateBookmarkSuccessfully() {
        UserChapterBookmark bookmark = UserChapterBookmark.rehydrate(
                BOOKMARK_ID,
                USER_ID,
                CHAPTER_ID,
                CREATED_AT
        );

        assertThat(bookmark).isNotNull();
        assertThat(bookmark.getId()).isEqualTo(BOOKMARK_ID);
        assertThat(bookmark.getUserId()).isEqualTo(USER_ID);
        assertThat(bookmark.getChapterId()).isEqualTo(CHAPTER_ID);
        assertThat(bookmark.getCreatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("Từ chối tạo Bookmark khi ID là null")
    void shouldRejectNullId() {
        assertThatThrownBy(() -> UserChapterBookmark.create(
                null,
                USER_ID,
                CHAPTER_ID,
                CREATED_AT
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("ID dấu trang chương không được để trống.");
    }

    @Test
    @DisplayName("Từ chối tạo Bookmark khi userId là null")
    void shouldRejectNullUserId() {
        assertThatThrownBy(() -> UserChapterBookmark.create(
                BOOKMARK_ID,
                null,
                CHAPTER_ID,
                CREATED_AT
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("ID người dùng không được để trống.");
    }

    @Test
    @DisplayName("Từ chối tạo Bookmark khi chapterId là null")
    void shouldRejectNullChapterId() {
        assertThatThrownBy(() -> UserChapterBookmark.create(
                BOOKMARK_ID,
                USER_ID,
                null,
                CREATED_AT
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("ID chương không được để trống.");
    }

    @Test
    @DisplayName("Từ chối tạo Bookmark khi createdAt là null")
    void shouldRejectNullCreatedAt() {
        assertThatThrownBy(() -> UserChapterBookmark.create(
                BOOKMARK_ID,
                USER_ID,
                CHAPTER_ID,
                null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Thời gian tạo dấu trang không được để trống.");
    }

    @Test
    @DisplayName("Xác thực tính bình đẳng dựa trên ID dấu trang")
    void shouldVerifyEqualityBasedOnId() {
        UserChapterBookmark bookmark1 = UserChapterBookmark.create(
                BOOKMARK_ID,
                USER_ID,
                CHAPTER_ID,
                CREATED_AT
        );

        UserChapterBookmark bookmark2 = UserChapterBookmark.rehydrate(
                BOOKMARK_ID,
                USER_ID,
                CHAPTER_ID,
                CREATED_AT
        );

        UserChapterBookmark differentBookmark = UserChapterBookmark.create(
                UUID.randomUUID(),
                USER_ID,
                CHAPTER_ID,
                CREATED_AT
        );

        assertThat(bookmark1).isEqualTo(bookmark2);
        assertThat(bookmark1.hashCode()).isEqualTo(bookmark2.hashCode());
        assertThat(bookmark1).isNotEqualTo(differentBookmark);
        assertThat(bookmark1.toString()).contains(BOOKMARK_ID.toString());
    }
}
