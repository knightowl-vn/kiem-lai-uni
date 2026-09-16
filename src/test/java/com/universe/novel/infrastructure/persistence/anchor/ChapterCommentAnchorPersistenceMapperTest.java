package com.universe.novel.infrastructure.persistence.anchor;

import com.universe.novel.domain.anchor.ChapterCommentAnchor;
import com.universe.novel.domain.anchor.ChapterCommentAnchorKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ChapterCommentAnchorPersistenceMapper Unit Tests")
class ChapterCommentAnchorPersistenceMapperTest {

    private ChapterCommentAnchorPersistenceMapper mapper;

    private static final UUID ROOT_COMMENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CHAPTER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant CREATED_AT = Instant.parse("2026-09-16T12:00:00.123456Z");

    @BeforeEach
    void setUp() {
        mapper = new ChapterCommentAnchorPersistenceMapper();
    }

    @Test
    @DisplayName("Should map BLOCK anchor round-trip preserving all fields exactly")
    void shouldMapBlockAnchorRoundTrip() {
        ChapterCommentAnchor domain = ChapterCommentAnchor.createBlock(
                ROOT_COMMENT_ID,
                CHAPTER_ID,
                5L,
                "blk-0123456789abcdef-1",
                "Đoạn văn hoàn chỉnh của khối.",
                CREATED_AT
        );

        ChapterCommentAnchorJpaEntity entity = mapper.toJpaEntity(domain);
        assertThat(entity).isNotNull();
        assertThat(entity.getRootCommentId()).isEqualTo(ROOT_COMMENT_ID.toString());
        assertThat(entity.getChapterId()).isEqualTo(CHAPTER_ID.toString());
        assertThat(entity.getContentVersion()).isEqualTo(5L);
        assertThat(entity.getBlockKey()).isEqualTo("blk-0123456789abcdef-1");
        assertThat(entity.getAnchorKind()).isEqualTo(ChapterCommentAnchorKind.BLOCK);
        assertThat(entity.getStartOffset()).isNull();
        assertThat(entity.getEndOffset()).isNull();
        assertThat(entity.getSelectedText()).isEqualTo("Đoạn văn hoàn chỉnh của khối.");
        assertThat(entity.getContextBefore()).isEmpty();
        assertThat(entity.getContextAfter()).isEmpty();
        assertThat(entity.getCreatedAt()).isEqualTo(CREATED_AT);

        ChapterCommentAnchor roundTripped = mapper.toDomain(entity);
        assertThat(roundTripped).isNotNull();
        assertThat(roundTripped.getRootCommentId()).isEqualTo(ROOT_COMMENT_ID);
        assertThat(roundTripped.getChapterId()).isEqualTo(CHAPTER_ID);
        assertThat(roundTripped.getContentVersion()).isEqualTo(5L);
        assertThat(roundTripped.getBlockKey()).isEqualTo("blk-0123456789abcdef-1");
        assertThat(roundTripped.getAnchorKind()).isEqualTo(ChapterCommentAnchorKind.BLOCK);
        assertThat(roundTripped.getStartOffset()).isNull();
        assertThat(roundTripped.getEndOffset()).isNull();
        assertThat(roundTripped.getSelectedText()).isEqualTo("Đoạn văn hoàn chỉnh của khối.");
        assertThat(roundTripped.getContextBefore()).isEmpty();
        assertThat(roundTripped.getContextAfter()).isEmpty();
        assertThat(roundTripped.getCreatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("Should map TEXT_RANGE anchor round-trip preserving all fields exactly")
    void shouldMapTextRangeAnchorRoundTrip() {
        String selected = "cụm từ được chọn";
        String before = "  ngữ cảnh trước   ";
        String after = "   ngữ cảnh sau  ";

        ChapterCommentAnchor domain = ChapterCommentAnchor.createTextRange(
                ROOT_COMMENT_ID,
                CHAPTER_ID,
                3L,
                "blk-abcdef0123456789-2",
                12,
                28,
                selected,
                before,
                after,
                CREATED_AT
        );

        ChapterCommentAnchorJpaEntity entity = mapper.toJpaEntity(domain);
        assertThat(entity).isNotNull();
        assertThat(entity.getRootCommentId()).isEqualTo(ROOT_COMMENT_ID.toString());
        assertThat(entity.getChapterId()).isEqualTo(CHAPTER_ID.toString());
        assertThat(entity.getContentVersion()).isEqualTo(3L);
        assertThat(entity.getBlockKey()).isEqualTo("blk-abcdef0123456789-2");
        assertThat(entity.getAnchorKind()).isEqualTo(ChapterCommentAnchorKind.TEXT_RANGE);
        assertThat(entity.getStartOffset()).isEqualTo(12);
        assertThat(entity.getEndOffset()).isEqualTo(28);
        assertThat(entity.getSelectedText()).isEqualTo(selected);
        assertThat(entity.getContextBefore()).isEqualTo(before);
        assertThat(entity.getContextAfter()).isEqualTo(after);
        assertThat(entity.getCreatedAt()).isEqualTo(CREATED_AT);

        ChapterCommentAnchor roundTripped = mapper.toDomain(entity);
        assertThat(roundTripped).isNotNull();
        assertThat(roundTripped.getRootCommentId()).isEqualTo(ROOT_COMMENT_ID);
        assertThat(roundTripped.getChapterId()).isEqualTo(CHAPTER_ID);
        assertThat(roundTripped.getContentVersion()).isEqualTo(3L);
        assertThat(roundTripped.getBlockKey()).isEqualTo("blk-abcdef0123456789-2");
        assertThat(roundTripped.getAnchorKind()).isEqualTo(ChapterCommentAnchorKind.TEXT_RANGE);
        assertThat(roundTripped.getStartOffset()).isEqualTo(12);
        assertThat(roundTripped.getEndOffset()).isEqualTo(28);
        assertThat(roundTripped.getSelectedText()).isEqualTo(selected);
        assertThat(roundTripped.getContextBefore()).isEqualTo(before);
        assertThat(roundTripped.getContextAfter()).isEqualTo(after);
        assertThat(roundTripped.getCreatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("Should return null when input is null")
    void shouldHandleNullInputs() {
        assertThat(mapper.toJpaEntity(null)).isNull();
        assertThat(mapper.toDomain(null)).isNull();
    }
}
