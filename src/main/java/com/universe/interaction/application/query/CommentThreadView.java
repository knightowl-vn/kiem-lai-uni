package com.universe.interaction.application.query;

import java.util.List;
import java.util.Objects;

/**
 * Immutable view of a single discussion thread consisting of an active root and resolved visible flat replies.
 *
 * <p>Preserves flat visual presentation:
 * <ul>
 *   <li>Root at visual level 0;</li>
 *   <li>All replies at visual level 1;</li>
 *   <li>Defensively unmodifiable replies list.</li>
 * </ul>
 */
public record CommentThreadView(
        CommentReadItem root,
        List<CommentReadItem> replies
) {

    public CommentThreadView {
        Objects.requireNonNull(root, "Root comment cannot be null.");
        Objects.requireNonNull(replies, "Replies cannot be null.");
        replies = List.copyOf(replies);
    }

    public CommentReadItem getRoot() {
        return root();
    }

    public List<CommentReadItem> getReplies() {
        return replies();
    }
}
