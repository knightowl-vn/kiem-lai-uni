package com.universe.novel.application.anchor;

import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort.ChapterAnchorDocumentSnapshot;
import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort.ReaderBlock;
import com.universe.novel.domain.anchor.ChapterCommentAnchor;
import com.universe.novel.domain.anchor.ChapterCommentAnchorResolution;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure domain/application resolution engine for {@link ChapterCommentAnchor} evidence
 * against a {@link ChapterAnchorDocumentSnapshot}.
 *
 * <p>Preserves deterministic resolution invariants:
 * <ul>
 *   <li>Same contentVersion uses direct coordinates only, never searches or relocates;</li>
 *   <li>Different contentVersion performs exact blockKey matching followed by exact text and context disambiguation;</li>
 *   <li>Ambiguous candidate matches resolve strictly to STALE;</li>
 *   <li>Pure in-memory computation without side effects or mutations.</li>
 * </ul>
 */
@Component
public class ChapterCommentAnchorResolver {

    /**
     * Resolves an anchor against a chapter content snapshot.
     *
     * @param anchor the immutable anchor evidence, cannot be null
     * @param snapshot the current chapter reader document snapshot, cannot be null
     * @return immutable {@link ChapterCommentAnchorResolution}
     */
    public ChapterCommentAnchorResolution resolve(
            ChapterCommentAnchor anchor,
            ChapterAnchorDocumentSnapshot snapshot
    ) {
        Objects.requireNonNull(anchor, "anchor cannot be null");
        Objects.requireNonNull(snapshot, "snapshot cannot be null");

        if (snapshot.contentVersion() == anchor.getContentVersion()) {
            return resolveSameVersion(anchor, snapshot);
        } else {
            return resolveDifferentVersion(anchor, snapshot);
        }
    }

    // -------------------------------------------------------------------------
    // SAME VERSION RESOLUTION (Direct validation only, NO relocation)
    // -------------------------------------------------------------------------

    private ChapterCommentAnchorResolution resolveSameVersion(
            ChapterCommentAnchor anchor,
            ChapterAnchorDocumentSnapshot snapshot
    ) {
        List<ReaderBlock> keyMatches = snapshot.blocks().stream()
                .filter(b -> b.blockKey().equals(anchor.getBlockKey()))
                .toList();

        if (keyMatches.size() != 1) {
            return ChapterCommentAnchorResolution.stale(
                    anchor.getRootCommentId(),
                    anchor.getChapterId(),
                    anchor.getContentVersion(),
                    snapshot.contentVersion()
            );
        }

        ReaderBlock block = keyMatches.get(0);

        if (anchor.isBlockAnchor()) {
            return ChapterCommentAnchorResolution.currentBlock(
                    anchor.getRootCommentId(),
                    anchor.getChapterId(),
                    snapshot.contentVersion(),
                    block.blockKey()
            );
        }

        // TEXT_RANGE anchor
        Integer start = anchor.getStartOffset();
        Integer end = anchor.getEndOffset();
        String text = block.canonicalText();

        if (start != null && end != null && start >= 0 && start < end && end <= text.length()) {
            if (text.substring(start, end).equals(anchor.getSelectedText())) {
                return ChapterCommentAnchorResolution.currentTextRange(
                        anchor.getRootCommentId(),
                        anchor.getChapterId(),
                        snapshot.contentVersion(),
                        block.blockKey(),
                        start,
                        end
                );
            }
        }

        return ChapterCommentAnchorResolution.stale(
                anchor.getRootCommentId(),
                anchor.getChapterId(),
                anchor.getContentVersion(),
                snapshot.contentVersion()
        );
    }

    // -------------------------------------------------------------------------
    // DIFFERENT VERSION RESOLUTION
    // -------------------------------------------------------------------------

    private ChapterCommentAnchorResolution resolveDifferentVersion(
            ChapterCommentAnchor anchor,
            ChapterAnchorDocumentSnapshot snapshot
    ) {
        if (anchor.isBlockAnchor()) {
            return resolveDifferentVersionBlock(anchor, snapshot);
        } else {
            return resolveDifferentVersionTextRange(anchor, snapshot);
        }
    }

