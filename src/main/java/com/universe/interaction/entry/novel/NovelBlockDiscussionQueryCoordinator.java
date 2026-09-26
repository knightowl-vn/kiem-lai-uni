package com.universe.interaction.entry.novel;

import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.interaction.application.query.CommentReadItem;
import com.universe.interaction.application.query.CommentThreadView;
import com.universe.interaction.application.query.GetBatchReactionSummariesUseCase;
import com.universe.interaction.application.query.GetCommentThreadsByRootIdsUseCase;
import com.universe.interaction.application.query.ReactionSummary;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.domain.reaction.ReactionTargetType;
import com.universe.interaction.entry.dto.ChapterBlockDiscussionResponseDTO;
import com.universe.interaction.entry.dto.CommentAuthorDTO;
import com.universe.interaction.entry.dto.CommentReadDTO;
import com.universe.interaction.entry.dto.CommentThreadResponseDTO;
import com.universe.interaction.entry.dto.ReactionSummaryResponseDTO;
import com.universe.novel.application.anchor.ChapterBlockDiscussionAnchorView;
import com.universe.novel.application.anchor.GetChapterBlockDiscussionAnchorsUseCase;
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
 * Cross-context composition coordinator for reading Novel block discussions.
 *
 * <p>Preserves bounded-context boundaries:
 * <ul>
 *   <li>Novel application has ZERO Interaction imports;</li>
 *   <li>Interaction application has ZERO Novel imports;</li>
 *   <li>Composition occurs cleanly at this entry layer;</li>
 *   <li>1 bulk Novel anchor resolution query + 1 bulk Interaction thread read + 1 bulk Identity author lookup + 1 bulk Interaction reaction summary read (no N+1 loop);</li>
 *   <li>Read-only operation without write transactional overhead.</li>
 * </ul>
 */
@Service
public class NovelBlockDiscussionQueryCoordinator {

    private static final Logger log = LoggerFactory.getLogger(NovelBlockDiscussionQueryCoordinator.class);

    private final GetChapterBlockDiscussionAnchorsUseCase getChapterBlockDiscussionAnchorsUseCase;
    private final GetCommentThreadsByRootIdsUseCase getCommentThreadsByRootIdsUseCase;
    private final UserIdentityContract userIdentityContract;
    private final GetBatchReactionSummariesUseCase getBatchReactionSummariesUseCase;

    public NovelBlockDiscussionQueryCoordinator(
            GetChapterBlockDiscussionAnchorsUseCase getChapterBlockDiscussionAnchorsUseCase,
            GetCommentThreadsByRootIdsUseCase getCommentThreadsByRootIdsUseCase,
            UserIdentityContract userIdentityContract,
            GetBatchReactionSummariesUseCase getBatchReactionSummariesUseCase
    ) {
        this.getChapterBlockDiscussionAnchorsUseCase = Objects.requireNonNull(
                getChapterBlockDiscussionAnchorsUseCase, "GetChapterBlockDiscussionAnchorsUseCase cannot be null"
        );
        this.getCommentThreadsByRootIdsUseCase = Objects.requireNonNull(
                getCommentThreadsByRootIdsUseCase, "GetCommentThreadsByRootIdsUseCase cannot be null"
        );
        this.userIdentityContract = Objects.requireNonNull(
                userIdentityContract, "UserIdentityContract cannot be null"
        );
        this.getBatchReactionSummariesUseCase = Objects.requireNonNull(
                getBatchReactionSummariesUseCase, "GetBatchReactionSummariesUseCase cannot be null"
        );
    }

    /**
     * Resolves the block discussion response for a chapter block by composing Novel block anchor
     * resolution with Interaction bulk visible thread retrieval and Identity public author enrichment.
     *
     * @param chapterId scalar UUID of the chapter
     * @param blockKey canonical Reader block key
     * @return immutable {@link ChapterBlockDiscussionResponseDTO}
     */
    public ChapterBlockDiscussionResponseDTO getBlockDiscussion(UUID chapterId, String blockKey) {
        return getBlockDiscussion(chapterId, blockKey, null);
    }

