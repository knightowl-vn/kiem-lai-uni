package com.universe.novel.application.ports;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Novel application boundary for loading the current chapter content snapshot
 * required for inline anchor resolution.
 */
public interface ChapterAnchorResolutionSourcePort {

    record ReaderBlock(String blockKey, String canonicalText) {
        public ReaderBlock {
            Objects.requireNonNull(blockKey, "blockKey cannot be null");
            Objects.requireNonNull(canonicalText, "canonicalText cannot be null");
        }
    }

    record ChapterAnchorDocumentSnapshot(
            UUID chapterId,
            long contentVersion,
            List<ReaderBlock> blocks
    ) {
        public ChapterAnchorDocumentSnapshot {
            Objects.requireNonNull(chapterId, "chapterId cannot be null");
            blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks cannot be null"));
        }
    }

    ChapterAnchorDocumentSnapshot loadCurrent(UUID chapterId);
}
