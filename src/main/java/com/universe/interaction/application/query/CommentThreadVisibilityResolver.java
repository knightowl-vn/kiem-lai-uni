package com.universe.interaction.application.query;

import com.universe.interaction.application.exceptions.CommentThreadIntegrityException;
import com.universe.interaction.domain.Comment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Pure application-level resolver for flat comment thread visibility and tombstone retention.
 *
 * <p>Enforces approved visibility rules:
 * <ul>
 *   <li>All {@code ACTIVE} replies remain visible;</li>
 *   <li>Deleted leaf replies with no active descendants are pruned/hidden;</li>
 *   <li>Deleted non-root replies that are transitive ancestors of an active reply are retained as tombstones;</li>
 *   <li>Immediate reply-to author attribution is resolved from the immediate parent comment;</li>
 *   <li>Original chronological ordering ({@code createdAt ASC, id ASC}) is strictly preserved without re-sorting;</li>
 *   <li>Strict graph integrity verification detects cycles, orphan references, foreign targets, and corrupt hierarchy.</li>
 * </ul>
 */
@Component
public class CommentThreadVisibilityResolver {

    /**
     * Resolves the visible flat replies for an active root comment and complete thread replies list.
     *
     * @param root active root comment aggregate (cannot be null)
     * @param replies complete ordered replies list returned from persistence (cannot be null)
     * @return unmodifiable list of visible read items preserving original persistence ordering
     */
    public List<CommentReadItem> resolve(Comment root, List<Comment> replies) {
        Objects.requireNonNull(root, "Root comment cannot be null.");
        Objects.requireNonNull(replies, "Replies list cannot be null.");

        if (!root.isRoot()) {
            throw new CommentThreadIntegrityException("Supplied root is not a root comment: " + root.getId());
        }
        if (root.isDeleted()) {
            throw new IllegalArgumentException("Root comment must be ACTIVE for visibility resolution: " + root.getId());
        }

        if (replies.isEmpty()) {
            return Collections.emptyList();
        }

        Map<UUID, Comment> replyMap = new LinkedHashMap<>();
        validateGraphIntegrity(root, replies, replyMap);

        // Mark visible replies: active replies and their transitive ancestors
        Set<UUID> visibleIds = new HashSet<>();
        for (Comment reply : replies) {
            if (reply.isActive()) {
                visibleIds.add(reply.getId());
                UUID curr = reply.getParentCommentId();
                while (!curr.equals(root.getId())) {
                    if (!visibleIds.add(curr)) {
                        // Already marked visible, all its ancestors were already traversed
                        break;
                    }
                    Comment parentComment = replyMap.get(curr);
                    curr = parentComment.getParentCommentId();
                }
            }
        }

        // Map visible replies into CommentReadItems preserving original persistence order
        List<CommentReadItem> result = new ArrayList<>(visibleIds.size());
        for (Comment reply : replies) {
            if (visibleIds.contains(reply.getId())) {
                UUID parentId = reply.getParentCommentId();
                UUID replyToAuthorUserId = parentId.equals(root.getId())
                        ? root.getAuthorUserId()
                        : replyMap.get(parentId).getAuthorUserId();

                CommentReadItem item = reply.isActive()
                        ? CommentReadItem.fromActiveReply(reply, replyToAuthorUserId)
                        : CommentReadItem.fromTombstoneReply(reply, replyToAuthorUserId);
                result.add(item);
            }
        }

        return Collections.unmodifiableList(result);
    }

    private void validateGraphIntegrity(Comment root, List<Comment> replies, Map<UUID, Comment> replyMap) {
        for (Comment reply : replies) {
            if (reply == null) {
                throw new CommentThreadIntegrityException("Encountered null reply in thread list for root: " + root.getId());
            }
            if (!reply.isReply()) {
                throw new CommentThreadIntegrityException("Supplied reply list contains a root comment: " + reply.getId());
            }
            if (!root.getId().equals(reply.getThreadRootCommentId())) {
                throw new CommentThreadIntegrityException(
                        "Reply " + reply.getId() + " threadRootCommentId (" + reply.getThreadRootCommentId()
                                + ") does not match root ID (" + root.getId() + ")."
                );
            }
            if (!root.getTarget().equals(reply.getTarget())) {
                throw new CommentThreadIntegrityException(
                        "Reply " + reply.getId() + " target (" + reply.getTarget()
                                + ") does not match root target (" + root.getTarget() + ")."
                );
            }
            if (replyMap.put(reply.getId(), reply) != null) {
                throw new CommentThreadIntegrityException("Duplicate reply ID found in thread: " + reply.getId());
            }
        }

        // Validate ancestry termination at root and detect cycles
        Set<UUID> validated = new HashSet<>();
        for (Comment reply : replies) {
            Set<UUID> currentPath = new LinkedHashSet<>();
            UUID curr = reply.getId();
            while (curr != null && !curr.equals(root.getId())) {
                if (validated.contains(curr)) {
                    break;
                }
                if (!currentPath.add(curr)) {
                    throw new CommentThreadIntegrityException("Parent cycle detected at comment: " + curr);
                }
                Comment currComment = replyMap.get(curr);
                if (currComment == null) {
                    throw new CommentThreadIntegrityException(
                            "Immediate parent " + curr + " not found in thread for reply " + reply.getId()
                    );
                }
                curr = currComment.getParentCommentId();
            }
            validated.addAll(currentPath);
        }
    }
}
