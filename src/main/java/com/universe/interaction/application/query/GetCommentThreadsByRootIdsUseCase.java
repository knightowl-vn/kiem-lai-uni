package com.universe.interaction.application.query;

import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentTarget;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Interaction application query use case to retrieve discussion threads for a collection
 * of root comment IDs in bulk.
 *
 * <p>Preserves clean architecture, ordering, and performance invariants:
 * <ul>
 *   <li>Executes at most 2 bulk queries (one for roots, one for replies), avoiding N+1 loops;</li>
 *   <li>Excludes deleted or non-root comments from root positions;</li>
 *   <li>Preserves target scope: comments belonging to another target cannot be returned;</li>
 *   <li>Preserves standard root ordering ({@code createdAt DESC, id DESC}) and reply ordering ({@code createdAt ASC, id ASC});</li>
 *   <li>Delegates flat reply tombstone retention to {@link CommentThreadVisibilityResolver}.</li>
 * </ul>
 */
@Service
public class GetCommentThreadsByRootIdsUseCase {

    private final CommentRepositoryPort commentRepositoryPort;
    private final CommentThreadVisibilityResolver visibilityResolver;

    public GetCommentThreadsByRootIdsUseCase(
            CommentRepositoryPort commentRepositoryPort,
            CommentThreadVisibilityResolver visibilityResolver
    ) {
        this.commentRepositoryPort = Objects.requireNonNull(commentRepositoryPort, "CommentRepositoryPort cannot be null.");
        this.visibilityResolver = Objects.requireNonNull(visibilityResolver, "CommentThreadVisibilityResolver cannot be null.");
    }

    /**
     * Retrieves visible discussion threads for the specified root comment IDs under the target.
     *
     * @param target comment target (cannot be null)
     * @param rootCommentIds collection of root comment IDs to load
     * @return unmodifiable list of visible discussion thread views
     */
    @Transactional(readOnly = true)
    public List<CommentThreadView> execute(CommentTarget target, Collection<UUID> rootCommentIds) {
        if (target == null) {
            throw new IllegalArgumentException("CommentTarget cannot be null.");
        }
        if (rootCommentIds == null) {
            throw new IllegalArgumentException("Root comment IDs collection cannot be null.");
        }
        if (rootCommentIds.isEmpty()) {
            return List.of();
        }

        List<Comment> roots = commentRepositoryPort.findActiveRootsByIds(target, rootCommentIds);
        if (roots.isEmpty()) {
            return List.of();
        }

        List<UUID> activeRootIds = roots.stream()
                .map(Comment::getId)
                .toList();

        List<Comment> allReplies = commentRepositoryPort.findThreadRepliesByRootIds(activeRootIds);

        Map<UUID, List<Comment>> repliesByRootId = new LinkedHashMap<>();
        for (Comment reply : allReplies) {
            repliesByRootId.computeIfAbsent(reply.getThreadRootCommentId(), k -> new ArrayList<>())
                    .add(reply);
        }

        List<CommentThreadView> result = new ArrayList<>(roots.size());
        for (Comment root : roots) {
            List<Comment> threadReplies = repliesByRootId.getOrDefault(root.getId(), Collections.emptyList());
            List<CommentReadItem> visibleReplies = visibilityResolver.resolve(root, threadReplies);
            CommentReadItem rootItem = CommentReadItem.fromRoot(root);
            result.add(new CommentThreadView(rootItem, visibleReplies));
        }

        return Collections.unmodifiableList(result);
    }
}
