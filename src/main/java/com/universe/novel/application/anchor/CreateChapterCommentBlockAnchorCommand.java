package com.universe.novel.application.anchor;

import java.util.Objects;
import java.util.UUID;

/**
 * Command to create and persist an immutable Novel ChapterCommentAnchor for a canonical Reader block.
 */
public record CreateChapterCommentBlockAnchorCommand(
        UUID rootCommentId,
        UUID chapterId,
        long contentVersion,
        String blockKey
) {
    public CreateChapterCommentBlockAnchorCommand {
        Objects.requireNonNull(rootCommentId, "rootCommentId cannot be null");
        Objects.requireNonNull(chapterId, "chapterId cannot be null");
        if (contentVersion < 1L) {
            throw new IllegalArgumentException("contentVersion must be >= 1: " + contentVersion);
        }
        Objects.requireNonNull(blockKey, "blockKey cannot be null");
        if (blockKey.trim().isEmpty()) {
            throw new IllegalArgumentException("blockKey cannot be blank");
        }
    }
}
