package com.universe.novel.application.anchor;

import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort;
import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort.ChapterAnchorDocumentSnapshot;
import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort.ReaderBlock;
import com.universe.novel.application.ports.ChapterCommentAnchorRepositoryPort;
import com.universe.novel.domain.anchor.ChapterCommentAnchor;
import com.universe.novel.domain.anchor.ChapterCommentAnchorResolution;
import com.universe.novel.domain.anchor.ChapterCommentAnchorResolutionStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Novel application query use case for resolving anchor evidence associated with a bounded
 * collection of root comment IDs against the current Reader content snapshot.
 *
 * <p>Preserves clean architecture rules:
 * <ul>
 *   <li>Read-only operation: never mutates, never calls {@code save}, never persists resolution state;</li>
 *   <li>Loads the current chapter snapshot at most once per execution, and only if eligible anchors exist;</li>
 *   <li>Filters out anchors belonging to chapters other than the requested {@code chapterId};</li>
 *   <li>Performs in-memory resolution via {@link ChapterCommentAnchorResolver};</li>
 *   <li>Safely degrades inconsistent resolutions (e.g. CURRENT/RELOCATED pointing to a block key missing
 *       from the snapshot) to STALE with {@code resolvedBlockKey = null} and original anchor text;</li>
 *   <li>Zero dependency on Interaction bounded context.</li>
 * </ul>
 */
@Service
public class ResolveChapterCommentAnchorsByRootIdsUseCase {

    private final ChapterCommentAnchorRepositoryPort anchorRepositoryPort;
    private final ChapterAnchorResolutionSourcePort resolutionSourcePort;
    private final ChapterCommentAnchorResolver resolver;

    public ResolveChapterCommentAnchorsByRootIdsUseCase(
            ChapterCommentAnchorRepositoryPort anchorRepositoryPort,
            ChapterAnchorResolutionSourcePort resolutionSourcePort,
            ChapterCommentAnchorResolver resolver
    ) {
        this.anchorRepositoryPort = Objects.requireNonNull(anchorRepositoryPort, "anchorRepositoryPort cannot be null");
        this.resolutionSourcePort = Objects.requireNonNull(resolutionSourcePort, "resolutionSourcePort cannot be null");
        this.resolver = Objects.requireNonNull(resolver, "resolver cannot be null");
    }

    /**
     * Resolves anchor state for the requested root comment IDs within a given chapter.
     *
     * @param chapterId scalar UUID of the chapter, must not be null
     * @param rootCommentIds collection of root comment IDs to resolve anchors for
     * @return immutable list of {@link ResolvedChapterCommentAnchorView} instances
     */
    @Transactional(readOnly = true)
    public List<ResolvedChapterCommentAnchorView> execute(UUID chapterId, Collection<UUID> rootCommentIds) {
        Objects.requireNonNull(chapterId, "chapterId cannot be null");
        if (rootCommentIds == null || rootCommentIds.isEmpty()) {
            return List.of();
        }

        List<ChapterCommentAnchor> anchors = anchorRepositoryPort.findByRootCommentIds(rootCommentIds);
        if (anchors == null || anchors.isEmpty()) {
            return List.of();
        }

        List<ChapterCommentAnchor> chapterAnchors = anchors.stream()
                .filter(a -> chapterId.equals(a.getChapterId()))
                .toList();
        if (chapterAnchors.isEmpty()) {
            return List.of();
        }

        ChapterAnchorDocumentSnapshot snapshot = resolutionSourcePort.loadCurrent(chapterId);
        Map<String, String> blockTextsByKey = snapshot.blocks().stream()
                .collect(Collectors.toMap(
                        ReaderBlock::blockKey,
                        ReaderBlock::canonicalText,
                        (existing, replacement) -> existing
                ));

        List<ResolvedChapterCommentAnchorView> results = new ArrayList<>(chapterAnchors.size());
        for (ChapterCommentAnchor anchor : chapterAnchors) {
            ChapterCommentAnchorResolution resolution = resolver.resolve(anchor, snapshot);

            if (resolution.status() == ChapterCommentAnchorResolutionStatus.CURRENT
                    || resolution.status() == ChapterCommentAnchorResolutionStatus.RELOCATED) {
                String resolvedKey = resolution.resolvedBlockKey();
                String canonicalText = resolvedKey != null ? blockTextsByKey.get(resolvedKey) : null;

                if (resolvedKey != null && canonicalText != null) {
                    results.add(new ResolvedChapterCommentAnchorView(
                            anchor.getRootCommentId(),
                            resolution.status(),
                            resolvedKey,
                            canonicalText
                    ));
                } else {
                    // Safe degradation: resolved key missing in snapshot -> degrade to STALE
                    results.add(new ResolvedChapterCommentAnchorView(
                            anchor.getRootCommentId(),
                            ChapterCommentAnchorResolutionStatus.STALE,
                            null,
                            anchor.getSelectedText()
                    ));
                }
            } else {
                // STALE resolution
                results.add(new ResolvedChapterCommentAnchorView(
                        anchor.getRootCommentId(),
                        ChapterCommentAnchorResolutionStatus.STALE,
                        null,
                        anchor.getSelectedText()
                ));
            }
        }

        return List.copyOf(results);
    }
}
