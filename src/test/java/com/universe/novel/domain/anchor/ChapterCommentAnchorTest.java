package com.universe.novel.domain.anchor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ChapterCommentAnchor Domain Unit Tests")
class ChapterCommentAnchorTest {

    private static final UUID ROOT_COMMENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CHAPTER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant NOW = Instant.parse("2026-09-16T12:00:00Z");
    private static final String VALID_BLOCK_KEY_1 = "blk-0123456789abcdef-1";
    private static final String VALID_BLOCK_KEY_2 = "blk-abcdef0123456789-2";

    @Test
    @DisplayName("Should create valid BLOCK anchor with null offsets and empty context")
    void shouldCreateValidBlockAnchor() {
        ChapterCommentAnchor anchor = ChapterCommentAnchor.createBlock(
                ROOT_COMMENT_ID,
                CHAPTER_ID,
                3L,
                VALID_BLOCK_KEY_1,
                "Đoạn văn mở đầu câu chuyện.",
                NOW
        );

        assertThat(anchor.getRootCommentId()).isEqualTo(ROOT_COMMENT_ID);
        assertThat(anchor.getChapterId()).isEqualTo(CHAPTER_ID);
        assertThat(anchor.getContentVersion()).isEqualTo(3L);
        assertThat(anchor.getBlockKey()).isEqualTo(VALID_BLOCK_KEY_1);
        assertThat(anchor.getAnchorKind()).isEqualTo(ChapterCommentAnchorKind.BLOCK);
        assertThat(anchor.getStartOffset()).isNull();
        assertThat(anchor.getEndOffset()).isNull();
        assertThat(anchor.getSelectedText()).isEqualTo("Đoạn văn mở đầu câu chuyện.");
        assertThat(anchor.getContextBefore()).isEmpty();
        assertThat(anchor.getContextAfter()).isEmpty();
        assertThat(anchor.getCreatedAt()).isEqualTo(NOW);
        assertThat(anchor.isBlockAnchor()).isTrue();
        assertThat(anchor.isTextRangeAnchor()).isFalse();
    }

    @Test
    @DisplayName("Should create valid TEXT_RANGE anchor with offsets and surrounding context")
    void shouldCreateValidTextRangeAnchor() {
        String selected = "Trần Bình An";
        int start = 10;
        int end = start + selected.length(); // 22

        ChapterCommentAnchor anchor = ChapterCommentAnchor.createTextRange(
                ROOT_COMMENT_ID,
                CHAPTER_ID,
                2L,
                VALID_BLOCK_KEY_2,
                start,
                end,
                selected,
                "Trước đó, ",
                " cất bước lên núi.",
                NOW
        );

        assertThat(anchor.getRootCommentId()).isEqualTo(ROOT_COMMENT_ID);
        assertThat(anchor.getChapterId()).isEqualTo(CHAPTER_ID);
        assertThat(anchor.getContentVersion()).isEqualTo(2L);
        assertThat(anchor.getBlockKey()).isEqualTo(VALID_BLOCK_KEY_2);
        assertThat(anchor.getAnchorKind()).isEqualTo(ChapterCommentAnchorKind.TEXT_RANGE);
        assertThat(anchor.getStartOffset()).isEqualTo(start);
        assertThat(anchor.getEndOffset()).isEqualTo(end);
        assertThat(anchor.getSelectedText()).isEqualTo(selected);
        assertThat(anchor.getContextBefore()).isEqualTo("Trước đó, ");
        assertThat(anchor.getContextAfter()).isEqualTo(" cất bước lên núi.");
        assertThat(anchor.getCreatedAt()).isEqualTo(NOW);
        assertThat(anchor.isBlockAnchor()).isFalse();
        assertThat(anchor.isTextRangeAnchor()).isTrue();
    }

    @Test
    @DisplayName("Should reject null rootCommentId or null chapterId")
    void shouldRejectNullIdentityFields() {
        assertThatThrownBy(() -> ChapterCommentAnchor.createBlock(
                null, CHAPTER_ID, 1L, VALID_BLOCK_KEY_1, "Text", NOW
        )).isInstanceOf(NullPointerException.class).hasMessageContaining("Root comment ID cannot be null.");

        assertThatThrownBy(() -> ChapterCommentAnchor.createBlock(
                ROOT_COMMENT_ID, null, 1L, VALID_BLOCK_KEY_1, "Text", NOW
        )).isInstanceOf(NullPointerException.class).hasMessageContaining("Chapter ID cannot be null.");
    }

