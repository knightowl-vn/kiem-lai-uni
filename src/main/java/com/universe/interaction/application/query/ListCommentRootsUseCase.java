package com.universe.interaction.application.query;

import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.application.ports.CommentSlice;
import com.universe.interaction.domain.CommentSortMode;
import com.universe.interaction.domain.CommentTarget;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * Use case to retrieve a zero-based slice of active root comments for a given target.
 *
 * <p>Preserves deterministic ordering and performance:
 * <ul>
 *   <li>Supports deterministic {@link CommentSortMode#FEATURED} and {@link CommentSortMode#NEWEST};</li>
 *   <li>Avoids N+1 queries by intentionally not loading thread replies for roots;</li>
 *   <li>Uses offset slice pagination without issuing COUNT(*) queries;</li>
 *   <li>Returns framework-free {@link CommentReadSlice}.</li>
 * </ul>
 */
@Service
public class ListCommentRootsUseCase {

    private final CommentRepositoryPort commentRepositoryPort;

    public ListCommentRootsUseCase(CommentRepositoryPort commentRepositoryPort) {
        this.commentRepositoryPort = Objects.requireNonNull(commentRepositoryPort, "CommentRepositoryPort cannot be null.");
    }

    @Transactional(readOnly = true)
    public CommentReadSlice execute(CommentTarget target, CommentSortMode sortMode, int page, int size) {
        if (target == null) {
            throw new IllegalArgumentException("CommentTarget cannot be null.");
        }
        if (sortMode == null) {
            sortMode = CommentSortMode.FEATURED;
        }
        if (page < 0) {
            throw new IllegalArgumentException("Page index cannot be negative: " + page);
        }
        if (size <= 0) {
            throw new IllegalArgumentException("Page size must be greater than zero: " + size);
        }

        CommentSlice domainSlice = commentRepositoryPort.findActiveRoots(target, sortMode, page, size);
        List<CommentReadItem> items = domainSlice.items().stream()
                .map(CommentReadItem::fromRoot)
                .toList();

        return new CommentReadSlice(items, domainSlice.page(), domainSlice.size(), domainSlice.hasNext());
    }

}
