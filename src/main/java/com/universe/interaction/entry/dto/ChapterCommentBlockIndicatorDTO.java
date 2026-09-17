package com.universe.interaction.entry.dto;

import java.util.Objects;

/**
 * Immutable response DTO representing an inline discussion indicator for a Reader block.
 *
 * @param blockKey stable fingerprint of the Reader block
 * @param threadCount number of visible anchored root discussion threads on this block (>= 1)
 */
public record ChapterCommentBlockIndicatorDTO(
        String blockKey,
        int threadCount
) {
    public ChapterCommentBlockIndicatorDTO {
        Objects.requireNonNull(blockKey, "blockKey cannot be null");
        if (threadCount <= 0) {
            throw new IllegalArgumentException("threadCount must be positive: " + threadCount);
        }
    }
}
