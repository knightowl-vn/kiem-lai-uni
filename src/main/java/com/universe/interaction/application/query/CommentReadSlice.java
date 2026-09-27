package com.universe.interaction.application.query;

import java.util.List;
import java.util.Objects;

/**
 * Framework-free immutable slice of {@link CommentReadItem} root comments for zero-based pagination.
 */
public record CommentReadSlice(
        List<CommentReadItem> items,
        int page,
        int size,
        boolean hasNext
) {

    public CommentReadSlice {
        if (page < 0) {
            throw new IllegalArgumentException("Page index cannot be negative: " + page);
        }
        if (size <= 0) {
            throw new IllegalArgumentException("Page size must be greater than zero: " + size);
        }
        Objects.requireNonNull(items, "Items cannot be null.");
        items = List.copyOf(items);
    }

    public List<CommentReadItem> getItems() {
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
