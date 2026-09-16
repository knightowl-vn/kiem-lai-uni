package com.universe.novel.domain.anchor;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable runtime resolution result of a {@link ChapterCommentAnchor} against
 * current Chapter Reader content.
 *
 * <p>Never persists resolution state. Original evidence remains immutable in
 * {@link ChapterCommentAnchor}.</p>
 */
public record ChapterCommentAnchorResolution(
        UUID rootCommentId,
        UUID chapterId,
        long originalContentVersion,
        long currentContentVersion,
        ChapterCommentAnchorResolutionStatus status,
        String resolvedBlockKey,
        Integer resolvedStartOffset,
        Integer resolvedEndOffset
) {

    public ChapterCommentAnchorResolution {
        Objects.requireNonNull(rootCommentId, "rootCommentId cannot be null");
        Objects.requireNonNull(chapterId, "chapterId cannot be null");
        Objects.requireNonNull(status, "status cannot be null");

        if (originalContentVersion < 1L) {
            throw new IllegalArgumentException("originalContentVersion must be >= 1: " + originalContentVersion);
        }
        if (currentContentVersion < 1L) {
            throw new IllegalArgumentException("currentContentVersion must be >= 1: " + currentContentVersion);
        }

        if (status == ChapterCommentAnchorResolutionStatus.CURRENT) {
            if (originalContentVersion != currentContentVersion) {
                throw new IllegalArgumentException(
                        "CURRENT resolution requires originalContentVersion == currentContentVersion ("
                                + originalContentVersion + " != " + currentContentVersion + ")"
                );
            }
        } else if (status == ChapterCommentAnchorResolutionStatus.RELOCATED) {
            if (originalContentVersion == currentContentVersion) {
                throw new IllegalArgumentException(
                        "RELOCATED resolution requires originalContentVersion != currentContentVersion ("
                                + originalContentVersion + " == " + currentContentVersion + ")"
                );
            }
        }

        if (status == ChapterCommentAnchorResolutionStatus.STALE) {
            if (resolvedBlockKey != null || resolvedStartOffset != null || resolvedEndOffset != null) {
                throw new IllegalArgumentException("STALE resolution must have null resolvedBlockKey and null offsets");
            }
        } else {
            Objects.requireNonNull(resolvedBlockKey, "resolvedBlockKey cannot be null for non-STALE resolution");
            if (resolvedBlockKey.isBlank()) {
                throw new IllegalArgumentException("resolvedBlockKey cannot be blank for non-STALE resolution");
            }
            if (resolvedStartOffset == null && resolvedEndOffset == null) {
                // BLOCK anchor - offsets must be null
            } else if (resolvedStartOffset != null && resolvedEndOffset != null) {
                // TEXT_RANGE anchor - both offsets required, 0 <= start < end
                if (resolvedStartOffset < 0) {
                    throw new IllegalArgumentException("resolvedStartOffset must be >= 0: " + resolvedStartOffset);
                }
                if (resolvedStartOffset >= resolvedEndOffset) {
                    throw new IllegalArgumentException("resolvedStartOffset (" + resolvedStartOffset
                            + ") must be strictly less than resolvedEndOffset (" + resolvedEndOffset + ")");
                }
            } else {
                throw new IllegalArgumentException("Offsets must either both be null (BLOCK) or both non-null (TEXT_RANGE)");
            }
        }
    }

    public static ChapterCommentAnchorResolution currentBlock(
            UUID rootCommentId,
            UUID chapterId,
            long contentVersion,
            String resolvedBlockKey
    ) {
        return new ChapterCommentAnchorResolution(
                rootCommentId,
                chapterId,
                contentVersion,
                contentVersion,
                ChapterCommentAnchorResolutionStatus.CURRENT,
                resolvedBlockKey,
                null,
                null
        );
    }

    public static ChapterCommentAnchorResolution currentTextRange(
            UUID rootCommentId,
            UUID chapterId,
            long contentVersion,
            String resolvedBlockKey,
            int resolvedStartOffset,
            int resolvedEndOffset
    ) {
        return new ChapterCommentAnchorResolution(
                rootCommentId,
                chapterId,
                contentVersion,
                contentVersion,
                ChapterCommentAnchorResolutionStatus.CURRENT,
                resolvedBlockKey,
                resolvedStartOffset,
                resolvedEndOffset
        );
    }

    public static ChapterCommentAnchorResolution relocatedBlock(
            UUID rootCommentId,
            UUID chapterId,
            long originalContentVersion,
            long currentContentVersion,
            String resolvedBlockKey
    ) {
        return new ChapterCommentAnchorResolution(
                rootCommentId,
                chapterId,
                originalContentVersion,
                currentContentVersion,
                ChapterCommentAnchorResolutionStatus.RELOCATED,
                resolvedBlockKey,
                null,
                null
        );
    }

    public static ChapterCommentAnchorResolution relocatedTextRange(
            UUID rootCommentId,
            UUID chapterId,
            long originalContentVersion,
            long currentContentVersion,
            String resolvedBlockKey,
            int resolvedStartOffset,
            int resolvedEndOffset
    ) {
        return new ChapterCommentAnchorResolution(
                rootCommentId,
                chapterId,
                originalContentVersion,
                currentContentVersion,
                ChapterCommentAnchorResolutionStatus.RELOCATED,
                resolvedBlockKey,
                resolvedStartOffset,
                resolvedEndOffset
        );
    }

    public static ChapterCommentAnchorResolution stale(
            UUID rootCommentId,
            UUID chapterId,
            long originalContentVersion,
            long currentContentVersion
    ) {
        return new ChapterCommentAnchorResolution(
                rootCommentId,
                chapterId,
                originalContentVersion,
                currentContentVersion,
                ChapterCommentAnchorResolutionStatus.STALE,
                null,
                null,
                null
        );
    }
}
