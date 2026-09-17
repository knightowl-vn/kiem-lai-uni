package com.universe.interaction.entry.dto;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable response DTO representing a block-discussion read response for the Novel Reader drawer.
 *
 * @param chapterId UUID of the chapter
 * @param contentVersion current chapter content version
 * @param blockKey canonical Reader block key
 * @param canonicalText full current canonical block text from current Reader snapshot
 * @param threadCount number of visible root discussion threads returned/available for the current block
 * @param commentCount total visible active comments (root + visible active replies) inside visible discussions
 * @param threads list of visible discussion threads anchored to this block
 */
public record ChapterBlockDiscussionResponseDTO(
        UUID chapterId,
        long contentVersion,
        String blockKey,
        String canonicalText,
        int threadCount,
        int commentCount,
        List<CommentThreadResponseDTO> threads
) {
    public ChapterBlockDiscussionResponseDTO {
        Objects.requireNonNull(chapterId, "chapterId cannot be null");
        Objects.requireNonNull(blockKey, "blockKey cannot be null");
        Objects.requireNonNull(canonicalText, "canonicalText cannot be null");
        threads = threads == null ? List.of() : List.copyOf(threads);
    }

    public ChapterBlockDiscussionResponseDTO(
            UUID chapterId,
            long contentVersion,
            String blockKey,
            String canonicalText,
            int threadCount,
            List<CommentThreadResponseDTO> threads
    ) {
        this(chapterId, contentVersion, blockKey, canonicalText, threadCount, threadCount, threads);
    }
}