    private ChapterCommentAnchorResolution resolveDifferentVersionBlock(
            ChapterCommentAnchor anchor,
            ChapterAnchorDocumentSnapshot snapshot
    ) {
        // STEP 1 - exact blockKey
        List<ReaderBlock> keyMatches = snapshot.blocks().stream()
                .filter(b -> b.blockKey().equals(anchor.getBlockKey()))
                .toList();

        if (keyMatches.size() == 1) {
            return ChapterCommentAnchorResolution.relocatedBlock(
                    anchor.getRootCommentId(),
                    anchor.getChapterId(),
                    anchor.getContentVersion(),
                    snapshot.contentVersion(),
                    keyMatches.get(0).blockKey()
            );
        }

        if (keyMatches.size() > 1) {
            // Structurally ambiguous key
            return ChapterCommentAnchorResolution.stale(
                    anchor.getRootCommentId(),
                    anchor.getChapterId(),
                    anchor.getContentVersion(),
                    snapshot.contentVersion()
            );
        }

        // STEP 2 - exact canonical text fallback (only if original blockKey is absent)
        List<ReaderBlock> textMatches = snapshot.blocks().stream()
                .filter(b -> b.canonicalText().equals(anchor.getSelectedText()))
                .toList();

        if (textMatches.size() == 1) {
            return ChapterCommentAnchorResolution.relocatedBlock(
                    anchor.getRootCommentId(),
                    anchor.getChapterId(),
                    anchor.getContentVersion(),
                    snapshot.contentVersion(),
                    textMatches.get(0).blockKey()
            );
        }

        return ChapterCommentAnchorResolution.stale(
                anchor.getRootCommentId(),
                anchor.getChapterId(),
                anchor.getContentVersion(),
                snapshot.contentVersion()
        );
    }

    private ChapterCommentAnchorResolution resolveDifferentVersionTextRange(
            ChapterCommentAnchor anchor,
            ChapterAnchorDocumentSnapshot snapshot
    ) {
        List<ReaderBlock> keyMatches = snapshot.blocks().stream()
                .filter(b -> b.blockKey().equals(anchor.getBlockKey()))
                .toList();

        if (keyMatches.size() > 1) {
            // Structurally ambiguous key
            return ChapterCommentAnchorResolution.stale(
                    anchor.getRootCommentId(),
                    anchor.getChapterId(),
                    anchor.getContentVersion(),
                    snapshot.contentVersion()
            );
        }

        if (keyMatches.size() == 1) {
            // Case A: Original blockKey still exists. Search ONLY that block.
            ReaderBlock targetBlock = keyMatches.get(0);
            Integer origStart = anchor.getStartOffset();
            Integer origEnd = anchor.getEndOffset();
            String text = targetBlock.canonicalText();

            // First: check if original offsets are still exact
            if (origStart != null && origEnd != null && origStart >= 0 && origStart < origEnd && origEnd <= text.length()
                    && text.substring(origStart, origEnd).equals(anchor.getSelectedText())) {
                return ChapterCommentAnchorResolution.relocatedTextRange(
                        anchor.getRootCommentId(),
                        anchor.getChapterId(),
                        anchor.getContentVersion(),
                        snapshot.contentVersion(),
                        targetBlock.blockKey(),
                        origStart,
                        origEnd
                );
            }

            // Otherwise: find all exact occurrences inside targetBlock
            List<Candidate> candidates = findOccurrences(targetBlock, anchor.getSelectedText());
            if (candidates.isEmpty()) {
                // If original blockKey still exists but selectedText disappeared from it, DO NOT jump to another block
                return ChapterCommentAnchorResolution.stale(
                        anchor.getRootCommentId(),
                        anchor.getChapterId(),
                        anchor.getContentVersion(),
                        snapshot.contentVersion()
                );
            }

            if (candidates.size() == 1) {
                Candidate c = candidates.get(0);
                return ChapterCommentAnchorResolution.relocatedTextRange(
                        anchor.getRootCommentId(),
                        anchor.getChapterId(),
                        anchor.getContentVersion(),
                        snapshot.contentVersion(),
                        c.blockKey(),
                        c.startOffset(),
                        c.endOffset()
                );
            }

            // Multiple candidates within the block -> context disambiguation
            return disambiguate(candidates, anchor, snapshot.contentVersion());
        }

        // Case B: Original blockKey no longer exists. Search every current canonical block globally.
        List<Candidate> allCandidates = new ArrayList<>();
        for (ReaderBlock block : snapshot.blocks()) {
            allCandidates.addAll(findOccurrences(block, anchor.getSelectedText()));
        }

        if (allCandidates.isEmpty()) {
            return ChapterCommentAnchorResolution.stale(
                    anchor.getRootCommentId(),
                    anchor.getChapterId(),
                    anchor.getContentVersion(),
                    snapshot.contentVersion()
            );
        }

        if (allCandidates.size() == 1) {
            Candidate c = allCandidates.get(0);
            return ChapterCommentAnchorResolution.relocatedTextRange(
                    anchor.getRootCommentId(),
                    anchor.getChapterId(),
                    anchor.getContentVersion(),
                    snapshot.contentVersion(),
                    c.blockKey(),
                    c.startOffset(),
                    c.endOffset()
            );
        }

        // Multiple candidates globally -> context disambiguation
        return disambiguate(allCandidates, anchor, snapshot.contentVersion());
    }

