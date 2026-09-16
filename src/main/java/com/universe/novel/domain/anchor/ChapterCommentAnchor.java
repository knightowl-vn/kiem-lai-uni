package com.universe.novel.domain.anchor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Novel-owned immutable domain aggregate representing the original Reader anchor evidence
 * for an inline chapter discussion thread.
 *
 * <p>Preserves clean architecture and cross-context boundaries:
 * <ul>
 *   <li>One anchor belongs strictly to one ROOT discussion comment ({@code rootCommentId});</li>
 *   <li>Replies inherit the root's anchor and do not receive independent anchor rows;</li>
 *   <li>Stores only scalar {@code UUID rootCommentId} with zero dependency on Interaction domain/entities;</li>
 *   <li>Anchor evidence is immutable after creation and must never be modified by later relocation resolvers.</li>
 * </ul>
 */
public final class ChapterCommentAnchor {

    public static final int MAX_CONTEXT_CODE_UNITS = 64;
    private static final Pattern BLOCK_KEY_PATTERN = Pattern.compile("^blk-[0-9a-f]{16}-[1-9][0-9]*$");

    private final UUID rootCommentId;
    private final UUID chapterId;
    private final long contentVersion;
    private final String blockKey;
    private final ChapterCommentAnchorKind anchorKind;
    private final Integer startOffset;
    private final Integer endOffset;
    private final String selectedText;
    private final String contextBefore;
    private final String contextAfter;
    private final Instant createdAt;

    private ChapterCommentAnchor(
            UUID rootCommentId,
            UUID chapterId,
            long contentVersion,
            String blockKey,
            ChapterCommentAnchorKind anchorKind,
            Integer startOffset,
            Integer endOffset,
            String selectedText,
            String contextBefore,
            String contextAfter,
            Instant createdAt
    ) {
        this.rootCommentId = Objects.requireNonNull(rootCommentId, "Root comment ID cannot be null.");
        this.chapterId = Objects.requireNonNull(chapterId, "Chapter ID cannot be null.");

        if (contentVersion < 1L) {
            throw new IllegalArgumentException("Original content version must be >= 1: " + contentVersion);
        }
        this.contentVersion = contentVersion;

        this.blockKey = validateBlockKey(blockKey);
        this.anchorKind = Objects.requireNonNull(anchorKind, "Anchor kind cannot be null.");

        this.selectedText = Objects.requireNonNull(selectedText, "Selected text cannot be null.");
        if (this.selectedText.isEmpty()) {
            throw new IllegalArgumentException("Selected text cannot be empty.");
        }

        this.contextBefore = Objects.requireNonNull(contextBefore, "Context before cannot be null.");
        this.contextAfter = Objects.requireNonNull(contextAfter, "Context after cannot be null.");
        this.createdAt = Objects.requireNonNull(createdAt, "CreatedAt timestamp cannot be null.");

        if (anchorKind == ChapterCommentAnchorKind.BLOCK) {
            if (startOffset != null || endOffset != null) {
                throw new IllegalArgumentException("Offsets must be null for a BLOCK anchor.");
            }
            if (!contextBefore.isEmpty() || !contextAfter.isEmpty()) {
                throw new IllegalArgumentException("Surrounding context must be empty for a BLOCK anchor.");
            }
            this.startOffset = null;
            this.endOffset = null;
        } else if (anchorKind == ChapterCommentAnchorKind.TEXT_RANGE) {
            if (startOffset == null || startOffset < 0) {
                throw new IllegalArgumentException("startOffset must be non-null and >= 0 for a TEXT_RANGE anchor.");
            }
            if (endOffset == null || endOffset <= startOffset) {
                throw new IllegalArgumentException("endOffset must be non-null and strictly greater than startOffset.");
            }
            if (this.selectedText.length() != (endOffset - startOffset)) {
                throw new IllegalArgumentException(
                        "Selected text length (" + this.selectedText.length() +
                        ") must match offset range [" + startOffset + ", " + endOffset + "] (" +
                        (endOffset - startOffset) + ") in UTF-16 code units."
                );
            }
            if (contextBefore.length() > MAX_CONTEXT_CODE_UNITS) {
                throw new IllegalArgumentException(
                        "contextBefore length (" + contextBefore.length() +
                        ") exceeds maximum of " + MAX_CONTEXT_CODE_UNITS + " code units."
                );
            }
            if (contextAfter.length() > MAX_CONTEXT_CODE_UNITS) {
                throw new IllegalArgumentException(
                        "contextAfter length (" + contextAfter.length() +
                        ") exceeds maximum of " + MAX_CONTEXT_CODE_UNITS + " code units."
                );
            }
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        } else {
            throw new IllegalArgumentException("Unsupported anchor kind: " + anchorKind);
        }
    }

