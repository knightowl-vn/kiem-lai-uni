package com.universe.interaction.entry.novel;

import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.interaction.application.query.CommentReadItem;
import com.universe.interaction.application.query.CommentThreadView;
import com.universe.interaction.application.query.GetCommentThreadsByRootIdsUseCase;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.entry.dto.ChapterBlockDiscussionResponseDTO;
import com.universe.interaction.entry.dto.CommentAuthorDTO;
import com.universe.interaction.entry.dto.CommentReadDTO;
import com.universe.interaction.entry.dto.CommentThreadResponseDTO;
import com.universe.novel.application.anchor.ChapterBlockDiscussionAnchorView;
import com.universe.novel.application.anchor.GetChapterBlockDiscussionAnchorsUseCase;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Cross-context composition coordinator for reading Novel block discussions.
 *
 * <p>Preserves bounded-context boundaries:
 * <ul>
 *   <li>Novel application has ZERO Interaction imports;</li>
 *   <li>Interaction application has ZERO Novel imports;</li>
 *   <li>Composition occurs cleanly at this entry layer;</li>
 *   <li>1 bulk Novel anchor resolution query + 1 bulk Interaction thread read + 1 bulk Identity author lookup (no N+1 loop);</li>
 *   <li>Read-only operation without write transactional overhead.</li>
 * </ul>
 */
@Service
public class NovelBlockDiscussionQueryCoordinator {

    private final GetChapterBlockDiscussionAnchorsUseCase getChapterBlockDiscussionAnchorsUseCase;
    private final GetCommentThreadsByRootIdsUseCase getCommentThreadsByRootIdsUseCase;
    private final UserIdentityContract userIdentityContract;

    public NovelBlockDiscussionQueryCoordinator(
            GetChapterBlockDiscussionAnchorsUseCase getChapterBlockDiscussionAnchorsUseCase,
            GetCommentThreadsByRootIdsUseCase getCommentThreadsByRootIdsUseCase,
            UserIdentityContract userIdentityContract
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

        // 4. Identity bulk author lookup: collect unique authorUserIds from roots and active replies (exclude tombstones)
        Set<UUID> authorUserIds = new HashSet<>();
        for (CommentThreadView thread : threadViews) {
            if (thread.root() != null && thread.root().authorUserId() != null) {
                authorUserIds.add(thread.root().authorUserId());
            }
            if (thread.replies() != null) {
                for (CommentReadItem reply : thread.replies()) {
                    if (reply != null && !reply.tombstone() && reply.authorUserId() != null) {
                        authorUserIds.add(reply.authorUserId());
                    }
                }
            }
        }

        Map<UUID, UserPublicProfileDTO> authorsMap = authorUserIds.isEmpty()
                ? Map.of()
                : userIdentityContract.findPublicProfilesByIds(authorUserIds);

        List<CommentThreadResponseDTO> threads = threadViews.stream()
                .map(threadView -> toEnrichedThreadDTO(threadView, authorsMap, viewerUserId))
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
            UUID viewerUserId
    ) {
        CommentReadDTO rootDTO = toEnrichedCommentDTO(threadView.root(), authorsMap, viewerUserId);
        List<CommentReadDTO> replyDTOs = threadView.replies().stream()
                .map(replyItem -> toEnrichedCommentDTO(replyItem, authorsMap, viewerUserId))
                .toList();
        return new CommentThreadResponseDTO(rootDTO, replyDTOs);
    }

    private CommentReadDTO toEnrichedCommentDTO(
            CommentReadItem item,
            Map<UUID, UserPublicProfileDTO> authorsMap,
            UUID viewerUserId
    ) {
        boolean canEdit = viewerUserId != null &&
                !item.tombstone() &&
                viewerUserId.equals(item.authorUserId());

        if (item.tombstone()) {
            // Tombstone replies have no author presentation to prevent identity re-introduction
            return CommentReadDTO.from(item, null, false);
        }

        UUID authorId = item.authorUserId();
        UserPublicProfileDTO profile = authorsMap != null ? authorsMap.get(authorId) : null;
        CommentAuthorDTO authorDTO;
        if (profile != null) {
            authorDTO = new CommentAuthorDTO(authorId, profile.displayName(), profile.avatarUrl());
        } else {
            authorDTO = CommentAuthorDTO.fallback(authorId);
        }

        return CommentReadDTO.from(item, authorDTO, canEdit);
    }
}