    // -------------------------------------------------------------------------
    // OCCURRENCE SEARCH & DISAMBIGUATION
    // -------------------------------------------------------------------------

    private record Candidate(String blockKey, String canonicalText, int startOffset, int endOffset) {}

    private List<Candidate> findOccurrences(ReaderBlock block, String target) {
        List<Candidate> result = new ArrayList<>();
        String text = block.canonicalText();
        int index = 0;
        while (index <= text.length() - target.length()) {
            int found = text.indexOf(target, index);
            if (found == -1) {
                break;
            }
            result.add(new Candidate(block.blockKey(), text, found, found + target.length()));
            index = found + 1; // Allows distinct overlapping occurrences
        }
        return result;
    }

    private ChapterCommentAnchorResolution disambiguate(
            List<Candidate> candidates,
            ChapterCommentAnchor anchor,
            long currentContentVersion
    ) {
        int maxScore = -1;
        Candidate bestCandidate = null;
        boolean tie = false;

        for (Candidate c : candidates) {
            int score = calculateScore(c, anchor.getContextBefore(), anchor.getContextAfter());
            if (score > maxScore) {
                maxScore = score;
                bestCandidate = c;
                tie = false;
            } else if (score == maxScore) {
                tie = true;
            }
        }

        if (tie || maxScore <= 0 || bestCandidate == null) {
            return ChapterCommentAnchorResolution.stale(
                    anchor.getRootCommentId(),
                    anchor.getChapterId(),
                    anchor.getContentVersion(),
                    currentContentVersion
            );
        }

        return ChapterCommentAnchorResolution.relocatedTextRange(
                anchor.getRootCommentId(),
                anchor.getChapterId(),
                anchor.getContentVersion(),
                currentContentVersion,
                bestCandidate.blockKey(),
                bestCandidate.startOffset(),
                bestCandidate.endOffset()
        );
    }

    private int calculateScore(Candidate c, String contextBefore, String contextAfter) {
        int beforeScore = 0;
        int startOffset = c.startOffset();
        int endOffset = c.endOffset();
        int textLength = c.canonicalText().length();

        // Suffix of contextBefore matched against text immediately preceding candidate start (working backwards)
        while (beforeScore < contextBefore.length() && beforeScore < startOffset) {
            char expected = contextBefore.charAt(contextBefore.length() - 1 - beforeScore);
            char actual = c.canonicalText().charAt(startOffset - 1 - beforeScore);
            if (expected == actual) {
                beforeScore++;
            } else {
                break;
            }
        }

        // Prefix of contextAfter matched against text immediately following candidate end (working forwards)
        int afterScore = 0;
        int remainingAfterLen = textLength - endOffset;
        while (afterScore < contextAfter.length() && afterScore < remainingAfterLen) {
            char expected = contextAfter.charAt(afterScore);
            char actual = c.canonicalText().charAt(endOffset + afterScore);
            if (expected == actual) {
                afterScore++;
            } else {
                break;
            }
        }

        return beforeScore + afterScore;
    }
}
