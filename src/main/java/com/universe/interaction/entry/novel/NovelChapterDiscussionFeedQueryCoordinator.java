package com.universe.interaction.entry.novel;

import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.interaction.application.query.CommentReadItem;
import com.universe.interaction.application.query.CommentThreadView;
import com.universe.interaction.application.query.GetBatchReactionSummariesUseCase;
import com.universe.interaction.application.query.GetCommentThreadsByRootIdsUseCase;
import com.universe.interaction.application.query.ListCommentRootsUseCase;
import com.universe.interaction.domain.CommentSortMode;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.entry.dto.ChapterDiscussionFeedItemDTO;
import com.universe.interaction.entry.dto.ChapterDiscussionFeedResponseDTO;
import com.universe.interaction.entry.dto.CommentAuthorDTO;
import com.universe.interaction.entry.dto.CommentReadDTO;
import com.universe.interaction.entry.dto.CommentThreadResponseDTO;
import com.universe.interaction.entry.dto.ReactionSummaryResponseDTO;
import com.universe.interaction.entry.shared.CommentReactionEnrichmentService;
import com.universe.interaction.entry.shared.CommentReactionEnrichmentService.ReactionEnrichmentResult;
import com.universe.interaction.entry.shared.CommentThreadDtoAssembler;
import com.universe.interaction.entry.shared.DiscussionCoreReadService;
import com.universe.interaction.entry.shared.DiscussionCoreReadService.CoreThreadedFeedResult;
import com.universe.novel.application.anchor.ResolveChapterCommentAnchorsByRootIdsUseCase;
import com.universe.novel.application.anchor.ResolvedChapterCommentAnchorView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Cross-context query composition coordinator for reading chapter-level discussion feeds.
 *
 * <p>Preserves bounded-context boundaries and performance contracts:
 * <ul>
 *   <li>Interaction application has ZERO Novel imports;</li>
 *   <li>Novel application has ZERO Interaction imports;</li>
 *   <li>Entry-layer composition uses only scalar cross-context references (UUIDs);</li>
 *   <li>Delegates Phase A core reads (one {@code @Transactional(readOnly = true)} transaction covering
 *       root slice pagination, batched visible threads, batched surviving-root anchor resolution,
 *       participant collection, and batched Identity profiles) to {@link DiscussionCoreReadService},
 *       followed sequentially by Phase B best-effort Reaction enrichment via
 *       {@link CommentReactionEnrichmentService};</li>
 *   <li>Zero per-root N+1 access loops.</li>
 * </ul>
 */
@Service
public class NovelChapterDiscussionFeedQueryCoordinator {

    static final int MAX_PASSAGE_LENGTH = 140;
    static final int MAX_TRUNCATED_CONTENT_LENGTH = 137;

    private final DiscussionCoreReadService discussionCoreReadService;
    private final CommentReactionEnrichmentService commentReactionEnrichmentService;
    private final ResolveChapterCommentAnchorsByRootIdsUseCase resolveChapterCommentAnchorsByRootIdsUseCase;

    @Autowired
    public NovelChapterDiscussionFeedQueryCoordinator(
            DiscussionCoreReadService discussionCoreReadService,
            CommentReactionEnrichmentService commentReactionEnrichmentService,
            ResolveChapterCommentAnchorsByRootIdsUseCase resolveChapterCommentAnchorsByRootIdsUseCase
    ) {
        this.discussionCoreReadService = Objects.requireNonNull(
                discussionCoreReadService, "DiscussionCoreReadService cannot be null"
        );
        this.commentReactionEnrichmentService = Objects.requireNonNull(
                commentReactionEnrichmentService, "CommentReactionEnrichmentService cannot be null"
        );
        this.resolveChapterCommentAnchorsByRootIdsUseCase = Objects.requireNonNull(
                resolveChapterCommentAnchorsByRootIdsUseCase, "ResolveChapterCommentAnchorsByRootIdsUseCase cannot be null"
        );
    }

    /**
     * Compatibility constructor for isolated unit tests instantiating the coordinator directly with mocks.
     */
    public NovelChapterDiscussionFeedQueryCoordinator(
            ListCommentRootsUseCase listCommentRootsUseCase,
            GetCommentThreadsByRootIdsUseCase getCommentThreadsByRootIdsUseCase,
            ResolveChapterCommentAnchorsByRootIdsUseCase resolveChapterCommentAnchorsByRootIdsUseCase,
            UserIdentityContract userIdentityContract,
            GetBatchReactionSummariesUseCase getBatchReactionSummariesUseCase
    ) {
        this(
                DiscussionCoreReadService.forSliceReadWithoutMetrics(
                        Objects.requireNonNull(listCommentRootsUseCase, "ListCommentRootsUseCase cannot be null"),
                        Objects.requireNonNull(getCommentThreadsByRootIdsUseCase, "GetCommentThreadsByRootIdsUseCase cannot be null"),
                        Objects.requireNonNull(userIdentityContract, "UserIdentityContract cannot be null")
                ),
                new CommentReactionEnrichmentService(
                        Objects.requireNonNull(getBatchReactionSummariesUseCase, "GetBatchReactionSummariesUseCase cannot be null")
                ),
                resolveChapterCommentAnchorsByRootIdsUseCase
        );
    }

