package com.universe.interaction.application.mutation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CommentLockOrder Unit Tests")
class CommentLockOrderTest {

    @Test
    @DisplayName("Should order distinct UUIDs lexicographically by string representation ASC")
    void shouldOrderDistinctUuidsAscending() {
        UUID id1 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID id2 = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        List<UUID> order1 = CommentLockOrder.inLockOrder(id1, id2);
        assertThat(order1).containsExactly(id1, id2);

        List<UUID> order2 = CommentLockOrder.inLockOrder(id2, id1);
        assertThat(order2).containsExactly(id1, id2);
    }

    @Test
    @DisplayName("Should return single element when both IDs are identical")
    void shouldReturnSingleElementWhenIdentical() {
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        List<UUID> order = CommentLockOrder.inLockOrder(id, id);
        assertThat(order).containsExactly(id);
    }

    @Test
    @DisplayName("Should reject null inputs")
    void shouldRejectNullInputs() {
        UUID valid = UUID.randomUUID();

        assertThatThrownBy(() -> CommentLockOrder.inLockOrder(null, valid))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Comment ID 1 cannot be null");

        assertThatThrownBy(() -> CommentLockOrder.inLockOrder(valid, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Comment ID 2 cannot be null");
    }
}
