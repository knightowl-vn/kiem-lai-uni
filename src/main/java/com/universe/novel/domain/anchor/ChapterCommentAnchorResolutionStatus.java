package com.universe.novel.domain.anchor;

/**
 * Runtime resolution status of an immutable {@link ChapterCommentAnchor}
 * evaluated against the current Chapter Reader content.
 */
public enum ChapterCommentAnchorResolutionStatus {

    /**
     * Anchor is on the current content version and original coordinates resolve exactly.
     */
    CURRENT,

    /**
     * Anchor was created on an earlier content version and has been deterministically
     * resolved to a safe current location.
     */
    RELOCATED,

    /**
     * Anchor evidence cannot be resolved safely to a unique current location.
     */
    STALE
}
