package com.universe.interaction.application.query;

import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.application.ports.CommentSlice;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentSortMode;
import com.universe.interaction.domain.CommentTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ListCommentRootsUseCase Unit Tests")
class ListCommentRootsUseCaseTest {

    @Mock
    private CommentRepositoryPort commentRepositoryPort;

    private ListCommentRootsUseCase useCase;

    private static final CommentTarget TARGET = CommentTarget.novelChapter(UUID.fromString("99999999-9999-9999-9999-999999999999"));
    private static final UUID AUTHOR = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final Instant T0 = Instant.parse("2026-09-16T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-16T10:05:00Z");

    @BeforeEach
    void setUp() {
        useCase = new ListCommentRootsUseCase(commentRepositoryPort);
    }

    @Test
    @DisplayName("Should pass target, page, size to repository and map roots to immutable read items preserving order and slice metadata")
    void shouldMapRootsIntoReadItemsPreservingOrderingAndMetadata() {
        UUID root1Id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID root2Id = UUID.fromString("22222222-2222-2222-2222-222222222222");

        Comment root1 = Comment.createRoot(root1Id, TARGET, AUTHOR, "Newer root", T1);
        Comment root2 = Comment.createRoot(root2Id, TARGET, AUTHOR, "Older root", T0);

        CommentSlice domainSlice = new CommentSlice(List.of(root1, root2), 0, 10, true);

        when(commentRepositoryPort.findActiveRoots(TARGET, CommentSortMode.FEATURED, 0, 10)).thenReturn(domainSlice);

        CommentReadSlice readSlice = useCase.execute(TARGET, CommentSortMode.FEATURED, 0, 10);

        assertThat(readSlice).isNotNull();
        assertThat(readSlice.getPage()).isEqualTo(0);
        assertThat(readSlice.getSize()).isEqualTo(10);
        assertThat(readSlice.isHasNext()).isTrue();
        assertThat(readSlice.getItems()).hasSize(2);

        CommentReadItem item1 = readSlice.getItems().get(0);
        assertThat(item1.getId()).isEqualTo(root1Id);
        assertThat(item1.getBody()).isEqualTo("Newer root");
        assertThat(item1.isRoot()).isTrue();
        assertThat(item1.isTombstone()).isFalse();
        assertThat(item1.getParentCommentId()).isNull();
        assertThat(item1.getReplyToAuthorUserId()).isNull();

        CommentReadItem item2 = readSlice.getItems().get(1);
        assertThat(item2.getId()).isEqualTo(root2Id);
        assertThat(item2.getBody()).isEqualTo("Older root");
        assertThat(item2.isRoot()).isTrue();
        assertThat(item2.isTombstone()).isFalse();

        // Verify ordering preserved
        assertThat(readSlice.getItems()).extracting(CommentReadItem::getId)
                .containsExactly(root1Id, root2Id);

        verify(commentRepositoryPort).findActiveRoots(TARGET, CommentSortMode.FEATURED, 0, 10);
        // Verify no N+1 reply queries issued
        verify(commentRepositoryPort, never()).findThreadReplies(any());
        verify(commentRepositoryPort, never()).findById(any());
        verify(commentRepositoryPort, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("Should return empty read slice when repository returns empty slice")
    void shouldReturnEmptySliceWhenNoRootsExist() {
        CommentSlice emptyDomainSlice = new CommentSlice(List.of(), 1, 10, false);

        when(commentRepositoryPort.findActiveRoots(TARGET, CommentSortMode.FEATURED, 1, 10)).thenReturn(emptyDomainSlice);

        CommentReadSlice readSlice = useCase.execute(TARGET, CommentSortMode.FEATURED, 1, 10);

        assertThat(readSlice).isNotNull();
        assertThat(readSlice.getPage()).isEqualTo(1);
        assertThat(readSlice.getSize()).isEqualTo(10);
        assertThat(readSlice.isHasNext()).isFalse();
        assertThat(readSlice.getItems()).isEmpty();
        verify(commentRepositoryPort).findActiveRoots(TARGET, CommentSortMode.FEATURED, 1, 10);
    }

    @Test
    @DisplayName("Should validate pagination input arguments")
    void shouldValidateInputArguments() {
        assertThatThrownBy(() -> useCase.execute(null, CommentSortMode.FEATURED, 0, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CommentTarget cannot be null.");

        assertThatThrownBy(() -> useCase.execute(TARGET, CommentSortMode.FEATURED, -1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page index cannot be negative");

        assertThatThrownBy(() -> useCase.execute(TARGET, CommentSortMode.FEATURED, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page size must be greater than zero");

        assertThatThrownBy(() -> useCase.execute(TARGET, CommentSortMode.FEATURED, 0, -5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page size must be greater than zero");
    }

    @Test
    @DisplayName("Should pass explicit sort mode to repository")
    void shouldPassExplicitSortModeToRepository() {
        CommentSlice domainSlice = new CommentSlice(List.of(), 0, 10, false);
        when(commentRepositoryPort.findActiveRoots(TARGET, CommentSortMode.NEWEST, 0, 10)).thenReturn(domainSlice);

        CommentReadSlice readSlice = useCase.execute(TARGET, CommentSortMode.NEWEST, 0, 10);

        assertThat(readSlice).isNotNull();
        verify(commentRepositoryPort).findActiveRoots(TARGET, CommentSortMode.NEWEST, 0, 10);
    }

    @Test
    @DisplayName("Should default to FEATURED sort mode when sort mode is null")
    void shouldDefaultToFeaturedSortWhenNull() {
        CommentSlice domainSlice = new CommentSlice(List.of(), 0, 10, false);
        when(commentRepositoryPort.findActiveRoots(TARGET, CommentSortMode.FEATURED, 0, 10)).thenReturn(domainSlice);

        CommentReadSlice readSlice = useCase.execute(TARGET, null, 0, 10);

        assertThat(readSlice).isNotNull();
        verify(commentRepositoryPort).findActiveRoots(TARGET, CommentSortMode.FEATURED, 0, 10);
    }
}
