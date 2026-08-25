package com.universe.novel.domain.reader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserReadingProgressTest {

    private static final UUID PROGRESS_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final UUID USER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID CHAPTER_1_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID CHAPTER_20_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final UUID CHAPTER_82_ID =
            UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static final UUID CHAPTER_800_ID =
            UUID.fromString("55555555-5555-5555-5555-555555555555");

    private static final Instant T0 =
            Instant.parse("2026-08-25T08:00:00Z");

    private static final Instant T1 =
            Instant.parse("2026-08-25T08:30:00Z");

    private static final Instant T2 =
            Instant.parse("2026-08-25T09:00:00Z");

    private static final Instant T3 =
            Instant.parse("2026-08-25T09:30:00Z");

    @Test
    @DisplayName("createInitial sets all state correctly")
    void shouldCreateInitialProgressCorrectly() {
        UserReadingProgress progress = UserReadingProgress.createInitial(
                PROGRESS_ID,
                USER_ID,
                CHAPTER_1_ID,
                1,
                T0
        );

        assertThat(progress.getId()).isEqualTo(PROGRESS_ID);
        assertThat(progress.getUserId()).isEqualTo(USER_ID);
        assertThat(progress.getLastOpenedChapterId()).isEqualTo(CHAPTER_1_ID);
        assertThat(progress.getHighestReachedChapterNumber()).isEqualTo(1);
        assertThat(progress.getCreatedAt()).isEqualTo(T0);
        assertThat(progress.getUpdatedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("Opening a later chapter updates lastOpened and increases highest progress")
    void shouldUpdateLastOpenedAndIncreaseHighestWhenOpeningLaterChapter() {
        UserReadingProgress progress = UserReadingProgress.createInitial(
                PROGRESS_ID,
                USER_ID,
                CHAPTER_20_ID,
                20,
                T0
        );

        boolean mutated = progress.recordChapterAccess(
                CHAPTER_82_ID,
                82,
                T1
        );

        assertThat(mutated).isTrue();
        assertThat(progress.getLastOpenedChapterId()).isEqualTo(CHAPTER_82_ID);
        assertThat(progress.getHighestReachedChapterNumber()).isEqualTo(82);
        assertThat(progress.getUpdatedAt()).isEqualTo(T1);
        assertThat(progress.getCreatedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("Opening an older chapter updates lastOpened but preserves highest progress")
    void shouldUpdateLastOpenedAndPreserveHighestWhenOpeningOlderChapter() {
        // Initial state at Chapter 82
        UserReadingProgress progress = UserReadingProgress.createInitial(
                PROGRESS_ID,
                USER_ID,
                CHAPTER_82_ID,
                82,
                T0
        );

        // Later opens Chapter 20
        boolean mutated = progress.recordChapterAccess(
                CHAPTER_20_ID,
                20,
                T1
        );

        assertThat(mutated).isTrue();
        assertThat(progress.getLastOpenedChapterId()).isEqualTo(CHAPTER_20_ID);
        assertThat(progress.getHighestReachedChapterNumber()).isEqualTo(82);
        assertThat(progress.getUpdatedAt()).isEqualTo(T1);
        assertThat(progress.getCreatedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("Opening the same last-opened chapter is an idempotent no-op")
    void shouldBeNoOpWhenOpeningSameLastOpenedChapter() {
        UserReadingProgress progress = UserReadingProgress.createInitial(
                PROGRESS_ID,
                USER_ID,
                CHAPTER_20_ID,
                20,
                T0
        );

        // Access Chapter 20 again at T1
        boolean mutated = progress.recordChapterAccess(
                CHAPTER_20_ID,
                20,
                T1
        );

        assertThat(mutated).isFalse();
        assertThat(progress.getLastOpenedChapterId()).isEqualTo(CHAPTER_20_ID);
        assertThat(progress.getHighestReachedChapterNumber()).isEqualTo(20);
        assertThat(progress.getUpdatedAt()).isEqualTo(T0); // Exactly preserved
        assertThat(progress.getCreatedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("No-op preserves updatedAt exactly without touching timestamp")
    void shouldPreserveUpdatedAtExactlyOnRepeatedAccess() {
        UserReadingProgress progress = UserReadingProgress.createInitial(
                PROGRESS_ID,
                USER_ID,
                CHAPTER_82_ID,
                82,
                T0
        );

        // First transition: open older chapter 20 at T1
        progress.recordChapterAccess(CHAPTER_20_ID, 20, T1);
        assertThat(progress.getUpdatedAt()).isEqualTo(T1);

        // Repeated access to chapter 20 at T2 -> no-op
        boolean secondAccess = progress.recordChapterAccess(CHAPTER_20_ID, 20, T2);

        assertThat(secondAccess).isFalse();
        assertThat(progress.getUpdatedAt()).isEqualTo(T1); // Retains T1, not updated to T2
    }

    @Test
    @DisplayName("Direct jump increases highest progress directly without intermediate synthesis")
    void shouldDirectlyJumpToHighChapter() {
        UserReadingProgress progress = UserReadingProgress.createInitial(
                PROGRESS_ID,
                USER_ID,
                CHAPTER_20_ID,
                20,
                T0
        );

        boolean mutated = progress.recordChapterAccess(
                CHAPTER_800_ID,
                800,
                T1
        );

        assertThat(mutated).isTrue();
        assertThat(progress.getLastOpenedChapterId()).isEqualTo(CHAPTER_800_ID);
        assertThat(progress.getHighestReachedChapterNumber()).isEqualTo(800);
        assertThat(progress.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    @DisplayName("highestReachedChapterNumber never decreases across multiple complex accesses")
    void shouldNeverDecreaseHighestReachedAcrossMultipleAccesses() {
        // Start at Chapter 50
        UserReadingProgress progress = UserReadingProgress.createInitial(
                PROGRESS_ID,
                USER_ID,
                UUID.randomUUID(),
                50,
                T0
        );
        assertThat(progress.getHighestReachedChapterNumber()).isEqualTo(50);

        // Read forward to Chapter 100
        UUID ch100 = UUID.randomUUID();
        progress.recordChapterAccess(ch100, 100, T1);
        assertThat(progress.getLastOpenedChapterId()).isEqualTo(ch100);
        assertThat(progress.getHighestReachedChapterNumber()).isEqualTo(100);

        // Jump back to Chapter 10
        UUID ch10 = UUID.randomUUID();
        progress.recordChapterAccess(ch10, 10, T2);
        assertThat(progress.getLastOpenedChapterId()).isEqualTo(ch10);
        assertThat(progress.getHighestReachedChapterNumber()).isEqualTo(100);

        // Read forward to Chapter 75 (still lower than 100)
        UUID ch75 = UUID.randomUUID();
        progress.recordChapterAccess(ch75, 75, T3);
        assertThat(progress.getLastOpenedChapterId()).isEqualTo(ch75);
        assertThat(progress.getHighestReachedChapterNumber()).isEqualTo(100);

        // Read forward to Chapter 150 (exceeds 100)
        UUID ch150 = UUID.randomUUID();
        Instant t4 = Instant.parse("2026-08-25T10:00:00Z");
        progress.recordChapterAccess(ch150, 150, t4);
        assertThat(progress.getLastOpenedChapterId()).isEqualTo(ch150);
        assertThat(progress.getHighestReachedChapterNumber()).isEqualTo(150);
    }

    @Test
    @DisplayName("Invalid chapter number (< 1) is rejected in createInitial, rehydrate, and recordChapterAccess")
    void shouldRejectInvalidChapterNumber() {
        // createInitial with chapterNumber < 1
        assertThatThrownBy(() -> UserReadingProgress.createInitial(
                PROGRESS_ID,
                USER_ID,
                CHAPTER_1_ID,
                0,
                T0
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Số chương phải lớn hơn hoặc bằng 1.");

        assertThatThrownBy(() -> UserReadingProgress.createInitial(
                PROGRESS_ID,
                USER_ID,
                CHAPTER_1_ID,
                -5,
                T0
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Số chương phải lớn hơn hoặc bằng 1.");

        // rehydrate with highestReachedChapterNumber < 1
        assertThatThrownBy(() -> UserReadingProgress.rehydrate(
                PROGRESS_ID,
                USER_ID,
                CHAPTER_1_ID,
                0,
                T0,
                T0
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Số chương phải lớn hơn hoặc bằng 1.");

        // recordChapterAccess with chapterNumber < 1
        UserReadingProgress progress = UserReadingProgress.createInitial(
                PROGRESS_ID,
                USER_ID,
                CHAPTER_1_ID,
                1,
                T0
        );

        assertThatThrownBy(() -> progress.recordChapterAccess(
                CHAPTER_20_ID,
                0,
                T1
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Số chương phải lớn hơn hoặc bằng 1.");
    }

    @Test
    @DisplayName("Required UUID and timestamp null validations are strictly enforced")
    void shouldRejectNullArguments() {
        // createInitial null checks
        assertThatThrownBy(() -> UserReadingProgress.createInitial(
                null, USER_ID, CHAPTER_1_ID, 1, T0
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> UserReadingProgress.createInitial(
                PROGRESS_ID, null, CHAPTER_1_ID, 1, T0
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> UserReadingProgress.createInitial(
                PROGRESS_ID, USER_ID, null, 1, T0
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> UserReadingProgress.createInitial(
                PROGRESS_ID, USER_ID, CHAPTER_1_ID, 1, null
        )).isInstanceOf(NullPointerException.class);

        // rehydrate null checks
        assertThatThrownBy(() -> UserReadingProgress.rehydrate(
                null, USER_ID, CHAPTER_1_ID, 1, T0, T0
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> UserReadingProgress.rehydrate(
                PROGRESS_ID, null, CHAPTER_1_ID, 1, T0, T0
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> UserReadingProgress.rehydrate(
                PROGRESS_ID, USER_ID, null, 1, T0, T0
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> UserReadingProgress.rehydrate(
                PROGRESS_ID, USER_ID, CHAPTER_1_ID, 1, null, T0
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> UserReadingProgress.rehydrate(
                PROGRESS_ID, USER_ID, CHAPTER_1_ID, 1, T0, null
        )).isInstanceOf(NullPointerException.class);

        // recordChapterAccess null checks
        UserReadingProgress progress = UserReadingProgress.createInitial(
                PROGRESS_ID, USER_ID, CHAPTER_1_ID, 1, T0
        );

        assertThatThrownBy(() -> progress.recordChapterAccess(
                null, 2, T1
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> progress.recordChapterAccess(
                CHAPTER_20_ID, 20, null
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("rehydrate correctly restores all state from persistence representation")
    void shouldRehydrateStateCorrectly() {
        UserReadingProgress progress = UserReadingProgress.rehydrate(
                PROGRESS_ID,
                USER_ID,
                CHAPTER_20_ID,
                82,
                T0,
                T1
        );

        assertThat(progress.getId()).isEqualTo(PROGRESS_ID);
        assertThat(progress.getUserId()).isEqualTo(USER_ID);
        assertThat(progress.getLastOpenedChapterId()).isEqualTo(CHAPTER_20_ID);
        assertThat(progress.getHighestReachedChapterNumber()).isEqualTo(82);
        assertThat(progress.getCreatedAt()).isEqualTo(T0);
        assertThat(progress.getUpdatedAt()).isEqualTo(T1);
    }
}
