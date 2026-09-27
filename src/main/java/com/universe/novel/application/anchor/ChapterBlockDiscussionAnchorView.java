package com.universe.novel.application.anchor;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable view representing the Novel-owned canonical block context and resolved discussion
 * root comment IDs for a specific Reader block.
 *
 * @param chapterId scalar UUID of the chapter
 * @param contentVersion current chapter content version
 * @param blockKey canonical Reader blockKey
 * @param canonicalText full current canonical block text
 * @param rootCommentIds distinct list of root comment IDs resolved to this block
 */
public record ChapterBlockDiscussionAnchorView(
        UUID chapterId,
        long contentVersion,
        String blockKey,
        String canonicalText,
        List<UUID> rootCommentIds
) {
    public ChapterBlockDiscussionAnchorView {
        Objects.requireNonNull(chapterId, "chapterId cannot be null");
        Objects.requireNonNull(blockKey, "blockKey cannot be null");
        Objects.requireNonNull(canonicalText, "canonicalText cannot be null");
        rootCommentIds = rootCommentIds == null ? List.of() : List.copyOf(rootCommentIds);
    }
}
