package com.universe.interaction.application.ports;

import com.universe.interaction.domain.CommentRevision;

import java.util.List;
import java.util.Objects;

/**
 * Immutable slice of domain {@link CommentRevision} entities for zero-based pagination.
 *
 * <p>Preserves clean architecture boundaries:
 * <ul>
 *   <li>Infrastructure-free: zero dependency on Spring Data, JPA, or ORM types;</li>
 *   <li>Immutable: items are defensively copied into an unmodifiable list;</li>
 *   <li>Offset-based slice semantics: {@code hasNext} indicates presence of subsequent page without issuing COUNT(*) queries.</li>
 * </ul>
 */
public record CommentRevisionSlice(
        List<CommentRevision> items,
        int page,
        int size,
        boolean hasNext
) {

    public CommentRevisionSlice {
        if (page < 0) {
            throw new IllegalArgumentException("Page index cannot be negative: " + page);
        }
        if (size <= 0) {
            throw new IllegalArgumentException("Page size must be greater than zero: " + size);
        }
        Objects.requireNonNull(items, "Items cannot be null.");
        items = List.copyOf(items);
    }

    public List<CommentRevision> getItems() {
        return items();
    }

    public int getPage() {
        return page();
    }

    public int getSize() {
        return size();
    }

    public boolean isHasNext() {
        return hasNext();
    }
}
