package com.universe.novel.domain.narration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ChapterNarrationSegment Domain Model Tests")
class ChapterNarrationSegmentDomainTest {

    private static final UUID SEGMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CHAPTER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant T0 = Instant.parse("2026-09-05T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-05T11:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-05T12:00:00Z");

    @Test
    @DisplayName("Should create CURRENT segment with valid state and automatic hash computation")
    void shouldCreateCurrentSegment() {
        String text = "Trần Bình An cõng hòm trúc đi về phía nam.";
        ChapterNarrationSegment segment = ChapterNarrationSegment.create(
                SEGMENT_ID,
                CHAPTER_ID,
                0,
                text,
                T0
        );

        assertThat(segment.getId()).isEqualTo(SEGMENT_ID);
        assertThat(segment.getChapterId()).isEqualTo(CHAPTER_ID);
        assertThat(segment.getSegmentIndex()).isEqualTo(0);
        assertThat(segment.getText()).isEqualTo(text);
        assertThat(segment.getCharacterCount()).isEqualTo(text.length());
        assertThat(segment.getContentHash()).isEqualTo(NarrationTextSegment.computeSha256(text));
        assertThat(segment.getStatus()).isEqualTo(ChapterNarrationSegmentStatus.CURRENT);
        assertThat(segment.isCurrent()).isTrue();
        assertThat(segment.isRetired()).isFalse();
        assertThat(segment.getCreatedAt()).isEqualTo(T0);
        assertThat(segment.getUpdatedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("Should rehydrate segment from persistent storage correctly")
    void shouldRehydrateSegment() {
        String text = "Lạc Phách sơn thanh phong từ từ.";
        String hash = NarrationTextSegment.computeSha256(text);

        ChapterNarrationSegment segment = ChapterNarrationSegment.rehydrate(
                SEGMENT_ID,
                CHAPTER_ID,
                3,
                text,
                text.length(),
                hash,
                ChapterNarrationSegmentStatus.RETIRED,
                T0,
                T1
        );

        assertThat(segment.getId()).isEqualTo(SEGMENT_ID);
        assertThat(segment.getChapterId()).isEqualTo(CHAPTER_ID);
        assertThat(segment.getSegmentIndex()).isEqualTo(3);
        assertThat(segment.getText()).isEqualTo(text);
        assertThat(segment.getCharacterCount()).isEqualTo(text.length());
        assertThat(segment.getContentHash()).isEqualTo(hash);
        assertThat(segment.getStatus()).isEqualTo(ChapterNarrationSegmentStatus.RETIRED);
        assertThat(segment.isRetired()).isTrue();
        assertThat(segment.isCurrent()).isFalse();
        assertThat(segment.getCreatedAt()).isEqualTo(T0);
        assertThat(segment.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    @DisplayName("Should reposition CURRENT segment to a new index and update timestamp")
    void shouldRepositionCurrentSegment() {
        ChapterNarrationSegment segment = ChapterNarrationSegment.create(
                SEGMENT_ID,
                CHAPTER_ID,
                0,
                "Đoạn văn thứ nhất.",
                T0
        );

        segment.reposition(2, T1);

        assertThat(segment.getSegmentIndex()).isEqualTo(2);
        assertThat(segment.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    @DisplayName("Repositioning to the same index should not modify updatedAt")
    void shouldNotModifyUpdatedAtWhenRepositioningToSameIndex() {
        ChapterNarrationSegment segment = ChapterNarrationSegment.create(
                SEGMENT_ID,
                CHAPTER_ID,
                1,
                "Đoạn văn thứ hai.",
                T0
        );

        segment.reposition(1, T1);

        assertThat(segment.getSegmentIndex()).isEqualTo(1);
        assertThat(segment.getUpdatedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("Repositioning a RETIRED segment should throw IllegalStateException")
    void shouldRejectRepositioningRetiredSegment() {
        ChapterNarrationSegment segment = ChapterNarrationSegment.create(
                SEGMENT_ID,
                CHAPTER_ID,
                0,
                "Đoạn văn đã bị gỡ.",
                T0
        );
        segment.retire(T1);

        assertThatThrownBy(() -> segment.reposition(1, T2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CURRENT");
    }

    @Test
    @DisplayName("Repositioning with negative index should throw IllegalArgumentException")
    void shouldRejectRepositioningWithNegativeIndex() {
        ChapterNarrationSegment segment = ChapterNarrationSegment.create(
                SEGMENT_ID,
                CHAPTER_ID,
                0,
                "Đoạn văn hợp lệ.",
                T0
        );

        assertThatThrownBy(() -> segment.reposition(-1, T1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should retire a CURRENT segment and update updatedAt timestamp")
    void shouldRetireCurrentSegment() {
        ChapterNarrationSegment segment = ChapterNarrationSegment.create(
                SEGMENT_ID,
                CHAPTER_ID,
                0,
                "Đoạn văn sắp bị gỡ.",
                T0
        );

        segment.retire(T1);

        assertThat(segment.getStatus()).isEqualTo(ChapterNarrationSegmentStatus.RETIRED);
        assertThat(segment.isRetired()).isTrue();
        assertThat(segment.isCurrent()).isFalse();
        assertThat(segment.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    @DisplayName("Retiring an already RETIRED segment should be idempotent and not change updatedAt")
    void shouldBeIdempotentWhenRetiringAlreadyRetiredSegment() {
        ChapterNarrationSegment segment = ChapterNarrationSegment.create(
                SEGMENT_ID,
                CHAPTER_ID,
                0,
                "Đoạn văn đã gỡ.",
                T0
        );
        segment.retire(T1);

        segment.retire(T2);

        assertThat(segment.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    @DisplayName("Should restore a RETIRED segment to CURRENT at a new index and update timestamp")
    void shouldRestoreRetiredSegment() {
        ChapterNarrationSegment segment = ChapterNarrationSegment.create(
                SEGMENT_ID,
                CHAPTER_ID,
                0,
                "Đoạn văn tái sử dụng.",
                T0
        );
        segment.retire(T1);

        segment.restore(5, T2);

        assertThat(segment.getStatus()).isEqualTo(ChapterNarrationSegmentStatus.CURRENT);
        assertThat(segment.isCurrent()).isTrue();
        assertThat(segment.getSegmentIndex()).isEqualTo(5);
        assertThat(segment.getUpdatedAt()).isEqualTo(T2);
    }

    @Test
    @DisplayName("Restoring a CURRENT segment should throw IllegalStateException")
    void shouldRejectRestoringCurrentSegment() {
        ChapterNarrationSegment segment = ChapterNarrationSegment.create(
                SEGMENT_ID,
                CHAPTER_ID,
                0,
                "Đoạn văn đang active.",
                T0
        );

        assertThatThrownBy(() -> segment.restore(1, T1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RETIRED");
    }

    @Test
    @DisplayName("Restoring with negative index should throw IllegalArgumentException")
    void shouldRejectRestoringWithNegativeIndex() {
        ChapterNarrationSegment segment = ChapterNarrationSegment.create(
                SEGMENT_ID,
                CHAPTER_ID,
                0,
                "Đoạn văn.",
                T0
        );
        segment.retire(T1);

        assertThatThrownBy(() -> segment.restore(-1, T2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should reject invalid domain inputs on creation")
    void shouldRejectInvalidInputsOnCreation() {
        String validText = "Nội dung hợp lệ.";
        String validHash = NarrationTextSegment.computeSha256(validText);

        // Null ID
        assertThatThrownBy(() -> ChapterNarrationSegment.create(null, CHAPTER_ID, 0, validText, validHash, T0))
                .isInstanceOf(NullPointerException.class);

        // Null Chapter ID
        assertThatThrownBy(() -> ChapterNarrationSegment.create(SEGMENT_ID, null, 0, validText, validHash, T0))
                .isInstanceOf(NullPointerException.class);

        // Negative index
        assertThatThrownBy(() -> ChapterNarrationSegment.create(SEGMENT_ID, CHAPTER_ID, -1, validText, validHash, T0))
                .isInstanceOf(IllegalArgumentException.class);

        // Null text
        assertThatThrownBy(() -> ChapterNarrationSegment.create(SEGMENT_ID, CHAPTER_ID, 0, null, validHash, T0))
                .isInstanceOf(IllegalArgumentException.class);

        // Blank text
        assertThatThrownBy(() -> ChapterNarrationSegment.create(SEGMENT_ID, CHAPTER_ID, 0, "   ", T0))
                .isInstanceOf(IllegalArgumentException.class);

        // Mismatched content hash
        assertThatThrownBy(() -> ChapterNarrationSegment.create(SEGMENT_ID, CHAPTER_ID, 0, validText, "bad-hash", T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Content hash");

        // Null timestamp
        assertThatThrownBy(() -> ChapterNarrationSegment.create(SEGMENT_ID, CHAPTER_ID, 0, validText, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Equals and hashCode are based on segment ID")
    void shouldTestEqualsAndHashCode() {
        ChapterNarrationSegment seg1 = ChapterNarrationSegment.create(SEGMENT_ID, CHAPTER_ID, 0, "Nội dung 1.", T0);
        ChapterNarrationSegment seg2 = ChapterNarrationSegment.create(SEGMENT_ID, CHAPTER_ID, 1, "Nội dung khác.", T1);
        ChapterNarrationSegment seg3 = ChapterNarrationSegment.create(UUID.randomUUID(), CHAPTER_ID, 0, "Nội dung 1.", T0);

        assertThat(seg1).isEqualTo(seg2);
        assertThat(seg1.hashCode()).isEqualTo(seg2.hashCode());
        assertThat(seg1).isNotEqualTo(seg3);
    }
}