    /**
     * Factory method for creating a canonical BLOCK anchor.
     */
    public static ChapterCommentAnchor createBlock(
            UUID rootCommentId,
            UUID chapterId,
            long contentVersion,
            String blockKey,
            String selectedText,
            Instant createdAt
    ) {
        return new ChapterCommentAnchor(
                rootCommentId,
                chapterId,
                contentVersion,
                blockKey,
                ChapterCommentAnchorKind.BLOCK,
                null,
                null,
                selectedText,
                "",
                "",
                createdAt
        );
    }

    /**
     * Factory method for creating a canonical TEXT_RANGE anchor.
     */
    public static ChapterCommentAnchor createTextRange(
            UUID rootCommentId,
            UUID chapterId,
            long contentVersion,
            String blockKey,
            int startOffset,
            int endOffset,
            String selectedText,
            String contextBefore,
            String contextAfter,
            Instant createdAt
    ) {
        return new ChapterCommentAnchor(
                rootCommentId,
                chapterId,
                contentVersion,
                blockKey,
                ChapterCommentAnchorKind.TEXT_RANGE,
                startOffset,
                endOffset,
                selectedText,
                contextBefore,
                contextAfter,
                createdAt
        );
    }

    /**
     * Rehydrates an immutable ChapterCommentAnchor from persistence.
     */
    public static ChapterCommentAnchor rehydrate(
            UUID rootCommentId,
            UUID chapterId,
            long contentVersion,
            String blockKey,
            ChapterCommentAnchorKind anchorKind,
            Integer startOffset,
            Integer endOffset,
            String selectedText,
            String contextBefore,
            String contextAfter,
            Instant createdAt
    ) {
        return new ChapterCommentAnchor(
                rootCommentId,
                chapterId,
                contentVersion,
                blockKey,
                anchorKind,
                startOffset,
                endOffset,
                selectedText,
                contextBefore,
                contextAfter,
                createdAt
        );
    }

    private static String validateBlockKey(String key) {
        if (key == null) {
            throw new IllegalArgumentException("Block key cannot be null.");
        }
        if (key.isBlank()) {
            throw new IllegalArgumentException("Block key cannot be blank.");
        }
        if (!BLOCK_KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException("Block key must follow E1 format ('blk-<16hex>-<occurrence>'): " + key);
        }
        return key;
    }

    public UUID getRootCommentId() {
        return rootCommentId;
    }

    public UUID getChapterId() {
        return chapterId;
    }

    public long getContentVersion() {
        return contentVersion;
    }

    public String getBlockKey() {
        return blockKey;
    }

    public ChapterCommentAnchorKind getAnchorKind() {
        return anchorKind;
    }

    public Integer getStartOffset() {
        return startOffset;
    }

    public Integer getEndOffset() {
        return endOffset;
    }

    public String getSelectedText() {
        return selectedText;
    }

    public String getContextBefore() {
        return contextBefore;
    }

    public String getContextAfter() {
        return contextAfter;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isBlockAnchor() {
        return anchorKind == ChapterCommentAnchorKind.BLOCK;
    }

    public boolean isTextRangeAnchor() {
        return anchorKind == ChapterCommentAnchorKind.TEXT_RANGE;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChapterCommentAnchor that = (ChapterCommentAnchor) o;
        return Objects.equals(rootCommentId, that.rootCommentId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rootCommentId);
    }

    @Override
    public String toString() {
        return "ChapterCommentAnchor{" +
                "rootCommentId=" + rootCommentId +
                ", chapterId=" + chapterId +
                ", contentVersion=" + contentVersion +
                ", blockKey='" + blockKey + '\'' +
                ", anchorKind=" + anchorKind +
                ", startOffset=" + startOffset +
                ", endOffset=" + endOffset +
                ", selectedTextLength=" + selectedText.length() +
                ", createdAt=" + createdAt +
                '}';
    }
}
