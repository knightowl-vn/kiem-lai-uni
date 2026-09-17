package com.universe.novel.application.anchor;

import com.universe.novel.application.exceptions.ReaderBlockNotFoundException;
import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort;
import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort.ChapterAnchorDocumentSnapshot;
import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort.ReaderBlock;
import com.universe.novel.application.ports.ChapterCommentAnchorRepositoryPort;
import com.universe.novel.domain.anchor.ChapterCommentAnchor;
import com.universe.novel.domain.anchor.ChapterCommentAnchorResolution;
import com.universe.novel.domain.anchor.ChapterCommentAnchorResolutionStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Novel application query use case for retrieving the current canonical block context and
 * resolving eligible chapter comment anchors to root comment IDs for a requested blockKey.
 *
 * <p>Preserves clean architecture rules:
 * <ul>
 *   <li>Read-only operation: never mutates, never calls {@code save}, never updates anchor state;</li>
 *   <li>Loads the current chapter snapshot exactly once for the request;</li>
 *   <li>Validates that requested blockKey exists exactly once in current content snapshot;</li>
 *   <li>Resolves anchors in memory using {@link ChapterCommentAnchorResolver};</li>
 *   <li>Includes CURRENT and RELOCATED anchors matching the requested blockKey;</li>
 *   <li>Excludes STALE anchors, anchors resolving to other blocks, and anchors for other chapters;</li>
 *   <li>Collects deterministic rootCommentIds with zero duplicates;</li>
 *   <li>Zero dependency on Interaction domain or entity types.</li>
 * </ul>
 */
@Service
public class GetChapterBlockDiscussionAnchorsUseCase {

    private final ChapterCommentAnchorRepositoryPort anchorRepositoryPort;
    private final ChapterAnchorResolutionSourcePort resolutionSourcePort;
    private final ChapterCommentAnchorResolver resolver;

    public GetChapterBlockDiscussionAnchorsUseCase(
            ChapterCommentAnchorRepositoryPort anchorRepositoryPort,
            ChapterAnchorResolutionSourcePort resolutionSourcePort,
            ChapterCommentAnchorResolver resolver
    ) {
        this.anchorRepositoryPort = Objects.requireNonNull(anchorRepositoryPort, "anchorRepositoryPort cannot be null");
        this.resolutionSourcePort = Objects.requireNonNull(resolutionSourcePort, "resolutionSourcePort cannot be null");
        this.resolver = Objects.requireNonNull(resolver, "resolver cannot be null");
    }

    /**
     * Resolves the canonical block context and matching root comment IDs for a requested blockKey.
     *
     * @param chapterId scalar UUID of the chapter
     * @param blockKey canonical Reader block key
     * @return {@link ChapterBlockDiscussionAnchorView} containing block text and resolved root comment IDs
     */
    @Transactional(readOnly = true)
    public ChapterBlockDiscussionAnchorView execute(UUID chapterId, String blockKey) {
        Objects.requireNonNull(chapterId, "chapterId cannot be null");
        if (blockKey == null || blockKey.trim().isEmpty()) {
            throw new IllegalArgumentException("blockKey cannot be blank");
        }
        String trimmedBlockKey = blockKey.trim();

        // 1. Load current chapter snapshot exactly once
        ChapterAnchorDocumentSnapshot snapshot = resolutionSourcePort.loadCurrent(chapterId);

        if (snapshot.contentVersion() < 1) {
            throw new IllegalStateException("Invalid current chapter contentVersion: " + snapshot.contentVersion());
        }

        // 2. Validate current canonical block existence
        List<ReaderBlock> matchingBlocks = snapshot.blocks().stream()
                .filter(b -> b.blockKey().equals(trimmedBlockKey))
                .toList();

        if (matchingBlocks.isEmpty()) {
            throw new ReaderBlockNotFoundException(chapterId, trimmedBlockKey);
        }
        if (matchingBlocks.size() > 1) {
            throw new IllegalStateException("Duplicate canonical blockKey found in chapter snapshot: " + trimmedBlockKey);
        }

        ReaderBlock currentBlock = matchingBlocks.get(0);

        // 3. Load immutable anchor evidence for the chapter
        List<ChapterCommentAnchor> anchors = anchorRepositoryPort.findByChapterId(chapterId);

        // 4. In-memory pure resolution and deterministic root comment ID collection
        Set<UUID> resolvedRootCommentIds = new LinkedHashSet<>();
        for (ChapterCommentAnchor anchor : anchors) {
            if (!chapterId.equals(anchor.getChapterId())) {
                continue;
            }

            ChapterCommentAnchorResolution resolution = resolver.resolve(anchor, snapshot);
            if ((resolution.status() == ChapterCommentAnchorResolutionStatus.CURRENT
                    || resolution.status() == ChapterCommentAnchorResolutionStatus.RELOCATED)
                    && trimmedBlockKey.equals(resolution.resolvedBlockKey())
                    && resolution.rootCommentId() != null) {
                resolvedRootCommentIds.add(resolution.rootCommentId());
            }
        }

        return new ChapterBlockDiscussionAnchorView(
                chapterId,
                snapshot.contentVersion(),
                currentBlock.blockKey(),
                currentBlock.canonicalText(),
                List.copyOf(resolvedRootCommentIds)
        );
    }
}
