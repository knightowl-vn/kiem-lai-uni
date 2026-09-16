package com.universe.novel.infrastructure.persistence.anchor;

import com.universe.novel.domain.anchor.ChapterCommentAnchor;
import com.universe.novel.domain.anchor.ChapterCommentAnchorKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChapterCommentAnchorPersistenceAdapter Unit Tests")
class ChapterCommentAnchorPersistenceAdapterTest {

    @Mock
    private SpringDataChapterCommentAnchorJpaRepository repository;

    private ChapterCommentAnchorPersistenceMapper mapper;
    private ChapterCommentAnchorPersistenceAdapter adapter;

    private static final UUID ROOT_COMMENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CHAPTER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant NOW = Instant.parse("2026-09-16T12:00:00Z");

    @BeforeEach
    void setUp() {
        mapper = new ChapterCommentAnchorPersistenceMapper();
        adapter = new ChapterCommentAnchorPersistenceAdapter(repository, mapper);
    }

    @Test
    @DisplayName("save: converts domain to entity, saves via repository, and returns mapped domain")
    void shouldSaveAndReturnDomain() {
        ChapterCommentAnchor domain = ChapterCommentAnchor.createBlock(
                ROOT_COMMENT_ID,
                CHAPTER_ID,
                1L,
                "blk-0123456789abcdef-1",
                "Full block text",
                NOW
        );

        ChapterCommentAnchorJpaEntity savedEntity = mapper.toJpaEntity(domain);
        when(repository.saveAndFlush(any(ChapterCommentAnchorJpaEntity.class))).thenReturn(savedEntity);

        ChapterCommentAnchor result = adapter.save(domain);

        assertThat(result).isNotNull();
        assertThat(result.getRootCommentId()).isEqualTo(ROOT_COMMENT_ID);
        assertThat(result.getChapterId()).isEqualTo(CHAPTER_ID);
        assertThat(result.getContentVersion()).isEqualTo(1L);
        assertThat(result.getBlockKey()).isEqualTo("blk-0123456789abcdef-1");
        assertThat(result.getAnchorKind()).isEqualTo(ChapterCommentAnchorKind.BLOCK);

        verify(repository).saveAndFlush(any(ChapterCommentAnchorJpaEntity.class));
    }

    @Test
    @DisplayName("save: throws IllegalArgumentException when anchor is null")
    void shouldThrowWhenSavingNull() {
        assertThatThrownBy(() -> adapter.save(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ChapterCommentAnchor cannot be null.");
    }

    @Test
    @DisplayName("findByRootCommentId: returns present domain when entity exists in repository")
    void shouldReturnPresentWhenAnchorExists() {
        ChapterCommentAnchor domain = ChapterCommentAnchor.createTextRange(
                ROOT_COMMENT_ID,
                CHAPTER_ID,
                2L,
                "blk-abcdef0123456789-2",
                0,
                8,
                "Sub text",
                "",
                " context",
                NOW
        );
        ChapterCommentAnchorJpaEntity entity = mapper.toJpaEntity(domain);
        when(repository.findById(ROOT_COMMENT_ID.toString())).thenReturn(Optional.of(entity));

        Optional<ChapterCommentAnchor> result = adapter.findByRootCommentId(ROOT_COMMENT_ID);

        assertThat(result).isPresent();
        assertThat(result.get().getRootCommentId()).isEqualTo(ROOT_COMMENT_ID);
        assertThat(result.get().getAnchorKind()).isEqualTo(ChapterCommentAnchorKind.TEXT_RANGE);
        assertThat(result.get().getStartOffset()).isEqualTo(0);
        assertThat(result.get().getEndOffset()).isEqualTo(8);
        verify(repository).findById(ROOT_COMMENT_ID.toString());
    }

    @Test
    @DisplayName("findByRootCommentId: returns Optional.empty when entity is missing")
    void shouldReturnEmptyWhenAnchorMissing() {
        when(repository.findById(ROOT_COMMENT_ID.toString())).thenReturn(Optional.empty());

        Optional<ChapterCommentAnchor> result = adapter.findByRootCommentId(ROOT_COMMENT_ID);

        assertThat(result).isEmpty();
        verify(repository).findById(ROOT_COMMENT_ID.toString());
    }

    @Test
    @DisplayName("findByRootCommentId: throws IllegalArgumentException when rootCommentId is null")
    void shouldThrowWhenRootCommentIdIsNull() {
        assertThatThrownBy(() -> adapter.findByRootCommentId(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Root comment ID cannot be null.");
    }
}
