package com.universe.interaction.application.ports;

import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CommentSlice Unit Tests")
class CommentSliceTest {

    private Comment createSampleComment(UUID id) {
        CommentTarget target = CommentTarget.novelChapter(UUID.randomUUID());
        return Comment.createRoot(
                id,
                target,
                UUID.randomUUID(),
                "Sample comment body",
                Instant.parse("2026-09-16T10:00:00Z")
        );
    }

    @Test
    @DisplayName("Should create CommentSlice with valid parameters and verify accessors")
    void shouldCreateCommentSliceSuccessfully() {
        Comment comment = createSampleComment(UUID.randomUUID());
        List<Comment> items = List.of(comment);

        CommentSlice slice = new CommentSlice(items, 0, 10, true);

        assertThat(slice.items()).containsExactly(comment);
        assertThat(slice.getItems()).containsExactly(comment);
        assertThat(slice.page()).isEqualTo(0);
        assertThat(slice.getPage()).isEqualTo(0);
        assertThat(slice.size()).isEqualTo(10);
        assertThat(slice.getSize()).isEqualTo(10);
        assertThat(slice.hasNext()).isTrue();
        assertThat(slice.isHasNext()).isTrue();
    }

    @Test
    @DisplayName("Should defensively copy items and return unmodifiable list")
    void shouldDefensivelyCopyItems() {
        Comment comment1 = createSampleComment(UUID.randomUUID());
        Comment comment2 = createSampleComment(UUID.randomUUID());
        List<Comment> mutableList = new ArrayList<>();
        mutableList.add(comment1);

        CommentSlice slice = new CommentSlice(mutableList, 0, 5, false);

        // Mutating source list should not affect slice
        mutableList.add(comment2);
        assertThat(slice.items()).hasSize(1);
        assertThat(slice.items()).containsExactly(comment1);

        // Returned list must be unmodifiable
        assertThatThrownBy(() -> slice.items().add(comment2))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Should reject negative page index")
    void shouldRejectNegativePageIndex() {
        List<Comment> items = List.of();

        assertThatThrownBy(() -> new CommentSlice(items, -1, 10, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page index cannot be negative");
    }

    @Test
    @DisplayName("Should reject zero or negative page size")
    void shouldRejectZeroOrNegativePageSize() {
        List<Comment> items = List.of();

        assertThatThrownBy(() -> new CommentSlice(items, 0, 0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page size must be greater than zero");

        assertThatThrownBy(() -> new CommentSlice(items, 0, -5, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page size must be greater than zero");
    }

    @Test
    @DisplayName("Should reject null items list")
    void shouldRejectNullItems() {
        assertThatThrownBy(() -> new CommentSlice(null, 0, 10, false))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Items cannot be null");
    }
}
