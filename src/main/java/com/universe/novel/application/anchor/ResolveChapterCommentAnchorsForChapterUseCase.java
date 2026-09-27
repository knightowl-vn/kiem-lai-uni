package com.universe.novel.application.anchor;

import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort;
import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort.ChapterAnchorDocumentSnapshot;
import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort.ReaderBlock;
import com.universe.novel.application.ports.ChapterCommentAnchorRepositoryPort;
import com.universe.novel.domain.anchor.ChapterCommentAnchor;
import com.universe.novel.domain.anchor.ChapterCommentAnchorResolution;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Novel application use case for resolving all immutable {@link ChapterCommentAnchor} evidence
 * for a chapter in bulk against the current Reader content snapshot.
 *
 * <p>Preserves clean architecture rules:
 * <ul>
 *   <li>Read-only operation: never mutates, never calls {@code save}, never persists resolution state;</li>
 *   <li>Loads the current chapter snapshot exactly once for all anchors;</li>
 *   <li>Preserves current Reader block document order;</li>
 *   <li>Zero dependency on Interaction bounded context.</li>
 * </ul>
 */
@Service
public class ResolveChapterCommentAnchorsForChapterUseCase {

    private final ChapterCommentAnchorRepositoryPort anchorRepositoryPort;
    private final ChapterAnchorResolutionSourcePort resolutionSourcePort;
    private final ChapterCommentAnchorResolver resolver;

    public ResolveChapterCommentAnchorsForChapterUseCase(
            ChapterCommentAnchorRepositoryPort anchorRepositoryPort,
            ChapterAnchorResolutionSourcePort resolutionSourcePort,
            ChapterCommentAnchorResolver resolver
    ) {
        this.anchorRepositoryPort = Objects.requireNonNull(anchorRepositoryPort, "anchorRepositoryPort cannot be null");
        this.resolutionSourcePort = Objects.requireNonNull(resolutionSourcePort, "resolutionSourcePort cannot be null");
        this.resolver = Objects.requireNonNull(resolver, "resolver cannot be null");
    }

    /**
     * Resolves all anchors belonging to the specified chapter against the current Reader snapshot.
     *
     * @param chapterId scalar UUID of the chapter
     * @return {@link ChapterAnchorResolutionBulkView} containing resolutions and ordered block keys
     */
    @Transactional(readOnly = true)
    public ChapterAnchorResolutionBulkView execute(UUID chapterId) {
        Objects.requireNonNull(chapterId, "chapterId cannot be null");

        List<ChapterCommentAnchor> anchors = anchorRepositoryPort.findByChapterId(chapterId);
        if (anchors.isEmpty()) {
            return new ChapterAnchorResolutionBulkView(List.of(), List.of());
        }

        ChapterAnchorDocumentSnapshot snapshot = resolutionSourcePort.loadCurrent(chapterId);
        List<String> orderedBlockKeys = snapshot.blocks().stream()
                .map(ReaderBlock::blockKey)
                .toList();

        List<ChapterAnchorResolutionBulkView.ResolutionRow> rows = new ArrayList<>(anchors.size());
        for (ChapterCommentAnchor anchor : anchors) {
            ChapterCommentAnchorResolution resolution = resolver.resolve(anchor, snapshot);
            rows.add(new ChapterAnchorResolutionBulkView.ResolutionRow(
                    resolution.rootCommentId(),
                    resolution.status(),
                    resolution.resolvedBlockKey()
            ));
        }

        return new ChapterAnchorResolutionBulkView(rows, orderedBlockKeys);
    }
}