    /**
     * Resolves a paginated slice of chapter discussion feed items with optional viewer capabilities and sort mode.
     *
     * @param chapterId scalar UUID of the chapter
     * @param page zero-based page index
     * @param size page size
     * @param viewerUserId optional scalar UUID of the authenticated viewer (null for guests)
     * @param sortMode comment sort mode (defaults to FEATURED if null)
     * @return immutable {@link ChapterDiscussionFeedResponseDTO}
     */
    public ChapterDiscussionFeedResponseDTO getDiscussionFeed(
            UUID chapterId,
            int page,
            int size,
            UUID viewerUserId,
            CommentSortMode sortMode
    ) {
        if (chapterId == null) {
            throw new IllegalArgumentException("chapterId cannot be null");
        }
        if (page < 0) {
            throw new IllegalArgumentException("page cannot be negative: " + page);
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be greater than zero: " + size);
        }

        // Phase A: Single read-only transaction covering root slice, batch threads, surviving-root anchors, and Identity profiles
        CommentTarget target = CommentTarget.novelChapter(chapterId);
        CoreThreadedFeedResult<Map<UUID, ResolvedChapterCommentAnchorView>> coreResult =
                discussionCoreReadService.readThreadedFeedForSurvivingRoots(
                        target,
                        page,
                        size,
                        sortMode,
                        false,
                        false,
                        () -> {},
                        survivingRootIds -> resolveAnchorsByRootId(chapterId, survivingRootIds)
                );

        if (coreResult.survivingRootIds().isEmpty()) {
            return new ChapterDiscussionFeedResponseDTO(
                    List.of(),
                    coreResult.page(),
                    coreResult.size(),
                    coreResult.hasNext()
            );
        }

        // Phase B: Sequential best-effort batch reaction lookup in an isolated transaction boundary
        ReactionEnrichmentResult reactionResult = commentReactionEnrichmentService.loadCommentReactionSummaries(
                "chapter discussion feed",
                coreResult.participants().activeCommentIds(),
                viewerUserId,
                false
        );
        Map<UUID, ReactionSummaryResponseDTO> reactionSummariesByCommentId = reactionResult.summariesByCommentId();

        Map<UUID, ResolvedChapterCommentAnchorView> resolvedAnchorsByRootId =
                coreResult.contextAttachment() != null ? coreResult.contextAttachment() : Map.of();

        // Assemble feed items preserving original slice ordering
        List<ChapterDiscussionFeedItemDTO> items = new ArrayList<>(coreResult.survivingRootIds().size());
        for (UUID rootId : coreResult.survivingRootIds()) {
            CommentThreadView threadView = coreResult.threadViewsByRootId().get(rootId);
            CommentReadItem authoritativeRoot = threadView.root();
            CommentThreadResponseDTO threadDto = CommentThreadDtoAssembler.toThreadDto(
                    threadView,
                    coreResult.authorsMap(),
                    viewerUserId,
                    reactionSummariesByCommentId,
                    null
            );

            CommentReadDTO rootReadDTO = threadDto.root();
            CommentAuthorDTO rootAuthorDTO = (rootReadDTO.author() != null)
                    ? rootReadDTO.author()
                    : CommentThreadDtoAssembler.resolveAuthorDto(authoritativeRoot.authorUserId(), coreResult.authorsMap());

            List<CommentReadDTO> replyDTOs = threadDto.replies();
            int activeReplyCount = 0;
            for (CommentReadDTO replyDTO : replyDTOs) {
                if (!replyDTO.tombstone()) {
                    activeReplyCount++;
                }
            }

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
                    rootReadDTO.reactionSummary()
            ));
        }

        return new ChapterDiscussionFeedResponseDTO(
                items,
                coreResult.page(),
                coreResult.size(),
                coreResult.hasNext()
        );
    }

    private Map<UUID, ResolvedChapterCommentAnchorView> resolveAnchorsByRootId(
            UUID chapterId,
            List<UUID> survivingRootIds
    ) {
        List<ResolvedChapterCommentAnchorView> resolvedAnchors =
                resolveChapterCommentAnchorsByRootIdsUseCase.execute(chapterId, survivingRootIds);
        if (resolvedAnchors == null || resolvedAnchors.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ResolvedChapterCommentAnchorView> resolvedByRootId = new LinkedHashMap<>();
        for (ResolvedChapterCommentAnchorView anchor : resolvedAnchors) {
            if (anchor != null && anchor.rootCommentId() != null) {
                resolvedByRootId.putIfAbsent(anchor.rootCommentId(), anchor);
            }
        }
        return Collections.unmodifiableMap(resolvedByRootId);
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
