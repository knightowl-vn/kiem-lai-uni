package com.universe.interaction.entry.dto;

import java.util.Objects;

/**
 * Immutable response DTO representing an inline discussion indicator for a Reader block.
 *
 * @param blockKey stable fingerprint of the Reader block
 * @param threadCount number of visible anchored root discussion threads on this block (>= 1)
 * @param commentCount total visible active comments (roots + visible active replies) on this block (>= 1)
 */
public record ChapterCommentBlockIndicatorDTO(
        String blockKey,
        int threadCount,
        int commentCount
) {
    public ChapterCommentBlockIndicatorDTO {
        Objects.requireNonNull(blockKey, "blockKey cannot be null");
        if (threadCount <= 0) {
            throw new IllegalArgumentException("threadCount must be positive: " + threadCount);
        }
        if (commentCount <= 0) {
            throw new IllegalArgumentException("commentCount must be positive: " + commentCount);
        }
    }

    public ChapterCommentBlockIndicatorDTO(String blockKey, int threadCount) {
        this(blockKey, threadCount, threadCount);
    }
}
