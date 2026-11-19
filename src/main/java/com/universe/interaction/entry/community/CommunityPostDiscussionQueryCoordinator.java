package com.universe.interaction.entry.community;

import com.universe.community.contracts.port.CommunityPostQueryPort;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.interaction.application.exceptions.CommentTargetNotEligibleException;
import com.universe.interaction.application.query.CommentReadItem;
import com.universe.interaction.application.query.CountVisibleActiveRepliesByRootIdsUseCase;
import com.universe.interaction.application.query.GetBatchReactionSummariesUseCase;
import com.universe.interaction.application.query.GetCommentTargetMetricsUseCase;
import com.universe.interaction.application.query.GetCommentThreadUseCase;
import com.universe.interaction.application.query.ListCommentRootsUseCase;
import com.universe.interaction.application.query.ValidateCommentTargetScopeUseCase;
import com.universe.interaction.domain.CommentSortMode;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.entry.community.dto.CommunityDiscussionFeedResponseDTO;
import com.universe.interaction.entry.community.dto.CommunityRootCommentDTO;
import com.universe.interaction.entry.dto.CommentReadDTO;
import com.universe.interaction.entry.dto.CommentThreadResponseDTO;
import com.universe.interaction.entry.dto.ReactionSummaryResponseDTO;
import com.universe.interaction.entry.shared.CommentReactionEnrichmentService;
import com.universe.interaction.entry.shared.CommentReactionEnrichmentService.ReactionEnrichmentResult;
import com.universe.interaction.entry.shared.CommentThreadDtoAssembler;
import com.universe.interaction.entry.shared.DiscussionCoreReadService;
import com.universe.interaction.entry.shared.DiscussionCoreReadService.CoreRootsOnlyFeedResult;
import com.universe.interaction.entry.shared.DiscussionCoreReadService.CoreSingleThreadResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Cross-context query composition coordinator for reading Community post discussion feeds and threads.
 *
 * <p>Preserves bounded-context boundaries and lazy-reply performance contracts:
 * <ul>
 *   <li>Interaction application has ZERO Community entity/write imports;</li>
 *   <li>Community application has ZERO Interaction entity/write imports;</li>
 *   <li>Delegates Phase A core reads (one {@code @Transactional(readOnly = true)} transaction) to
 *       {@link DiscussionCoreReadService}, followed sequentially by Phase B best-effort Reaction
 *       enrichment via {@link CommentReactionEnrichmentService};</li>
 *   <li>Root discussion feed retrieves ONLY root comments and indicator reply counts without
 *       materializing reply entities or fetching reply-author profiles;</li>
 *   <li>Thread endpoint resolves root comment and flat visible replies on demand with deduplicated
 *       bulk author lookup.</li>
 * </ul>
 */
@Service
public class CommunityPostDiscussionQueryCoordinator {

    private final CommunityPostQueryPort communityPostQueryPort;
    private final DiscussionCoreReadService discussionCoreReadService;
    private final CommentReactionEnrichmentService commentReactionEnrichmentService;

    @Autowired
    public CommunityPostDiscussionQueryCoordinator(
            CommunityPostQueryPort communityPostQueryPort,
            DiscussionCoreReadService discussionCoreReadService,
            CommentReactionEnrichmentService commentReactionEnrichmentService
    ) {
        this.communityPostQueryPort = Objects.requireNonNull(
                communityPostQueryPort, "CommunityPostQueryPort cannot be null"
        );
        this.discussionCoreReadService = Objects.requireNonNull(
                discussionCoreReadService, "DiscussionCoreReadService cannot be null"
        );
        this.commentReactionEnrichmentService = Objects.requireNonNull(
                commentReactionEnrichmentService, "CommentReactionEnrichmentService cannot be null"
        );
    }

    /**
     * Compatibility constructor for isolated unit tests instantiating the coordinator directly with mocks.
     */
    public CommunityPostDiscussionQueryCoordinator(
            CommunityPostQueryPort communityPostQueryPort,
            GetCommentTargetMetricsUseCase getCommentTargetMetricsUseCase,
            ListCommentRootsUseCase listCommentRootsUseCase,
            CountVisibleActiveRepliesByRootIdsUseCase countVisibleActiveRepliesByRootIdsUseCase,
            GetCommentThreadUseCase getCommentThreadUseCase,
            ValidateCommentTargetScopeUseCase validateCommentTargetScopeUseCase,
            UserIdentityContract userIdentityContract,
            GetBatchReactionSummariesUseCase getBatchReactionSummariesUseCase
    ) {
        this(
                communityPostQueryPort,
                DiscussionCoreReadService.forRootsOnlyFeedAndSingleThread(
                        Objects.requireNonNull(getCommentTargetMetricsUseCase, "GetCommentTargetMetricsUseCase cannot be null"),
                        Objects.requireNonNull(listCommentRootsUseCase, "ListCommentRootsUseCase cannot be null"),
                        Objects.requireNonNull(countVisibleActiveRepliesByRootIdsUseCase, "CountVisibleActiveRepliesByRootIdsUseCase cannot be null"),
                        Objects.requireNonNull(validateCommentTargetScopeUseCase, "ValidateCommentTargetScopeUseCase cannot be null"),
                        Objects.requireNonNull(getCommentThreadUseCase, "GetCommentThreadUseCase cannot be null"),
                        Objects.requireNonNull(userIdentityContract, "UserIdentityContract cannot be null")
                ),
                new CommentReactionEnrichmentService(
                        Objects.requireNonNull(getBatchReactionSummariesUseCase, "GetBatchReactionSummariesUseCase cannot be null")
                )
        );
    }

