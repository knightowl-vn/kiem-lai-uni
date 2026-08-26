package com.universe.novel.domain.reader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserChapterReadingHistoryTest {

    private static final UUID HISTORY_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID USER_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID CHAPTER_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final Instant T0 =
            Instant.parse("2026-08-26T08:00:00Z");

    private static final Instant T1 =
            Instant.parse("2026-08-26T08:30:00Z");

    @Test
    @DisplayName("createInitial sets firstReadAt = lastReadAt = now")
    void shouldCreateInitialHistoryCorrectly() {
        UserChapterReadingHistory history = UserChapterReadingHistory.createInitial(
                HISTORY_ID,
                USER_ID,
                CHAPTER_ID,
                T0
        );

        assertThat(history).isNotNull();
        assertThat(history.getId()).isEqualTo(HISTORY_ID);
        assertThat(history.getUserId()).isEqualTo(USER_ID);
        assertThat(history.getChapterId()).isEqualTo(CHAPTER_ID);
        assertThat(history.getFirstReadAt()).isEqualTo(T0);
        assertThat(history.getLastReadAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("recordRead updates only lastReadAt and preserves firstReadAt")
    void shouldUpdateOnlyLastReadAtOnRecordRead() {
        UserChapterReadingHistory history = UserChapterReadingHistory.createInitial(
                HISTORY_ID,
                USER_ID,
                CHAPTER_ID,
                T0
        );

        history.recordRead(T1);

        assertThat(history.getFirstReadAt()).isEqualTo(T0);
        assertThat(history.getLastReadAt()).isEqualTo(T1);
    }

    @Test
    @DisplayName("rehydrate correctly restores all fields from persistence representation")
    void shouldRehydrateHistoryCorrectly() {
        UserChapterReadingHistory history = UserChapterReadingHistory.rehydrate(
                HISTORY_ID,
                USER_ID,
                CHAPTER_ID,
                T0,
                T1
        );

        assertThat(history).isNotNull();
        assertThat(history.getId()).isEqualTo(HISTORY_ID);
        assertThat(history.getUserId()).isEqualTo(USER_ID);
        assertThat(history.getChapterId()).isEqualTo(CHAPTER_ID);
        assertThat(history.getFirstReadAt()).isEqualTo(T0);
        assertThat(history.getLastReadAt()).isEqualTo(T1);
    }

    @Test
    @DisplayName("createInitial rejects null arguments")
    void shouldRejectNullArgumentsInCreateInitial() {
        assertThatThrownBy(() -> UserChapterReadingHistory.createInitial(
                null, USER_ID, CHAPTER_ID, T0
        )).isInstanceOf(NullPointerException.class)
                .hasMessage("ID lịch sử đọc không được để trống.");

        assertThatThrownBy(() -> UserChapterReadingHistory.createInitial(
                HISTORY_ID, null, CHAPTER_ID, T0
        )).isInstanceOf(NullPointerException.class)
                .hasMessage("ID người dùng không được để trống.");

        assertThatThrownBy(() -> UserChapterReadingHistory.createInitial(
                HISTORY_ID, USER_ID, null, T0
        )).isInstanceOf(NullPointerException.class)
                .hasMessage("ID chương không được để trống.");

        assertThatThrownBy(() -> UserChapterReadingHistory.createInitial(
                HISTORY_ID, USER_ID, CHAPTER_ID, null
        )).isInstanceOf(NullPointerException.class)
                .hasMessage("Thời gian đọc không được để trống.");
    }

    @Test
    @DisplayName("rehydrate rejects null arguments")
    void shouldRejectNullArgumentsInRehydrate() {
        assertThatThrownBy(() -> UserChapterReadingHistory.rehydrate(
                null, USER_ID, CHAPTER_ID, T0, T1
        )).isInstanceOf(NullPointerException.class)
                .hasMessage("ID lịch sử đọc không được để trống.");

        assertThatThrownBy(() -> UserChapterReadingHistory.rehydrate(
                HISTORY_ID, null, CHAPTER_ID, T0, T1
        )).isInstanceOf(NullPointerException.class)
                .hasMessage("ID người dùng không được để trống.");

        assertThatThrownBy(() -> UserChapterReadingHistory.rehydrate(
                HISTORY_ID, USER_ID, null, T0, T1
        )).isInstanceOf(NullPointerException.class)
                .hasMessage("ID chương không được để trống.");

        assertThatThrownBy(() -> UserChapterReadingHistory.rehydrate(
                HISTORY_ID, USER_ID, CHAPTER_ID, null, T1
        )).isInstanceOf(NullPointerException.class)
                .hasMessage("Thời gian đọc lần đầu không được để trống.");

        assertThatThrownBy(() -> UserChapterReadingHistory.rehydrate(
                HISTORY_ID, USER_ID, CHAPTER_ID, T0, null
        )).isInstanceOf(NullPointerException.class)
                .hasMessage("Thời gian đọc gần nhất không được để trống.");
    }

    @Test
    @DisplayName("recordRead rejects null timestamp")
    void shouldRejectNullTimestampInRecordRead() {
        UserChapterReadingHistory history = UserChapterReadingHistory.createInitial(
                HISTORY_ID,
                USER_ID,
                CHAPTER_ID,
                T0
        );

        assertThatThrownBy(() -> history.recordRead(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Thời gian đọc không được để trống.");
    }

    @Test
    @DisplayName("Verify equality and hashCode based on history ID")
    void shouldVerifyEqualityBasedOnId() {
        UserChapterReadingHistory history1 = UserChapterReadingHistory.createInitial(
                HISTORY_ID,
                USER_ID,
                CHAPTER_ID,
                T0
        );

        UserChapterReadingHistory history2 = UserChapterReadingHistory.rehydrate(
                HISTORY_ID,
                USER_ID,
                CHAPTER_ID,
                T0,
                T1
        );

        UserChapterReadingHistory differentHistory = UserChapterReadingHistory.createInitial(
                UUID.randomUUID(),
                USER_ID,
                CHAPTER_ID,
                T0
        );

        assertThat(history1).isEqualTo(history2);
        assertThat(history1.hashCode()).isEqualTo(history2.hashCode());
        assertThat(history1).isNotEqualTo(differentHistory);
        assertThat(history1.toString()).contains(HISTORY_ID.toString());
    }
}
