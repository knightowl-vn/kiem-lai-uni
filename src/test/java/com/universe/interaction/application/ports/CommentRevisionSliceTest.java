package com.universe.interaction.application.ports;

import com.universe.interaction.domain.CommentRevision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CommentRevisionSlice Unit Tests")
class CommentRevisionSliceTest {

    private CommentRevision createSampleRevision(int revisionNumber) {
        return new CommentRevision(
                UUID.randomUUID(),
                UUID.randomUUID(),
                revisionNumber,
                "Sample body " + revisionNumber,
                Instant.parse("2026-09-18T10:00:00Z")
        );
    }

    @Test
    @DisplayName("Should create CommentRevisionSlice with valid parameters and verify accessors")
    void shouldCreateCommentRevisionSliceSuccessfully() {
        CommentRevision revision = createSampleRevision(1);
        List<CommentRevision> items = List.of(revision);

        CommentRevisionSlice slice = new CommentRevisionSlice(items, 0, 10, true);

        assertThat(slice.items()).containsExactly(revision);
        assertThat(slice.getItems()).containsExactly(revision);
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
        CommentRevision rev1 = createSampleRevision(1);
        CommentRevision rev2 = createSampleRevision(2);
        List<CommentRevision> mutableList = new ArrayList<>();
        mutableList.add(rev1);

        CommentRevisionSlice slice = new CommentRevisionSlice(mutableList, 0, 5, false);

        // Mutating source list should not affect slice
        mutableList.add(rev2);
        assertThat(slice.items()).hasSize(1).containsExactly(rev1);

        // Slice items should be unmodifiable
        assertThatThrownBy(() -> slice.items().add(rev2))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Should reject negative page index")
    void shouldRejectNegativePageIndex() {
        assertThatThrownBy(() -> new CommentRevisionSlice(List.of(), -1, 10, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page index cannot be negative: -1");
    }

    @Test
    @DisplayName("Should reject non-positive page size")
    void shouldRejectNonPositivePageSize() {
        assertThatThrownBy(() -> new CommentRevisionSlice(List.of(), 0, 0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page size must be greater than zero: 0");

        assertThatThrownBy(() -> new CommentRevisionSlice(List.of(), 0, -5, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page size must be greater than zero: -5");
    }

    @Test
    @DisplayName("Should reject null items list")
    void shouldRejectNullItemsList() {
        assertThatThrownBy(() -> new CommentRevisionSlice(null, 0, 10, false))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Items cannot be null.");
    }
}