    /**
     * Resolves a paginated slice of Community post discussion feed items with optional viewer capabilities and sort mode.
     *
     * <p>Loads ONLY root comments and indicator reply counts. Does NOT materialize reply bodies.
     * Resolves authors strictly for root comments present in the returned slice.
     *
     * @param postId scalar UUID of the Community post
     * @param page zero-based page index
     * @param size page size
     * @param viewerUserId optional scalar UUID of the authenticated viewer (null for guests)
     * @param sortMode comment sort mode (defaults to FEATURED if null)
     * @return immutable {@link CommunityDiscussionFeedResponseDTO}
     */
    public CommunityDiscussionFeedResponseDTO getDiscussionFeed(
            UUID postId,
            int page,
            int size,
            UUID viewerUserId,
            CommentSortMode sortMode
    ) {
        if (postId == null) {
            throw new IllegalArgumentException("postId cannot be null");
        }
        if (page < 0) {
            throw new IllegalArgumentException("page cannot be negative: " + page);
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be greater than zero: " + size);
        }

        // Phase A: Single read-only transaction covering public post gate, metrics, root slice, active reply counts, and root authors
        CommentTarget target = CommentTarget.communityPost(postId);
        CoreRootsOnlyFeedResult core = discussionCoreReadService.readRootsOnlyFeed(
                target,
                page,
                size,
                sortMode,
                () -> verifyPublicPost(postId, target)
        );

        if (core.roots().isEmpty()) {
            return new CommunityDiscussionFeedResponseDTO(
                    List.of(),
                    core.commentCount(),
                    core.page(),
                    core.size(),
                    core.hasNext()
            );
        }

        // Phase B: Sequential best-effort batch reaction lookup strictly for active root comments
        ReactionEnrichmentResult reactionResult = commentReactionEnrichmentService.loadCommentReactionSummaries(
                "community post feed",
                core.participants().activeCommentIds(),
                viewerUserId,
                false
        );
        Map<UUID, ReactionSummaryResponseDTO> reactionSummariesByCommentId = reactionResult.summariesByCommentId();

        // Assemble root comment DTOs preserving slice ordering
        List<CommunityRootCommentDTO> rootDTOs = new ArrayList<>(core.roots().size());
        for (CommentReadItem rootItem : core.roots()) {
            CommentReadDTO rootReadDTO = CommentThreadDtoAssembler.toCommentReadDto(
                    rootItem,
                    core.authorsMap(),
                    viewerUserId,
                    reactionSummariesByCommentId,
                    null
            );
            long replyCount = core.replyCountsByRootId().getOrDefault(rootItem.id(), 0L);
            rootDTOs.add(new CommunityRootCommentDTO(rootReadDTO, replyCount));
        }

        return new CommunityDiscussionFeedResponseDTO(
                rootDTOs,
                core.commentCount(),
                core.page(),
                core.size(),
                core.hasNext()
        );
    }

    /**
     * Resolves a single discussion thread by root comment ID under a public Community post.
     *
     * <p>Loads the root comment and its flat visible replies on demand. Performs deduplicated
     * single bulk Identity lookup across root author and returned reply authors.
     *
     * @param postId scalar UUID of the Community post
     * @param rootCommentId scalar UUID of the root comment
     * @param viewerUserId optional scalar UUID of the authenticated viewer (null for guests)
     * @return immutable {@link CommentThreadResponseDTO}
     */
    public CommentThreadResponseDTO getCommentThread(UUID postId, UUID rootCommentId, UUID viewerUserId) {
        if (postId == null) {
            throw new IllegalArgumentException("postId cannot be null");
        }
        if (rootCommentId == null) {
            throw new IllegalArgumentException("rootCommentId cannot be null");
        }

        // Phase A: Single read-only transaction covering public post gate, target-scope validation, thread hydration, and author batch
        CommentTarget expectedTarget = CommentTarget.communityPost(postId);
        CoreSingleThreadResult<Void> core = discussionCoreReadService.readSingleThreadCore(
                expectedTarget,
                rootCommentId,
                () -> verifyPublicPost(postId, expectedTarget)
        );

        // Phase B: Sequential best-effort batch reaction lookup for root and visible active replies
        ReactionEnrichmentResult reactionResult = commentReactionEnrichmentService.loadCommentReactionSummaries(
                "community post thread",
                core.participants().activeCommentIds(),
                viewerUserId,
                false
        );

        return CommentThreadDtoAssembler.toThreadDto(
                core.threadView(),
                core.authorsMap(),
                viewerUserId,
                reactionResult.summariesByCommentId(),
                null
        );
    }

    private void verifyPublicPost(UUID postId, CommentTarget target) {
        communityPostQueryPort.findPublicPostById(postId)
                .orElseThrow(() -> new CommentTargetNotEligibleException(target));
    }
}
