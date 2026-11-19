package com.universe.interaction.entry.shared;

import com.universe.interaction.application.query.CommentReadItem;
import com.universe.interaction.application.query.CommentThreadView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable participant and active-comment ID snapshot collected from visible discussion threads.
 *
 * <p>Excludes tombstoned comments from both active comment reaction lookups and public author
 * identity lookups while preserving deterministic slice/reply ordering for downstream batch calls.
 */
public record DiscussionParticipants(
        Set<UUID> authorUserIds,
        List<UUID> activeCommentIds
) {

    public static final DiscussionParticipants EMPTY = new DiscussionParticipants(Set.of(), List.of());

    public DiscussionParticipants {
        authorUserIds = authorUserIds == null ? Set.of() : Set.copyOf(authorUserIds);
        activeCommentIds = activeCommentIds == null ? List.of() : List.copyOf(activeCommentIds);
    }

    /**
     * Collects active author user IDs and active comment IDs across surviving root threads in slice order.
     *
     * @param survivingRootIds ordered root comment IDs
     * @param threadViewsByRootId resolved visible threads keyed by root ID
     * @return immutable {@link DiscussionParticipants} snapshot
     */
    public static DiscussionParticipants collect(
            List<UUID> survivingRootIds,
            Map<UUID, CommentThreadView> threadViewsByRootId
    ) {
        if (survivingRootIds == null || survivingRootIds.isEmpty()
                || threadViewsByRootId == null || threadViewsByRootId.isEmpty()) {
            return EMPTY;
        }

        Set<UUID> authorUserIds = new LinkedHashSet<>();
        List<UUID> activeCommentIds = new ArrayList<>();

        for (UUID rootId : survivingRootIds) {
            if (rootId == null) {
                continue;
            }
            CommentThreadView threadView = threadViewsByRootId.get(rootId);
            appendThreadParticipants(threadView, authorUserIds, activeCommentIds);
        }

        if (authorUserIds.isEmpty() && activeCommentIds.isEmpty()) {
            return EMPTY;
        }
        return new DiscussionParticipants(authorUserIds, activeCommentIds);
    }

    /**
     * Collects active author user IDs and active comment IDs from a single visible thread.
     *
     * @param threadView resolved visible thread (may be null)
     * @return immutable {@link DiscussionParticipants} snapshot
     */
    public static DiscussionParticipants collectForSingleThread(CommentThreadView threadView) {
        if (threadView == null) {
            return EMPTY;
        }

        Set<UUID> authorUserIds = new LinkedHashSet<>();
        List<UUID> activeCommentIds = new ArrayList<>();
        appendThreadParticipants(threadView, authorUserIds, activeCommentIds);

        if (authorUserIds.isEmpty() && activeCommentIds.isEmpty()) {
            return EMPTY;
        }
        return new DiscussionParticipants(authorUserIds, activeCommentIds);
    }

    /**
     * Collects active author user IDs and active comment IDs across an ordered list of visible threads.
     *
     * @param threadViews ordered visible thread views (may be null or empty)
     * @return immutable {@link DiscussionParticipants} snapshot
     */
    public static DiscussionParticipants collectForThreads(List<CommentThreadView> threadViews) {
        if (threadViews == null || threadViews.isEmpty()) {
            return EMPTY;
        }

        Set<UUID> authorUserIds = new LinkedHashSet<>();
        List<UUID> activeCommentIds = new ArrayList<>();
        for (CommentThreadView threadView : threadViews) {
            appendThreadParticipants(threadView, authorUserIds, activeCommentIds);
        }

        if (authorUserIds.isEmpty() && activeCommentIds.isEmpty()) {
            return EMPTY;
        }
        return new DiscussionParticipants(authorUserIds, activeCommentIds);
    }

    /**
     * Collects active author user IDs and active root comment IDs strictly from root items
     * without hydrating or collecting any reply participants.
     *
     * @param roots ordered root comment items (may be null or empty)
     * @return immutable {@link DiscussionParticipants} snapshot for roots only
     */
    public static DiscussionParticipants collectForRoots(List<CommentReadItem> roots) {
        if (roots == null || roots.isEmpty()) {
            return EMPTY;
        }

        Set<UUID> authorUserIds = new LinkedHashSet<>();
        List<UUID> activeCommentIds = new ArrayList<>(roots.size());
        for (CommentReadItem root : roots) {
            if (root != null && !root.tombstone()) {
                activeCommentIds.add(root.id());
                if (root.authorUserId() != null) {
                    authorUserIds.add(root.authorUserId());
                }
            }
        }

        if (authorUserIds.isEmpty() && activeCommentIds.isEmpty()) {
            return EMPTY;
        }
        return new DiscussionParticipants(authorUserIds, activeCommentIds);
    }

    private static void appendThreadParticipants(
            CommentThreadView threadView,
            Set<UUID> authorUserIds,
            List<UUID> activeCommentIds
    ) {
        if (threadView == null) {
            return;
        }
        CommentReadItem root = threadView.root();
        if (root != null) {
            if (!root.tombstone()) {
                activeCommentIds.add(root.id());
            }
            if (root.authorUserId() != null) {
                authorUserIds.add(root.authorUserId());
            }
        }
        List<CommentReadItem> replies = threadView.replies();
        if (replies != null) {
            for (CommentReadItem reply : replies) {
                if (reply != null && !reply.tombstone()) {
                    activeCommentIds.add(reply.id());
                    if (reply.authorUserId() != null) {
                        authorUserIds.add(reply.authorUserId());
                    }
                }
            }
        }
    }
}