    @Test
    @DisplayName("Should reject invalid contentVersion (< 1)")
    void shouldRejectInvalidContentVersion() {
        assertThatThrownBy(() -> ChapterCommentAnchor.createBlock(
                ROOT_COMMENT_ID, CHAPTER_ID, 0L, VALID_BLOCK_KEY_1, "Text", NOW
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("content version must be >= 1");

        assertThatThrownBy(() -> ChapterCommentAnchor.createBlock(
                ROOT_COMMENT_ID, CHAPTER_ID, -5L, VALID_BLOCK_KEY_1, "Text", NOW
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("content version must be >= 1");
    }

    @Test
    @DisplayName("Should reject null or blank blockKey")
    void shouldRejectNullOrBlankBlockKey() {
        assertThatThrownBy(() -> ChapterCommentAnchor.createBlock(
                ROOT_COMMENT_ID, CHAPTER_ID, 1L, null, "Text", NOW
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Block key cannot be null.");

        assertThatThrownBy(() -> ChapterCommentAnchor.createBlock(
                ROOT_COMMENT_ID, CHAPTER_ID, 1L, "   ", "Text", NOW
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Block key cannot be blank.");
    }

    @Test
    @DisplayName("Should accept exact E1 production block-key format")
    void shouldAcceptValidProductionBlockKeyFormat() {
        assertThatCode(() -> ChapterCommentAnchor.createBlock(
                ROOT_COMMENT_ID, CHAPTER_ID, 1L, "blk-0123456789abcdef-1", "Text", NOW
        )).doesNotThrowAnyException();

        assertThatCode(() -> ChapterCommentAnchor.createBlock(
                ROOT_COMMENT_ID, CHAPTER_ID, 1L, "blk-fedcba9876543210-999", "Text", NOW
        )).doesNotThrowAnyException();

        assertThatCode(() -> ChapterCommentAnchor.createBlock(
                ROOT_COMMENT_ID, CHAPTER_ID, 1L, "blk-1111222233334444-12", "Text", NOW
        )).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should reject malformed or fake block-key formats")
    void shouldRejectMalformedOrFakeBlockKeyFormats() {
        // Short hash (< 16 hex characters)
        assertThatThrownBy(() -> ChapterCommentAnchor.createBlock(
                ROOT_COMMENT_ID, CHAPTER_ID, 1L, "blk-1111-1", "Text", NOW
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must follow E1 format");

        // Non-hex characters
        assertThatThrownBy(() -> ChapterCommentAnchor.createBlock(
                ROOT_COMMENT_ID, CHAPTER_ID, 1L, "blk-code1234567890a-1", "Text", NOW
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must follow E1 format");

        // Occurrence 0 is invalid (starts at 1)
        assertThatThrownBy(() -> ChapterCommentAnchor.createBlock(
                ROOT_COMMENT_ID, CHAPTER_ID, 1L, "blk-0123456789abcdef-0", "Text", NOW
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must follow E1 format");

        // Uppercase hex characters are invalid
        assertThatThrownBy(() -> ChapterCommentAnchor.createBlock(
                ROOT_COMMENT_ID, CHAPTER_ID, 1L, "blk-0123456789ABCDEF-1", "Text", NOW
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must follow E1 format");

        // Missing occurrence segment
        assertThatThrownBy(() -> ChapterCommentAnchor.createBlock(
                ROOT_COMMENT_ID, CHAPTER_ID, 1L, "blk-0123456789abcdef", "Text", NOW
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must follow E1 format");

        // Extra segments
        assertThatThrownBy(() -> ChapterCommentAnchor.createBlock(
                ROOT_COMMENT_ID, CHAPTER_ID, 1L, "blk-0123456789abcdef-1-extra", "Text", NOW
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must follow E1 format");

        // Missing prefix
        assertThatThrownBy(() -> ChapterCommentAnchor.createBlock(
                ROOT_COMMENT_ID, CHAPTER_ID, 1L, "0123456789abcdef-1", "Text", NOW
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must follow E1 format");

        // Whitespace around key
        assertThatThrownBy(() -> ChapterCommentAnchor.createBlock(
                ROOT_COMMENT_ID, CHAPTER_ID, 1L, " blk-0123456789abcdef-1 ", "Text", NOW
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must follow E1 format");
    }

    @Test
    @DisplayName("Should reject empty selectedText")
    void shouldRejectEmptySelectedText() {
        assertThatThrownBy(() -> ChapterCommentAnchor.createBlock(
                ROOT_COMMENT_ID, CHAPTER_ID, 1L, VALID_BLOCK_KEY_1, "", NOW
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Selected text cannot be empty.");
    }

    @Test
    @DisplayName("Should reject BLOCK anchor with non-null offsets or non-empty context")
    void shouldRejectIllegalBlockAnchorShape() {
        assertThatThrownBy(() -> ChapterCommentAnchor.rehydrate(
                ROOT_COMMENT_ID, CHAPTER_ID, 1L, VALID_BLOCK_KEY_1,
                ChapterCommentAnchorKind.BLOCK,
                0, null, "Text", "", "", NOW
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Offsets must be null for a BLOCK anchor.");

        assertThatThrownBy(() -> ChapterCommentAnchor.rehydrate(
                ROOT_COMMENT_ID, CHAPTER_ID, 1L, VALID_BLOCK_KEY_1,
                ChapterCommentAnchorKind.BLOCK,
                null, 10, "Text", "", "", NOW
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Offsets must be null for a BLOCK anchor.");

        assertThatThrownBy(() -> ChapterCommentAnchor.rehydrate(
                ROOT_COMMENT_ID, CHAPTER_ID, 1L, VALID_BLOCK_KEY_1,
                ChapterCommentAnchorKind.BLOCK,
                null, null, "Text", "context", "", NOW
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Surrounding context must be empty for a BLOCK anchor.");

        assertThatThrownBy(() -> ChapterCommentAnchor.rehydrate(
                ROOT_COMMENT_ID, CHAPTER_ID, 1L, VALID_BLOCK_KEY_1,
                ChapterCommentAnchorKind.BLOCK,
                null, null, "Text", "", "context", NOW
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Surrounding context must be empty for a BLOCK anchor.");
    }

    @Test
    @DisplayName("Should reject TEXT_RANGE anchor with invalid offsets")
    void shouldRejectIllegalTextRangeOffsets() {
        // null startOffset
        assertThatThrownBy(() -> ChapterCommentAnchor.rehydrate(
                ROOT_COMMENT_ID, CHAPTER_ID, 1L, VALID_BLOCK_KEY_1,
                ChapterCommentAnchorKind.TEXT_RANGE,
                null, 4, "Text", "", "", NOW
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("startOffset must be non-null");

        // negative startOffset
        assertThatThrownBy(() -> ChapterCommentAnchor.createTextRange(
                ROOT_COMMENT_ID, CHAPTER_ID, 1L, VALID_BLOCK_KEY_1,
                -1, 3, "Text", "", "", NOW
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("startOffset must be non-null and >= 0");

        // null endOffset
        assertThatThrownBy(() -> ChapterCommentAnchor.rehydrate(
                ROOT_COMMENT_ID, CHAPTER_ID, 1L, VALID_BLOCK_KEY_1,
                ChapterCommentAnchorKind.TEXT_RANGE,
                0, null, "Text", "", "", NOW
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("endOffset must be non-null");

        // endOffset == startOffset
        assertThatThrownBy(() -> ChapterCommentAnchor.createTextRange(
                ROOT_COMMENT_ID, CHAPTER_ID, 1L, VALID_BLOCK_KEY_1,
                5, 5, "Text", "", "", NOW
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("strictly greater than startOffset");

        // endOffset < startOffset
        assertThatThrownBy(() -> ChapterCommentAnchor.createTextRange(
                ROOT_COMMENT_ID, CHAPTER_ID, 1L, VALID_BLOCK_KEY_1,
                10, 5, "Text", "", "", NOW
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("strictly greater than startOffset");
    }

    @Test
    @DisplayName("Should accept matching selectedText length and offset range (endOffset - startOffset == selectedText.length())")
    void shouldAcceptMatchingSelectedTextLengthAndOffsetRange() {
        String selected = "Trần Bình An";
        int start = 10;
        int end = 10 + selected.length(); // 22

        assertThatCode(() -> ChapterCommentAnchor.createTextRange(
                ROOT_COMMENT_ID, CHAPTER_ID, 1L, VALID_BLOCK_KEY_1,
                start, end, selected, "", "", NOW
        )).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should reject mismatched selectedText length and offset range")
    void shouldRejectMismatchedSelectedTextLengthAndOffsetRange() {
        String selected = "Trần Bình An"; // length = 12

        // Range length is 15 (25 - 10), but text length is 12
        assertThatThrownBy(() -> ChapterCommentAnchor.createTextRange(
                ROOT_COMMENT_ID, CHAPTER_ID, 1L, VALID_BLOCK_KEY_1,
                10, 25, selected, "", "", NOW
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must match offset range");

        // Range length is 5 (15 - 10), but text length is 12
        assertThatThrownBy(() -> ChapterCommentAnchor.createTextRange(
                ROOT_COMMENT_ID, CHAPTER_ID, 1L, VALID_BLOCK_KEY_1,
                10, 15, selected, "", "", NOW
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must match offset range");
    }

    @Test
    @DisplayName("Should correctly handle supplementary characters with UTF-16 code units")
    void shouldHandleSupplementaryCharactersWithUtf16CodeUnits() {
        // "🐉" is 1 Unicode code point, but 2 UTF-16 code units (String.length() == 2)
        String emoji = "🐉";
        assertThat(emoji.length()).isEqualTo(2);

        // Matching range (7 - 5 = 2) accepted
        assertThatCode(() -> ChapterCommentAnchor.createTextRange(
                ROOT_COMMENT_ID, CHAPTER_ID, 1L, VALID_BLOCK_KEY_1,
                5, 7, emoji, "", "", NOW
        )).doesNotThrowAnyException();

        // Mismatched range (6 - 5 = 1) rejected
        assertThatThrownBy(() -> ChapterCommentAnchor.createTextRange(
                ROOT_COMMENT_ID, CHAPTER_ID, 1L, VALID_BLOCK_KEY_1,
                5, 6, emoji, "", "", NOW
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must match offset range");
    }

    @Test
    @DisplayName("Should accept context exactly up to MAX_CONTEXT_CODE_UNITS (64 code units)")
    void shouldAcceptContextUpToMaxContextCodeUnits() {
        String context64 = "a".repeat(ChapterCommentAnchor.MAX_CONTEXT_CODE_UNITS);

        ChapterCommentAnchor anchor = ChapterCommentAnchor.createTextRange(
                ROOT_COMMENT_ID, CHAPTER_ID, 1L, VALID_BLOCK_KEY_1,
                0, 4, "test", context64, context64, NOW
        );

        assertThat(anchor.getContextBefore()).isEqualTo(context64);
        assertThat(anchor.getContextAfter()).isEqualTo(context64);
    }

    @Test
    @DisplayName("Should reject context exceeding MAX_CONTEXT_CODE_UNITS (64 code units)")
    void shouldRejectContextExceedingMaxContextCodeUnits() {
        String context65 = "a".repeat(ChapterCommentAnchor.MAX_CONTEXT_CODE_UNITS + 1);

        assertThatThrownBy(() -> ChapterCommentAnchor.createTextRange(
                ROOT_COMMENT_ID, CHAPTER_ID, 1L, VALID_BLOCK_KEY_1,
                0, 4, "test", context65, "", NOW
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contextBefore length")
                .hasMessageContaining("exceeds maximum of 64 code units");

        assertThatThrownBy(() -> ChapterCommentAnchor.createTextRange(
                ROOT_COMMENT_ID, CHAPTER_ID, 1L, VALID_BLOCK_KEY_1,
                0, 4, "test", "", context65, NOW
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contextAfter length")
                .hasMessageContaining("exceeds maximum of 64 code units");
    }

    @Test
    @DisplayName("Should reject supplementary character context exceeding 64 UTF-16 code units")
    void shouldRejectSupplementaryCharacterContextExceedingMaxUnits() {
        // 32 emojis = 64 code units -> accepted
        String context32Emojis = "🐉".repeat(32);
        assertThat(context32Emojis.length()).isEqualTo(64);

        assertThatCode(() -> ChapterCommentAnchor.createTextRange(
                ROOT_COMMENT_ID, CHAPTER_ID, 1L, VALID_BLOCK_KEY_1,
                0, 4, "test", context32Emojis, context32Emojis, NOW
        )).doesNotThrowAnyException();

        // 33 emojis = 66 code units (> 64) -> rejected
        String context33Emojis = "🐉".repeat(33);
        assertThat(context33Emojis.length()).isEqualTo(66);

        assertThatThrownBy(() -> ChapterCommentAnchor.createTextRange(
                ROOT_COMMENT_ID, CHAPTER_ID, 1L, VALID_BLOCK_KEY_1,
                0, 4, "test", context33Emojis, "", NOW
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contextBefore length (66) exceeds maximum of 64 code units");
    }

    @Test
    @DisplayName("Should preserve exact selectedText and context without trimming")
    void shouldPreserveExactTextAndContextEvidence() {
        String textWithWhitespace = "  Indented code line;\n\t";
        String contextBeforeWithSpaces = "   prefix   ";
        String contextAfterWithSpaces = "   suffix   ";
        int start = 5;
        int end = start + textWithWhitespace.length();

        ChapterCommentAnchor anchor = ChapterCommentAnchor.createTextRange(
                ROOT_COMMENT_ID,
                CHAPTER_ID,
                1L,
                VALID_BLOCK_KEY_1,
                start,
                end,
                textWithWhitespace,
                contextBeforeWithSpaces,
                contextAfterWithSpaces,
                NOW
        );

        assertThat(anchor.getSelectedText()).isEqualTo(textWithWhitespace);
        assertThat(anchor.getContextBefore()).isEqualTo(contextBeforeWithSpaces);
        assertThat(anchor.getContextAfter()).isEqualTo(contextAfterWithSpaces);
    }
}
