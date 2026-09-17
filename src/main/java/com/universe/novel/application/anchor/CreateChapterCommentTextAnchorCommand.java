package com.universe.novel.application.anchor;

import java.util.Objects;
import java.util.UUID;

/**
 * Command to create and persist an immutable Novel ChapterCommentAnchor for selected text.
 */
public record CreateChapterCommentTextAnchorCommand(
        UUID rootCommentId,
        UUID chapterId,
        long contentVersion,
        String blockKey,
        int startOffset,
        int endOffset
) {
    public CreateChapterCommentTextAnchorCommand {
        Objects.requireNonNull(rootCommentId, "rootCommentId cannot be null");
        Objects.requireNonNull(chapterId, "chapterId cannot be null");
        if (contentVersion < 1L) {
            throw new IllegalArgumentException("contentVersion must be >= 1: " + contentVersion);
        }
        Objects.requireNonNull(blockKey, "blockKey cannot be null");
        if (blockKey.trim().isEmpty()) {
            throw new IllegalArgumentException("blockKey cannot be blank");
        }
        if (startOffset < 0) {
            throw new IllegalArgumentException("startOffset cannot be negative: " + startOffset);
        }
        if (endOffset <= startOffset) {
            throw new IllegalArgumentException("endOffset must be strictly greater than startOffset: " + startOffset + " >= " + endOffset);
        }
    }
}
