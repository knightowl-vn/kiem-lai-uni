package com.universe.interaction.entry.novel;

import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.interaction.application.query.CommentReadItem;
import com.universe.interaction.application.query.CommentThreadView;
import com.universe.interaction.application.query.GetBatchReactionSummariesUseCase;
import com.universe.interaction.application.query.GetCommentThreadsByRootIdsUseCase;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.entry.dto.ChapterBlockDiscussionResponseDTO;
import com.universe.interaction.entry.dto.CommentThreadResponseDTO;
import com.universe.interaction.entry.dto.ReactionSummaryResponseDTO;
import com.universe.interaction.entry.shared.CommentReactionEnrichmentService;
import com.universe.interaction.entry.shared.CommentReactionEnrichmentService.ReactionEnrichmentResult;
import com.universe.interaction.entry.shared.CommentThreadDtoAssembler;
import com.universe.interaction.entry.shared.DiscussionCoreReadService;
import com.universe.interaction.entry.shared.DiscussionCoreReadService.CoreKnownRootsResult;
import com.universe.interaction.entry.shared.DiscussionCoreReadService.KnownRootsSelection;
import com.universe.novel.application.anchor.ChapterBlockDiscussionAnchorView;
import com.universe.novel.application.anchor.GetChapterBlockDiscussionAnchorsUseCase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Cross-context composition coordinator for reading Novel block discussions.
 *
 * <p>Preserves bounded-context boundaries:
 * <ul>
 *   <li>Novel application has ZERO Interaction imports;</li>
 *   <li>Interaction application has ZERO Novel imports;</li>
 *   <li>Composition occurs cleanly at this entry layer;</li>
 *   <li>Delegates Phase A core reads (one {@code @Transactional(readOnly = true)} transaction covering
 *       bulk Novel block anchor resolution, bulk visible thread retrieval, participant collection, and
 *       batched Identity profiles) to {@link DiscussionCoreReadService}, followed sequentially by Phase B
 *       best-effort Reaction enrichment via {@link CommentReactionEnrichmentService}.</li>
 * </ul>
 */
@Service
public class NovelBlockDiscussionQueryCoordinator {

    private final DiscussionCoreReadService discussionCoreReadService;
    private final CommentReactionEnrichmentService commentReactionEnrichmentService;
    private final GetChapterBlockDiscussionAnchorsUseCase getChapterBlockDiscussionAnchorsUseCase;

    @Autowired
    public NovelBlockDiscussionQueryCoordinator(
            DiscussionCoreReadService discussionCoreReadService,
            CommentReactionEnrichmentService commentReactionEnrichmentService,
            GetChapterBlockDiscussionAnchorsUseCase getChapterBlockDiscussionAnchorsUseCase
    ) {
        this.discussionCoreReadService = Objects.requireNonNull(
                discussionCoreReadService, "DiscussionCoreReadService cannot be null"
        );
        this.commentReactionEnrichmentService = Objects.requireNonNull(
                commentReactionEnrichmentService, "CommentReactionEnrichmentService cannot be null"
        );
        this.getChapterBlockDiscussionAnchorsUseCase = Objects.requireNonNull(
                getChapterBlockDiscussionAnchorsUseCase, "GetChapterBlockDiscussionAnchorsUseCase cannot be null"
        );
    }

    /**
     * Compatibility constructor for isolated unit tests instantiating the coordinator directly with mocks.
     */
    public NovelBlockDiscussionQueryCoordinator(
            GetChapterBlockDiscussionAnchorsUseCase getChapterBlockDiscussionAnchorsUseCase,
            GetCommentThreadsByRootIdsUseCase getCommentThreadsByRootIdsUseCase,
            UserIdentityContract userIdentityContract,
            GetBatchReactionSummariesUseCase getBatchReactionSummariesUseCase
    ) {
        this(
                DiscussionCoreReadService.forKnownRootsRead(
                        Objects.requireNonNull(getCommentThreadsByRootIdsUseCase, "GetCommentThreadsByRootIdsUseCase cannot be null"),
                        Objects.requireNonNull(userIdentityContract, "UserIdentityContract cannot be null")
                ),
                new CommentReactionEnrichmentService(
                        Objects.requireNonNull(getBatchReactionSummariesUseCase, "GetBatchReactionSummariesUseCase cannot be null")
                ),
                Objects.requireNonNull(getChapterBlockDiscussionAnchorsUseCase, "GetChapterBlockDiscussionAnchorsUseCase cannot be null")
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

        // Phase A: Single read-only transaction covering Novel block anchors, bulk Interaction threads, and Identity profiles
        CommentTarget target = CommentTarget.novelChapter(chapterId);
        CoreKnownRootsResult<ChapterBlockDiscussionAnchorView> coreResult =
                discussionCoreReadService.readKnownRootsCore(
                        target,
                        () -> {},
                        () -> {
                            ChapterBlockDiscussionAnchorView anchorView =
                                    getChapterBlockDiscussionAnchorsUseCase.execute(chapterId, blockKey);
                            return new KnownRootsSelection<>(anchorView.rootCommentIds(), anchorView);
                        }
                );

        ChapterBlockDiscussionAnchorView anchorView = coreResult.contextAttachment();
        if (coreResult.threadViews().isEmpty()) {
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

        // Phase B: Sequential best-effort batch reaction lookup in an isolated transaction boundary
        ReactionEnrichmentResult reactionResult = commentReactionEnrichmentService.loadCommentReactionSummaries(
                "block discussion",
                coreResult.participants().activeCommentIds(),
                viewerUserId,
                false
        );
        Map<UUID, ReactionSummaryResponseDTO> reactionSummariesByCommentId = reactionResult.summariesByCommentId();

        List<CommentThreadResponseDTO> threads = coreResult.threadViews().stream()
                .map(threadView -> CommentThreadDtoAssembler.toThreadDto(
                        threadView,
                        coreResult.authorsMap(),
                        viewerUserId,
                        reactionSummariesByCommentId,
                        null
                ))
                .toList();

        int commentCount = 0;
        for (CommentThreadView threadView : coreResult.threadViews()) {
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
}