    /**
     * Resolves the block discussion response for a chapter block, optionally computing canEdit
     * capabilities based on the authenticated viewer's userId.
     *
     * @param chapterId scalar UUID of the chapter
     * @param blockKey canonical Reader block key
     * @param viewerUserId optional scalar UUID of the authenticated viewer (null for guests)
     * @return immutable {@link ChapterBlockDiscussionResponseDTO}
     */
    public ChapterBlockDiscussionResponseDTO getBlockDiscussion(UUID chapterId, String blockKey, UUID viewerUserId) {
        if (chapterId == null) {
            throw new IllegalArgumentException("chapterId cannot be null");
        }
        if (blockKey == null || blockKey.trim().isEmpty()) {
            throw new IllegalArgumentException("blockKey cannot be blank");
        }

        // 1. Novel application query: loads current chapter snapshot, validates requested blockKey,
        // and resolves eligible chapter anchors to root comment IDs.
        ChapterBlockDiscussionAnchorView anchorView = getChapterBlockDiscussionAnchorsUseCase.execute(chapterId, blockKey);

        // 2. If no anchors resolved to this block, return empty threads with canonical block metadata immediately
        if (anchorView.rootCommentIds().isEmpty()) {
            return new ChapterBlockDiscussionResponseDTO(
                    anchorView.chapterId(),
                    anchorView.contentVersion(),
                    anchorView.blockKey(),
                    anchorView.canonicalText(),
                    0,
                    0,
                    List.of()
            );
        }

        // 3. Interaction bulk visible thread read: loads visible root threads and their replies in bulk (no N+1 loop)
        CommentTarget target = CommentTarget.novelChapter(chapterId);
        List<CommentThreadView> threadViews = getCommentThreadsByRootIdsUseCase.execute(target, anchorView.rootCommentIds());

        // 4. Identity bulk author lookup + active comments collection for reactions: collect unique authorUserIds from roots and active replies (exclude tombstones)
        Set<UUID> authorUserIds = new HashSet<>();
        List<UUID> activeCommentIds = new ArrayList<>();
        for (CommentThreadView thread : threadViews) {
            if (thread.root() != null) {
                if (!thread.root().tombstone()) {
                    activeCommentIds.add(thread.root().id());
                }
                if (thread.root().authorUserId() != null) {
                    authorUserIds.add(thread.root().authorUserId());
                }
            }
            if (thread.replies() != null) {
                for (CommentReadItem reply : thread.replies()) {
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

        // 5. Interaction batch reaction summary lookup
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
                log.warn("Failed to load reaction summaries for block discussion: chapterId={}, blockKey={}", chapterId, blockKey, ex);
                reactionSummariesByCommentId = Map.of();
            }
        }

        final Map<UUID, ReactionSummaryResponseDTO> finalSummaries = reactionSummariesByCommentId;
        List<CommentThreadResponseDTO> threads = threadViews.stream()
                .map(threadView -> toEnrichedThreadDTO(threadView, authorsMap, viewerUserId, finalSummaries))
                .toList();

        int commentCount = 0;
        for (CommentThreadView threadView : threadViews) {
            commentCount += 1;
            if (threadView.replies() != null) {
                for (CommentReadItem reply : threadView.replies()) {
                    if (reply != null && !reply.tombstone()) {
                        commentCount += 1;
                    }
                }
            }
        }

        return new ChapterBlockDiscussionResponseDTO(
                anchorView.chapterId(),
                anchorView.contentVersion(),
                anchorView.blockKey(),
                anchorView.canonicalText(),
                threads.size(),
                commentCount,
                threads
        );
    }

    private CommentThreadResponseDTO toEnrichedThreadDTO(
            CommentThreadView threadView,
            Map<UUID, UserPublicProfileDTO> authorsMap,
            UUID viewerUserId,
            Map<UUID, ReactionSummaryResponseDTO> reactionSummaries
    ) {
        CommentReadDTO rootDTO = toEnrichedCommentDTO(threadView.root(), authorsMap, viewerUserId, reactionSummaries);
        List<CommentReadDTO> replyDTOs = threadView.replies().stream()
                .map(replyItem -> toEnrichedCommentDTO(replyItem, authorsMap, viewerUserId, reactionSummaries))
                .toList();
        return new CommentThreadResponseDTO(rootDTO, replyDTOs);
    }

    private CommentReadDTO toEnrichedCommentDTO(
            CommentReadItem item,
            Map<UUID, UserPublicProfileDTO> authorsMap,
            UUID viewerUserId,
            Map<UUID, ReactionSummaryResponseDTO> reactionSummaries
    ) {
        if (item == null) {
            return null;
        }

        if (item.tombstone()) {
            // Tombstone replies have no author presentation to prevent identity re-introduction
            return CommentReadDTO.from(item, viewerUserId);
        }

        UUID authorId = item.authorUserId();
        UserPublicProfileDTO profile = authorsMap != null ? authorsMap.get(authorId) : null;
        CommentAuthorDTO authorDTO;
        if (profile != null) {
            authorDTO = new CommentAuthorDTO(authorId, profile.displayName(), profile.avatarUrl());
        } else {
            authorDTO = CommentAuthorDTO.fallback(authorId);
        }

        ReactionSummaryResponseDTO reactionSummary = reactionSummaries != null ? reactionSummaries.get(item.id()) : null;
        return CommentReadDTO.from(item, authorDTO, viewerUserId, reactionSummary);
    }
}
