package com.universe.novel.domain.anchor;

/**
 * Anchor kinds supported for Novel Reader inline comments.
 *
 * <p>Directly reflects the canonical E1 Reader anchor contract:
 * <ul>
 *   <li>{@link #BLOCK}: Anchors a discussion thread to an entire canonical Reader semantic block;</li>
 *   <li>{@link #TEXT_RANGE}: Anchors a discussion thread to a character range within a single canonical block.</li>
 * </ul>
 */
public enum ChapterCommentAnchorKind {
    BLOCK,
    TEXT_RANGE
}
