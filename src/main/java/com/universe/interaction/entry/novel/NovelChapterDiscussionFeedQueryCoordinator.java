package com.universe.interaction.entry.novel;

import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.interaction.application.query.CommentReadItem;
import com.universe.interaction.application.query.CommentReadSlice;
import com.universe.interaction.application.query.CountVisibleActiveRepliesByRootIdsUseCase;
import com.universe.interaction.application.query.ListCommentRootsUseCase;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.entry.dto.ChapterDiscussionFeedItemDTO;
import com.universe.interaction.entry.dto.ChapterDiscussionFeedResponseDTO;
import com.universe.interaction.entry.dto.CommentAuthorDTO;
import com.universe.novel.application.anchor.ResolveChapterCommentAnchorsByRootIdsUseCase;
import com.universe.novel.application.anchor.ResolvedChapterCommentAnchorView;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
 *   <li>No per-feed-item root, reply-count, anchor, or author queries;</li>
 *   <li>Chapter document snapshot loaded at most once per request inside Novel application;</li>
 *   <li>Read-only operation without write transactional overhead.</li>
 * </ul>
 */
@Service
public class NovelChapterDiscussionFeedQueryCoordinator {

    static final int MAX_PASSAGE_LENGTH = 140;
    static final int MAX_TRUNCATED_CONTENT_LENGTH = 137;

    private final ListCommentRootsUseCase listCommentRootsUseCase;
    private final CountVisibleActiveRepliesByRootIdsUseCase countVisibleActiveRepliesByRootIdsUseCase;
    private final ResolveChapterCommentAnchorsByRootIdsUseCase resolveChapterCommentAnchorsByRootIdsUseCase;
    private final UserIdentityContract userIdentityContract;

    public NovelChapterDiscussionFeedQueryCoordinator(
            ListCommentRootsUseCase listCommentRootsUseCase,
            CountVisibleActiveRepliesByRootIdsUseCase countVisibleActiveRepliesByRootIdsUseCase,
            ResolveChapterCommentAnchorsByRootIdsUseCase resolveChapterCommentAnchorsByRootIdsUseCase,
            UserIdentityContract userIdentityContract
    ) {
        this.listCommentRootsUseCase = Objects.requireNonNull(
                listCommentRootsUseCase, "ListCommentRootsUseCase cannot be null"
        );
        this.countVisibleActiveRepliesByRootIdsUseCase = Objects.requireNonNull(
                countVisibleActiveRepliesByRootIdsUseCase, "CountVisibleActiveRepliesByRootIdsUseCase cannot be null"
        );
        this.resolveChapterCommentAnchorsByRootIdsUseCase = Objects.requireNonNull(
                resolveChapterCommentAnchorsByRootIdsUseCase, "ResolveChapterCommentAnchorsByRootIdsUseCase cannot be null"
        );
        this.userIdentityContract = Objects.requireNonNull(
                userIdentityContract, "UserIdentityContract cannot be null"
        );
    }

    /**
     * Resolves a paginated slice of chapter discussion feed items.
     *
     * @param chapterId scalar UUID of the chapter
     * @param page zero-based page index
     * @param size page size
     * @return immutable {@link ChapterDiscussionFeedResponseDTO}
     */
    public ChapterDiscussionFeedResponseDTO getDiscussionFeed(UUID chapterId, int page, int size) {
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

        // 2. Interaction query: batch count active replies for all roots on this page (1 batch query, zero N+1)
        Map<UUID, Long> replyCountsByRootId = countVisibleActiveRepliesByRootIdsUseCase.execute(rootIds);

        // 3. Identity contract: batch lookup public profiles for unique authors (1 batch query, zero N+1)
        Set<UUID> authorUserIds = roots.stream()
                .map(CommentReadItem::authorUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<UUID, UserPublicProfileDTO> authorsMap = authorUserIds.isEmpty()
                ? Map.of()
                : userIdentityContract.findPublicProfilesByIds(authorUserIds);

        // 4. Novel application: batch lookup and resolve anchors for roots on this page (1 batch call, zero N+1)
        List<ResolvedChapterCommentAnchorView> resolvedAnchors =
                resolveChapterCommentAnchorsByRootIdsUseCase.execute(chapterId, rootIds);

        Map<UUID, ResolvedChapterCommentAnchorView> resolvedAnchorsByRootId = resolvedAnchors.stream()
                .collect(Collectors.toMap(
                        ResolvedChapterCommentAnchorView::rootCommentId,
                        a -> a,
                        (existing, replacement) -> existing
                ));

        // 5. Assemble feed items preserving original slice ordering
        List<ChapterDiscussionFeedItemDTO> items = new ArrayList<>(roots.size());
        for (CommentReadItem root : roots) {
            UUID rootId = root.id();
            int replyCount = replyCountsByRootId.getOrDefault(rootId, 0L).intValue();

            // Author presentation
            UserPublicProfileDTO profile = (authorsMap != null && root.authorUserId() != null)
                    ? authorsMap.get(root.authorUserId())
                    : null;
            CommentAuthorDTO authorDTO = (profile != null)
                    ? new CommentAuthorDTO(root.authorUserId(), profile.displayName(), profile.avatarUrl())
                    : CommentAuthorDTO.fallback(root.authorUserId());

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

            boolean edited = root.updatedAt() != null && root.updatedAt().isAfter(root.createdAt());

            items.add(new ChapterDiscussionFeedItemDTO(
                    rootId,
                    authorDTO,
                    root.body() != null ? root.body() : "",
                    root.createdAt(),
                    root.updatedAt(),
                    edited,
                    replyCount,
                    anchorStatus,
                    blockKey,
                    passageExcerpt
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
