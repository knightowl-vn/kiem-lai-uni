package com.universe.interaction.entry.novel;

import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.interaction.application.query.CommentReadItem;
import com.universe.interaction.application.query.CommentReadSlice;
import com.universe.interaction.application.query.CommentThreadView;
import com.universe.interaction.application.query.GetBatchReactionSummariesUseCase;
import com.universe.interaction.application.query.GetCommentThreadsByRootIdsUseCase;
import com.universe.interaction.application.query.ListCommentRootsUseCase;
import com.universe.interaction.application.query.ReactionSummary;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.domain.reaction.ReactionTargetType;
import com.universe.interaction.entry.dto.ChapterDiscussionFeedItemDTO;
import com.universe.interaction.entry.dto.ChapterDiscussionFeedResponseDTO;
import com.universe.interaction.entry.dto.CommentAuthorDTO;
import com.universe.interaction.entry.dto.CommentReadDTO;
import com.universe.interaction.entry.dto.ReactionSummaryResponseDTO;
import com.universe.novel.application.anchor.ResolveChapterCommentAnchorsByRootIdsUseCase;
import com.universe.novel.application.anchor.ResolvedChapterCommentAnchorView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Cross-context query composition coordinator for reading chapter-level discussion feeds.
 *
 * <p>Preserves bounded-context boundaries and performance contracts:
 * <ul>
 *   <li>Interaction application has ZERO Novel imports;</li>
 *   <li>Novel application has ZERO Interaction imports;</li>
 *   <li>Entry-layer composition uses only scalar cross-context references (UUIDs);</li>
 *   <li>One root Slice query + one batch visible thread query + one batch anchor resolution + one Identity profile query + one batch reaction summary query;</li>
 *   <li>Zero per-root N+1 access loops;</li>
 *   <li>Read-only operation without write transactional overhead.</li>
 * </ul>
 */
@Service
public class NovelChapterDiscussionFeedQueryCoordinator {

    private static final Logger log = LoggerFactory.getLogger(NovelChapterDiscussionFeedQueryCoordinator.class);

    static final int MAX_PASSAGE_LENGTH = 140;
    static final int MAX_TRUNCATED_CONTENT_LENGTH = 137;

    private final ListCommentRootsUseCase listCommentRootsUseCase;
    private final GetCommentThreadsByRootIdsUseCase getCommentThreadsByRootIdsUseCase;
    private final ResolveChapterCommentAnchorsByRootIdsUseCase resolveChapterCommentAnchorsByRootIdsUseCase;
    private final UserIdentityContract userIdentityContract;
    private final GetBatchReactionSummariesUseCase getBatchReactionSummariesUseCase;

    public NovelChapterDiscussionFeedQueryCoordinator(
            ListCommentRootsUseCase listCommentRootsUseCase,
            GetCommentThreadsByRootIdsUseCase getCommentThreadsByRootIdsUseCase,
            ResolveChapterCommentAnchorsByRootIdsUseCase resolveChapterCommentAnchorsByRootIdsUseCase,
            UserIdentityContract userIdentityContract,
            GetBatchReactionSummariesUseCase getBatchReactionSummariesUseCase
    ) {
        this.listCommentRootsUseCase = Objects.requireNonNull(
                listCommentRootsUseCase, "ListCommentRootsUseCase cannot be null"
        );
        this.getCommentThreadsByRootIdsUseCase = Objects.requireNonNull(
                getCommentThreadsByRootIdsUseCase, "GetCommentThreadsByRootIdsUseCase cannot be null"
        );
        this.resolveChapterCommentAnchorsByRootIdsUseCase = Objects.requireNonNull(
                resolveChapterCommentAnchorsByRootIdsUseCase, "ResolveChapterCommentAnchorsByRootIdsUseCase cannot be null"
        );
        this.userIdentityContract = Objects.requireNonNull(
                userIdentityContract, "UserIdentityContract cannot be null"
        );
        this.getBatchReactionSummariesUseCase = Objects.requireNonNull(
                getBatchReactionSummariesUseCase, "GetBatchReactionSummariesUseCase cannot be null"
        );
    }

    /**
     * Resolves a paginated slice of chapter discussion feed items for anonymous guests.
     *
     * @param chapterId scalar UUID of the chapter
     * @param page zero-based page index
     * @param size page size
     * @return immutable {@link ChapterDiscussionFeedResponseDTO}
     */
    public ChapterDiscussionFeedResponseDTO getDiscussionFeed(UUID chapterId, int page, int size) {
        return getDiscussionFeed(chapterId, page, size, null);
    }

