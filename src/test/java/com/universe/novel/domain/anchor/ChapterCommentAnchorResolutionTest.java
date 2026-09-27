package com.universe.novel.domain.anchor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChapterCommentAnchorResolutionTest {

    private static final UUID ROOT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CHAPTER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    @DisplayName("STALE: Khóa resolvedBlockKey và tọa độ phải là null")
    void staleInvariants() {
        ChapterCommentAnchorResolution res = ChapterCommentAnchorResolution.stale(ROOT_ID, CHAPTER_ID, 1L, 2L);

        assertThat(res.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.STALE);
        assertThat(res.resolvedBlockKey()).isNull();
        assertThat(res.resolvedStartOffset()).isNull();
        assertThat(res.resolvedEndOffset()).isNull();

        assertThatThrownBy(() -> new ChapterCommentAnchorResolution(
                ROOT_ID, CHAPTER_ID, 1L, 2L, ChapterCommentAnchorResolutionStatus.STALE,
                "blk-1111111111111111-1", null, null
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("BLOCK: Tọa độ startOffset và endOffset phải là null")
    void blockInvariants() {
        ChapterCommentAnchorResolution current = ChapterCommentAnchorResolution.currentBlock(
                ROOT_ID, CHAPTER_ID, 1L, "blk-1111111111111111-1"
        );
        assertThat(current.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.CURRENT);
        assertThat(current.resolvedBlockKey()).isEqualTo("blk-1111111111111111-1");
        assertThat(current.resolvedStartOffset()).isNull();
        assertThat(current.resolvedEndOffset()).isNull();

        ChapterCommentAnchorResolution relocated = ChapterCommentAnchorResolution.relocatedBlock(
                ROOT_ID, CHAPTER_ID, 1L, 2L, "blk-1111111111111111-1"
        );
        assertThat(relocated.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.RELOCATED);

        // Offsets not null for block -> error
        assertThatThrownBy(() -> new ChapterCommentAnchorResolution(
                ROOT_ID, CHAPTER_ID, 1L, 2L, ChapterCommentAnchorResolutionStatus.RELOCATED,
                "blk-1111111111111111-1", 0, null
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("TEXT_RANGE: Cả hai tọa độ bắt buộc phải có giá trị và 0 <= start < end")
    void textRangeInvariants() {
        ChapterCommentAnchorResolution current = ChapterCommentAnchorResolution.currentTextRange(
                ROOT_ID, CHAPTER_ID, 1L, "blk-1111111111111111-1", 5, 10
        );
        assertThat(current.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.CURRENT);
        assertThat(current.resolvedStartOffset()).isEqualTo(5);
        assertThat(current.resolvedEndOffset()).isEqualTo(10);

        // start < 0 -> error
        assertThatThrownBy(() -> ChapterCommentAnchorResolution.currentTextRange(
                ROOT_ID, CHAPTER_ID, 1L, "blk-1111111111111111-1", -1, 5
        )).isInstanceOf(IllegalArgumentException.class);

        // start >= end -> error
        assertThatThrownBy(() -> ChapterCommentAnchorResolution.currentTextRange(
                ROOT_ID, CHAPTER_ID, 1L, "blk-1111111111111111-1", 5, 5
        )).isInstanceOf(IllegalArgumentException.class);

        // start > end -> error
        assertThatThrownBy(() -> ChapterCommentAnchorResolution.currentTextRange(
                ROOT_ID, CHAPTER_ID, 1L, "blk-1111111111111111-1", 10, 5
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("originalContentVersion < 1 bị từ chối")
    void versionZeroRejectedForOriginal() {
        assertThatThrownBy(() -> new ChapterCommentAnchorResolution(
                ROOT_ID, CHAPTER_ID, 0L, 1L, ChapterCommentAnchorResolutionStatus.STALE, null, null, null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("originalContentVersion must be >= 1");
    }

    @Test
    @DisplayName("currentContentVersion < 1 bị từ chối")
    void versionZeroRejectedForCurrent() {
        assertThatThrownBy(() -> new ChapterCommentAnchorResolution(
                ROOT_ID, CHAPTER_ID, 1L, 0L, ChapterCommentAnchorResolutionStatus.STALE, null, null, null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currentContentVersion must be >= 1");
    }

    @Test
    @DisplayName("CURRENT với version khác nhau bị từ chối")
    void currentWithDifferentVersionsRejected() {
        assertThatThrownBy(() -> new ChapterCommentAnchorResolution(
                ROOT_ID, CHAPTER_ID, 1L, 2L, ChapterCommentAnchorResolutionStatus.CURRENT,
                "blk-1111111111111111-1", null, null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CURRENT resolution requires originalContentVersion == currentContentVersion");
    }

    @Test
    @DisplayName("RELOCATED với version bằng nhau bị từ chối")
    void relocatedWithEqualVersionsRejected() {
        assertThatThrownBy(() -> new ChapterCommentAnchorResolution(
                ROOT_ID, CHAPTER_ID, 1L, 1L, ChapterCommentAnchorResolutionStatus.RELOCATED,
                "blk-1111111111111111-1", null, null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RELOCATED resolution requires originalContentVersion != currentContentVersion");
    }

    @Test
    @DisplayName("STALE với version bằng nhau hoặc khác nhau đều được chấp nhận")
    void staleWithSameOrDifferentVersionsAccepted() {
        ChapterCommentAnchorResolution sameVersion = ChapterCommentAnchorResolution.stale(
                ROOT_ID, CHAPTER_ID, 1L, 1L
        );
        assertThat(sameVersion.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.STALE);
        assertThat(sameVersion.originalContentVersion()).isEqualTo(1L);
        assertThat(sameVersion.currentContentVersion()).isEqualTo(1L);

        ChapterCommentAnchorResolution diffVersion = ChapterCommentAnchorResolution.stale(
                ROOT_ID, CHAPTER_ID, 1L, 2L
        );
        assertThat(diffVersion.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.STALE);
        assertThat(diffVersion.originalContentVersion()).isEqualTo(1L);
        assertThat(diffVersion.currentContentVersion()).isEqualTo(2L);
    }
}
