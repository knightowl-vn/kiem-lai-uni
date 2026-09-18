package com.universe.novel.application.anchor;

import com.universe.novel.domain.anchor.ChapterCommentAnchorResolutionStatus;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable view representing the resolved anchor state for a chapter discussion root comment.
 *
 * @param rootCommentId scalar UUID of the root discussion comment, cannot be null
 * @param status resolution status (CURRENT, RELOCATED, or STALE), cannot be null
 * @param resolvedBlockKey navigable reader block key in current snapshot, or null if STALE
 * @param passageText canonical block text for CURRENT/RELOCATED, or anchor selected text if STALE
 */
public record ResolvedChapterCommentAnchorView(
        UUID rootCommentId,
        ChapterCommentAnchorResolutionStatus status,
        String resolvedBlockKey,
        String passageText
) {
    public ResolvedChapterCommentAnchorView {
        Objects.requireNonNull(rootCommentId, "rootCommentId cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
    }
}
