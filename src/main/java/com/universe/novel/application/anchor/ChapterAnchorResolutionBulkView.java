package com.universe.novel.application.anchor;

import com.universe.novel.domain.anchor.ChapterCommentAnchorResolutionStatus;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Bulk resolution view for all anchors of a chapter against the current Reader content snapshot.
 *
 * @param resolutions immutable list of minimal resolution rows
 * @param orderedBlockKeys current Reader block keys in document order
 */
public record ChapterAnchorResolutionBulkView(
        List<ResolutionRow> resolutions,
        List<String> orderedBlockKeys
) {
    public ChapterAnchorResolutionBulkView {
        resolutions = resolutions != null ? List.copyOf(resolutions) : List.of();
        orderedBlockKeys = orderedBlockKeys != null ? List.copyOf(orderedBlockKeys) : List.of();
    }

    /**
     * Minimal projection of an anchor resolution for indicator read model composition.
     *
     * @param rootCommentId scalar UUID of the root discussion comment
     * @param status resolution status (CURRENT, RELOCATED, STALE)
     * @param resolvedBlockKey current block key if resolved, or null if stale
     */
    public record ResolutionRow(
            UUID rootCommentId,
            ChapterCommentAnchorResolutionStatus status,
            String resolvedBlockKey
    ) {
        public ResolutionRow {
            Objects.requireNonNull(rootCommentId, "rootCommentId cannot be null");
            Objects.requireNonNull(status, "status cannot be null");
        }
    }
}
