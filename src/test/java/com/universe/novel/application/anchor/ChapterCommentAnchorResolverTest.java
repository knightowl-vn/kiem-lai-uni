package com.universe.novel.application.anchor;

import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort.ChapterAnchorDocumentSnapshot;
import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort.ReaderBlock;
import com.universe.novel.domain.anchor.ChapterCommentAnchor;
import com.universe.novel.domain.anchor.ChapterCommentAnchorResolution;
import com.universe.novel.domain.anchor.ChapterCommentAnchorResolutionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ChapterCommentAnchorResolver Unit Tests")
class ChapterCommentAnchorResolverTest {

    private static final UUID ROOT_COMMENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CHAPTER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant NOW = Instant.parse("2026-09-16T12:00:00Z");

    private static final String KEY_1 = "blk-0000000000000001-1";
    private static final String KEY_2 = "blk-0000000000000002-1";
    private static final String KEY_3 = "blk-0000000000000003-1";

    private ChapterCommentAnchorResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ChapterCommentAnchorResolver();
    }

    @Test
    @DisplayName("resolve: throws NullPointerException when anchor or snapshot is null")
    void shouldThrowWhenArgsNull() {
        ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(CHAPTER_ID, 1L, List.of());
        ChapterCommentAnchor anchor = ChapterCommentAnchor.createBlock(ROOT_COMMENT_ID, CHAPTER_ID, 1L, KEY_1, "text", NOW);

        assertThatThrownBy(() -> resolver.resolve(null, snapshot))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("anchor cannot be null");

        assertThatThrownBy(() -> resolver.resolve(anchor, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("snapshot cannot be null");
    }

    @Nested
    @DisplayName("Same Version Tests")
    class SameVersionTests {

        @Test
        @DisplayName("BLOCK: matching blockKey resolves CURRENT")
        void blockMatchResolvesCurrent() {
            ChapterCommentAnchor anchor = ChapterCommentAnchor.createBlock(ROOT_COMMENT_ID, CHAPTER_ID, 1L, KEY_1, "Đoạn văn", NOW);
            ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(
                    CHAPTER_ID, 1L, List.of(new ReaderBlock(KEY_1, "Đoạn văn"))
            );

            ChapterCommentAnchorResolution res = resolver.resolve(anchor, snapshot);

            assertThat(res.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.CURRENT);
            assertThat(res.resolvedBlockKey()).isEqualTo(KEY_1);
        }

        @Test
        @DisplayName("BLOCK: missing blockKey resolves STALE")
        void blockMissingResolvesStale() {
            ChapterCommentAnchor anchor = ChapterCommentAnchor.createBlock(ROOT_COMMENT_ID, CHAPTER_ID, 1L, KEY_1, "Đoạn văn", NOW);
            ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(
                    CHAPTER_ID, 1L, List.of(new ReaderBlock(KEY_2, "Đoạn khác"))
            );

            ChapterCommentAnchorResolution res = resolver.resolve(anchor, snapshot);

            assertThat(res.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.STALE);
            assertThat(res.resolvedBlockKey()).isNull();
        }

        @Test
        @DisplayName("TEXT_RANGE: matching blockKey and offsets resolves CURRENT")
        void textRangeMatchResolvesCurrent() {
            ChapterCommentAnchor anchor = ChapterCommentAnchor.createTextRange(
                    ROOT_COMMENT_ID, CHAPTER_ID, 1L, KEY_1, 0, 5, "Hello", "", " World", NOW
            );
            ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(
                    CHAPTER_ID, 1L, List.of(new ReaderBlock(KEY_1, "Hello World"))
            );

            ChapterCommentAnchorResolution res = resolver.resolve(anchor, snapshot);

            assertThat(res.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.CURRENT);
            assertThat(res.resolvedBlockKey()).isEqualTo(KEY_1);
            assertThat(res.resolvedStartOffset()).isEqualTo(0);
            assertThat(res.resolvedEndOffset()).isEqualTo(5);
        }

        @Test
        @DisplayName("TEXT_RANGE: mismatched text at offsets in same version resolves STALE (no relocation)")
        void textRangeMismatchResolvesStale() {
            ChapterCommentAnchor anchor = ChapterCommentAnchor.createTextRange(
                    ROOT_COMMENT_ID, CHAPTER_ID, 1L, KEY_1, 0, 5, "World", "", "", NOW
            );
            ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(
                    CHAPTER_ID, 1L, List.of(new ReaderBlock(KEY_1, "Hello World"))
            );

            ChapterCommentAnchorResolution res = resolver.resolve(anchor, snapshot);

            assertThat(res.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.STALE);
        }
    }

    @Nested
    @DisplayName("Different Version Tests")
    class DifferentVersionTests {

        @Test
        @DisplayName("BLOCK: same blockKey in newer version resolves RELOCATED")
        void blockSameKeyResolvesRelocated() {
            ChapterCommentAnchor anchor = ChapterCommentAnchor.createBlock(ROOT_COMMENT_ID, CHAPTER_ID, 1L, KEY_1, "Đoạn cũ", NOW);
            ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(
                    CHAPTER_ID, 2L, List.of(new ReaderBlock(KEY_1, "Đoạn mới"))
            );

            ChapterCommentAnchorResolution res = resolver.resolve(anchor, snapshot);

            assertThat(res.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.RELOCATED);
            assertThat(res.resolvedBlockKey()).isEqualTo(KEY_1);
        }

        @Test
        @DisplayName("BLOCK: changed blockKey with single exact text match resolves RELOCATED")
        void blockChangedKeyExactTextResolvesRelocated() {
            ChapterCommentAnchor anchor = ChapterCommentAnchor.createBlock(ROOT_COMMENT_ID, CHAPTER_ID, 1L, KEY_1, "Nội dung giữ nguyên", NOW);
            ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(
                    CHAPTER_ID, 2L, List.of(new ReaderBlock(KEY_2, "Nội dung giữ nguyên"))
            );

            ChapterCommentAnchorResolution res = resolver.resolve(anchor, snapshot);

            assertThat(res.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.RELOCATED);
            assertThat(res.resolvedBlockKey()).isEqualTo(KEY_2);
        }

        @Test
        @DisplayName("TEXT_RANGE: same blockKey with text shifted resolves RELOCATED with new offsets")
        void textRangeShiftedResolvesRelocated() {
            ChapterCommentAnchor anchor = ChapterCommentAnchor.createTextRange(
                    ROOT_COMMENT_ID, CHAPTER_ID, 1L, KEY_1, 0, 5, "World", "", "", NOW
            );
            ChapterAnchorDocumentSnapshot snapshot = new ChapterAnchorDocumentSnapshot(
                    CHAPTER_ID, 2L, List.of(new ReaderBlock(KEY_1, "Hello World"))
            );

            ChapterCommentAnchorResolution res = resolver.resolve(anchor, snapshot);

            assertThat(res.status()).isEqualTo(ChapterCommentAnchorResolutionStatus.RELOCATED);
            assertThat(res.resolvedBlockKey()).isEqualTo(KEY_1);
            assertThat(res.resolvedStartOffset()).isEqualTo(6);
            assertThat(res.resolvedEndOffset()).isEqualTo(11);
        }
    }
}
