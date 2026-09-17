package com.universe.novel.application.anchor;

import com.universe.novel.application.exceptions.ChapterCommentAnchorNotFoundException;
import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort;
import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort.ChapterAnchorDocumentSnapshot;
import com.universe.novel.application.ports.ChapterCommentAnchorRepositoryPort;
import com.universe.novel.domain.anchor.ChapterCommentAnchor;
import com.universe.novel.domain.anchor.ChapterCommentAnchorResolution;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Novel application use case for resolving immutable {@link ChapterCommentAnchor} evidence
 * against the current Chapter Reader content snapshot.
 *
 * <p>Preserves clean architecture rules:
 * <ul>
 *   <li>Read-only operation: never mutates, never calls {@code save}, never persists resolution state;</li>
 *   <li>Zero dependency on Interaction bounded context;</li>
 *   <li>Delegates pure resolution algorithm to {@link ChapterCommentAnchorResolver}.</li>
 * </ul>
 */
@Service
public class ResolveChapterCommentAnchorUseCase {

    private final ChapterCommentAnchorRepositoryPort anchorRepositoryPort;
    private final ChapterAnchorResolutionSourcePort resolutionSourcePort;
    private final ChapterCommentAnchorResolver resolver;

    public ResolveChapterCommentAnchorUseCase(
            ChapterCommentAnchorRepositoryPort anchorRepositoryPort,
            ChapterAnchorResolutionSourcePort resolutionSourcePort,
            ChapterCommentAnchorResolver resolver
    ) {
        this.anchorRepositoryPort = Objects.requireNonNull(anchorRepositoryPort, "anchorRepositoryPort cannot be null");
        this.resolutionSourcePort = Objects.requireNonNull(resolutionSourcePort, "resolutionSourcePort cannot be null");
        this.resolver = Objects.requireNonNull(resolver, "resolver cannot be null");
    }

    /**
     * Resolves the anchor associated with the given root comment ID against current chapter content.
     *
     * @param rootCommentId scalar UUID of the root discussion comment
     * @return immutable {@link ChapterCommentAnchorResolution}
     * @throws ChapterCommentAnchorNotFoundException if no anchor exists for the root comment
     */
    @Transactional(readOnly = true)
    public ChapterCommentAnchorResolution resolve(UUID rootCommentId) {
        Objects.requireNonNull(rootCommentId, "rootCommentId cannot be null");

        ChapterCommentAnchor anchor = anchorRepositoryPort.findByRootCommentId(rootCommentId)
                .orElseThrow(() -> new ChapterCommentAnchorNotFoundException(rootCommentId));

        ChapterAnchorDocumentSnapshot snapshot = resolutionSourcePort.loadCurrent(anchor.getChapterId());

        return resolver.resolve(anchor, snapshot);
    }

    public ChapterCommentAnchorResolution execute(UUID rootCommentId) {
        return resolve(rootCommentId);
    }
}
