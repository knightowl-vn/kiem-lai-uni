package com.universe.interaction.infrastructure.persistence;

import com.universe.interaction.contracts.dto.authored.AuthoredCommentItemDTO;
import com.universe.interaction.contracts.dto.authored.AuthoredCommentPageDTO;
import com.universe.interaction.domain.CommentTargetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserAuthoredCommentsQueryPersistenceAdapter Unit Tests")
class UserAuthoredCommentsQueryPersistenceAdapterTest {

    private static final UUID AUTHOR_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID COMMENT_1_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID COMMENT_2_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID TARGET_1_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID TARGET_2_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");

    @Mock
    private SpringDataCommentRepository repository;

    private UserAuthoredCommentsQueryPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new UserAuthoredCommentsQueryPersistenceAdapter(repository);
    }

    @Test
    @DisplayName("Maps CommentJpaEntity to AuthoredCommentItemDTO preserving root/reply and metadata")
    void shouldMapEntitiesToDTOsCorrectly() {
        CommentJpaEntity entity1 = new CommentJpaEntity(
                COMMENT_1_ID.toString(),
                "NOVEL_CHAPTER",
                TARGET_1_ID.toString(),
                AUTHOR_ID.toString(),
                null,
                null,
                "Bình luận gốc trong Novel",
                "ACTIVE",
                NOW,
                NOW,
                null
        );

        CommentJpaEntity entity2 = new CommentJpaEntity(
                COMMENT_2_ID.toString(),
                "WIKI_ARTICLE",
                TARGET_2_ID.toString(),
                AUTHOR_ID.toString(),
                COMMENT_1_ID.toString(),
                COMMENT_1_ID.toString(),
                "Phản hồi trong Wiki",
                "ACTIVE",
                NOW.minusSeconds(60),
                NOW.minusSeconds(60),
                null
        );

        PageImpl<CommentJpaEntity> page = new PageImpl<>(
                List.of(entity1, entity2),
                PageRequest.of(0, 20),
                2
        );

        when(repository.findAuthoredComments(eq(AUTHOR_ID.toString()), any(), any(Pageable.class)))
                .thenReturn(page);

        AuthoredCommentPageDTO result = adapter.findAuthoredComments(
                AUTHOR_ID,
                Set.of(CommentTargetType.NOVEL_CHAPTER, CommentTargetType.WIKI_ARTICLE),
                0,
                20
        );

        assertThat(result.items()).hasSize(2);
        assertThat(result.totalElements()).isEqualTo(2);

        // 1. Root comment assertion
        AuthoredCommentItemDTO item1 = result.items().get(0);
        assertThat(item1.commentId()).isEqualTo(COMMENT_1_ID);
        assertThat(item1.targetType()).isEqualTo(CommentTargetType.NOVEL_CHAPTER);
        assertThat(item1.targetId()).isEqualTo(TARGET_1_ID);
        assertThat(item1.body()).isEqualTo("Bình luận gốc trong Novel");
        assertThat(item1.parentCommentId()).isNull();
        assertThat(item1.threadRootCommentId()).isNull();
        assertThat(item1.isRoot()).isTrue();
        assertThat(item1.isReply()).isFalse();

        // 2. Reply comment assertion
        AuthoredCommentItemDTO item2 = result.items().get(1);
        assertThat(item2.commentId()).isEqualTo(COMMENT_2_ID);
        assertThat(item2.targetType()).isEqualTo(CommentTargetType.WIKI_ARTICLE);
        assertThat(item2.targetId()).isEqualTo(TARGET_2_ID);
        assertThat(item2.body()).isEqualTo("Phản hồi trong Wiki");
        assertThat(item2.parentCommentId()).isEqualTo(COMMENT_1_ID);
        assertThat(item2.threadRootCommentId()).isEqualTo(COMMENT_1_ID);
        assertThat(item2.isRoot()).isFalse();
        assertThat(item2.isReply()).isTrue();
    }

    @Test
    @DisplayName("Returns empty page without querying repository when authorUserId is null or targetTypes is empty")
    void shouldReturnEmptyPageWhenParamsInvalid() {
        AuthoredCommentPageDTO result1 = adapter.findAuthoredComments(null, Set.of(CommentTargetType.NOVEL_CHAPTER), 0, 20);
        assertThat(result1.items()).isEmpty();
        assertThat(result1.totalElements()).isEqualTo(0);

        AuthoredCommentPageDTO result2 = adapter.findAuthoredComments(AUTHOR_ID, Set.of(), 0, 20);
        assertThat(result2.items()).isEmpty();
        assertThat(result2.totalElements()).isEqualTo(0);

        verify(repository, never()).findAuthoredComments(any(), any(), any());
    }
}