    /**
     * Resolves a paginated slice of chapter discussion feed items with optional viewer capabilities.
     *
     * @param chapterId scalar UUID of the chapter
     * @param page zero-based page index
     * @param size page size
     * @param viewerUserId optional scalar UUID of the authenticated viewer (null for guests)
     * @return immutable {@link ChapterDiscussionFeedResponseDTO}
     */
    public ChapterDiscussionFeedResponseDTO getDiscussionFeed(UUID chapterId, int page, int size, UUID viewerUserId) {
        if (chapterId == null) {
            throw new IllegalArgumentException("chapterId cannot be null");
        }
        if (page < 0) {
            throw new IllegalArgumentException("page cannot be negative: " + page);
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be greater than zero: " + size);
        }

        // 1. Interaction query: load active root comments slice for this chapter
        CommentTarget target = CommentTarget.novelChapter(chapterId);
        CommentReadSlice rootSlice = listCommentRootsUseCase.execute(target, page, size);

        if (rootSlice.items().isEmpty()) {
            return new ChapterDiscussionFeedResponseDTO(
                    List.of(),
                    rootSlice.page(),
                    rootSlice.size(),
                    rootSlice.hasNext()
            );
        }

        List<CommentReadItem> roots = rootSlice.items();
        List<UUID> rootIds = roots.stream().map(CommentReadItem::id).toList();

        // 2. Interaction query: batch retrieve visible threads for all roots in this slice
        List<CommentThreadView> threadViews = getCommentThreadsByRootIdsUseCase.execute(target, rootIds);
        Map<UUID, CommentThreadView> threadViewsByRootId = threadViews.stream()
                .collect(Collectors.toMap(
                        tv -> tv.root().id(),
                        tv -> tv,
                        (existing, replacement) -> existing
                ));

        // 3. Concurrent delete safety: preserve slice ordering among surviving roots only
        List<UUID> survivingRootIds = rootIds.stream()
                .filter(threadViewsByRootId::containsKey)
                .toList();

        if (survivingRootIds.isEmpty()) {
            return new ChapterDiscussionFeedResponseDTO(
                    List.of(),
                    rootSlice.page(),
                    rootSlice.size(),
                    rootSlice.hasNext()
            );
        }

        // 4. Identity contract: batch lookup public profiles for unique authors (roots + active replies)
        Set<UUID> authorUserIds = new HashSet<>();
        List<UUID> activeCommentIds = new ArrayList<>();

        for (UUID rootId : survivingRootIds) {
            CommentThreadView threadView = threadViewsByRootId.get(rootId);
            if (threadView.root() != null) {
                if (!threadView.root().tombstone()) {
                    activeCommentIds.add(threadView.root().id());
                }
                if (threadView.root().authorUserId() != null) {
                    authorUserIds.add(threadView.root().authorUserId());
                }
            }
            if (threadView.replies() != null) {
                for (CommentReadItem reply : threadView.replies()) {
                    if (reply != null && !reply.tombstone()) {
                        activeCommentIds.add(reply.id());
                        if (reply.authorUserId() != null) {
                            authorUserIds.add(reply.authorUserId());
                        }
                    }
                }
            }
        }

        Map<UUID, UserPublicProfileDTO> authorsMap = authorUserIds.isEmpty()
                ? Map.of()
                : userIdentityContract.findPublicProfilesByIds(authorUserIds);

        // 5. Interaction query: batch lookup reaction summaries for all active comments (surviving roots + active replies)
        Map<UUID, ReactionSummaryResponseDTO> reactionSummariesByCommentId = Map.of();
        if (!activeCommentIds.isEmpty()) {
            try {
                Map<UUID, ReactionSummary> summaries = getBatchReactionSummariesUseCase.execute(
                        ReactionTargetType.COMMENT,
                        activeCommentIds,
                        viewerUserId
                );
                if (summaries != null && !summaries.isEmpty()) {
                    reactionSummariesByCommentId = summaries.entrySet().stream()
                            .filter(e -> e.getValue() != null)
                            .collect(Collectors.toMap(
                                    Map.Entry::getKey,
                                    e -> ReactionSummaryResponseDTO.from(e.getValue())
                            ));
                }
            } catch (RuntimeException ex) {
                log.warn("Failed to load reaction summaries for chapter discussion feed: chapterId={}", chapterId, ex);
                reactionSummariesByCommentId = Map.of();
            }
        }

        // 6. Novel application: batch lookup and resolve anchors ONLY for surviving roots on this page
        List<ResolvedChapterCommentAnchorView> resolvedAnchors =
                resolveChapterCommentAnchorsByRootIdsUseCase.execute(chapterId, survivingRootIds);

        Map<UUID, ResolvedChapterCommentAnchorView> resolvedAnchorsByRootId = resolvedAnchors.stream()
                .collect(Collectors.toMap(
                        ResolvedChapterCommentAnchorView::rootCommentId,
                        a -> a,
                        (existing, replacement) -> existing
                ));

        // 7. Assemble feed items preserving original slice ordering
        List<ChapterDiscussionFeedItemDTO> items = new ArrayList<>(survivingRootIds.size());
        for (UUID rootId : survivingRootIds) {
            CommentThreadView threadView = threadViewsByRootId.get(rootId);
            CommentReadItem authoritativeRoot = threadView.root();

            // Author presentation for root
            UserPublicProfileDTO rootProfile = (authorsMap != null && authoritativeRoot.authorUserId() != null)
                    ? authorsMap.get(authoritativeRoot.authorUserId())
                    : null;
            CommentAuthorDTO rootAuthorDTO = (rootProfile != null)
                    ? new CommentAuthorDTO(authoritativeRoot.authorUserId(), rootProfile.displayName(), rootProfile.avatarUrl(), rootProfile.publicHandle())
                    : CommentAuthorDTO.fallback(authoritativeRoot.authorUserId());

            ReactionSummaryResponseDTO rootReactionSummary = reactionSummariesByCommentId.get(rootId);

            // Canonical root CommentReadDTO for capability and data resolution
            CommentReadDTO rootReadDTO = CommentReadDTO.from(authoritativeRoot, rootAuthorDTO, viewerUserId, rootReactionSummary);

            // Nested replies mapping
            List<CommentReadDTO> replyDTOs = new ArrayList<>();
            int activeReplyCount = 0;
            if (threadView.replies() != null) {
                for (CommentReadItem replyItem : threadView.replies()) {
                    if (replyItem == null) {
                        continue;
                    }
                    if (replyItem.tombstone()) {
                        // Tombstone reply: privacy-safe, author null, canEdit=false, canDelete=false, reactionSummary=null
                        replyDTOs.add(CommentReadDTO.from(replyItem, viewerUserId));
                    } else {
                        activeReplyCount++;
                        UserPublicProfileDTO replyProfile = (authorsMap != null && replyItem.authorUserId() != null)
                                ? authorsMap.get(replyItem.authorUserId())
                                : null;
                        CommentAuthorDTO replyAuthorDTO = (replyProfile != null)
                                ? new CommentAuthorDTO(replyItem.authorUserId(), replyProfile.displayName(), replyProfile.avatarUrl(), replyProfile.publicHandle())
                                : CommentAuthorDTO.fallback(replyItem.authorUserId());
                        ReactionSummaryResponseDTO replyReactionSummary = reactionSummariesByCommentId.get(replyItem.id());
                        replyDTOs.add(CommentReadDTO.from(replyItem, replyAuthorDTO, viewerUserId, replyReactionSummary));
                    }
                }
            }

            // Anchor presentation
            ResolvedChapterCommentAnchorView anchorView = resolvedAnchorsByRootId.get(rootId);
            String anchorStatus;
            String blockKey;
            String passageExcerpt;
            if (anchorView != null) {
                anchorStatus = anchorView.status().name();
                blockKey = anchorView.resolvedBlockKey();
                passageExcerpt = truncatePassage(anchorView.passageText());
            } else {
                anchorStatus = "UNANCHORED";
                blockKey = null;
                passageExcerpt = null;
            }

            boolean edited = rootReadDTO.updatedAt() != null && rootReadDTO.updatedAt().isAfter(rootReadDTO.createdAt());

            items.add(new ChapterDiscussionFeedItemDTO(
                    rootId,
                    rootAuthorDTO,
                    rootReadDTO.body() != null ? rootReadDTO.body() : "",
                    rootReadDTO.createdAt(),
                    rootReadDTO.updatedAt(),
                    edited,
                    rootReadDTO.canEdit(),
                    rootReadDTO.canDelete(),
                    activeReplyCount,
                    anchorStatus,
                    blockKey,
                    passageExcerpt,
                    replyDTOs,
                    rootReactionSummary
            ));
        }

        return new ChapterDiscussionFeedResponseDTO(
                items,
                rootSlice.page(),
                rootSlice.size(),
                rootSlice.hasNext()
        );
    }

    /**
     * Deterministically truncates secondary passage text to a bounded length.
     *
     * @param text input passage text
     * @return truncated text with ellipsis if length exceeds maximum, or original trimmed text
     */
    public static String truncatePassage(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.length() <= MAX_PASSAGE_LENGTH) {
            return trimmed;
        }
        String sub = trimmed.substring(0, MAX_TRUNCATED_CONTENT_LENGTH);
        int lastSpace = sub.lastIndexOf(' ');
        if (lastSpace > 0 && lastSpace >= (int) (MAX_TRUNCATED_CONTENT_LENGTH * 0.6)) {
            return sub.substring(0, lastSpace).stripTrailing() + "...";
        }
        return sub.stripTrailing() + "...";
    }
}
