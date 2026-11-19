package com.universe.interaction.entry.wiki;

import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.interaction.application.query.GetBatchReactionSummariesUseCase;
import com.universe.interaction.application.query.GetCommentTargetMetricsUseCase;
import com.universe.interaction.application.query.GetCommentThreadUseCase;
import com.universe.interaction.application.query.GetCommentThreadsByRootIdsUseCase;
import com.universe.interaction.application.query.ListCommentRootsUseCase;
import com.universe.interaction.application.query.ValidateCommentTargetScopeUseCase;
import com.universe.interaction.domain.CommentSortMode;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.entry.dto.CommentThreadResponseDTO;
import com.universe.interaction.entry.shared.CommentReactionEnrichmentService;
import com.universe.interaction.entry.shared.CommentReactionEnrichmentService.ReactionEnrichmentResult;
import com.universe.interaction.entry.shared.CommentThreadDtoAssembler;
import com.universe.interaction.entry.shared.DiscussionCoreReadService;
import com.universe.interaction.entry.shared.DiscussionCoreReadService.CoreSingleThreadResult;
import com.universe.interaction.entry.shared.DiscussionCoreReadService.CoreThreadedFeedResult;
import com.universe.interaction.entry.wiki.dto.WikiDiscussionFeedResponseDTO;
import com.universe.wiki.application.exceptions.PublishedWikiArticleNotFoundException;
import com.universe.wiki.application.ports.WikiArticleQueryPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Cross-context query composition coordinator for reading Wiki article discussion feeds and threads.
 *
 * <p>Preserves bounded-context boundaries and performance contracts:
 * <ul>
 *   <li>Interaction application has ZERO Wiki imports;</li>
 *   <li>Wiki application has ZERO Interaction imports;</li>
 *   <li>Delegates generic Phase A core reading (one {@code @Transactional(readOnly = true)} transaction)
 *       to {@link DiscussionCoreReadService}, followed sequentially by Phase B Reaction enrichment
 *       via {@link CommentReactionEnrichmentService};</li>
 *   <li>Retains Wiki-specific publication policy ({@link PublishedWikiArticleNotFoundException}) and DTOs.</li>
 * </ul>
 */
@Service
public class WikiArticleDiscussionQueryCoordinator {

    private final WikiArticleQueryPort wikiArticleQueryPort;
    private final DiscussionCoreReadService discussionCoreReadService;
    private final CommentReactionEnrichmentService reactionEnrichmentService;

    @Autowired
    public WikiArticleDiscussionQueryCoordinator(
            WikiArticleQueryPort wikiArticleQueryPort,
            DiscussionCoreReadService discussionCoreReadService,
            CommentReactionEnrichmentService reactionEnrichmentService
    ) {
        this.wikiArticleQueryPort = Objects.requireNonNull(
                wikiArticleQueryPort, "WikiArticleQueryPort cannot be null"
        );
        this.discussionCoreReadService = Objects.requireNonNull(
                discussionCoreReadService, "DiscussionCoreReadService cannot be null"
        );
        this.reactionEnrichmentService = Objects.requireNonNull(
                reactionEnrichmentService, "CommentReactionEnrichmentService cannot be null"
        );
    }

    public WikiArticleDiscussionQueryCoordinator(
            WikiArticleQueryPort wikiArticleQueryPort,
            GetCommentTargetMetricsUseCase getCommentTargetMetricsUseCase,
            ListCommentRootsUseCase listCommentRootsUseCase,
            GetCommentThreadsByRootIdsUseCase getCommentThreadsByRootIdsUseCase,
            GetCommentThreadUseCase getCommentThreadUseCase,
            ValidateCommentTargetScopeUseCase validateCommentTargetScopeUseCase,
            UserIdentityContract userIdentityContract,
            GetBatchReactionSummariesUseCase getBatchReactionSummariesUseCase
    ) {
        this(
                wikiArticleQueryPort,
                new DiscussionCoreReadService(
                        getCommentTargetMetricsUseCase,
                        listCommentRootsUseCase,
                        getCommentThreadsByRootIdsUseCase,
                        validateCommentTargetScopeUseCase,
                        getCommentThreadUseCase,
                        userIdentityContract
                ),
                new CommentReactionEnrichmentService(getBatchReactionSummariesUseCase)
        );
    }

    /**
     * Resolves a paginated slice of Wiki article discussion feed items with optional viewer capabilities and sort mode.
     *
     * @param articleId scalar UUID of the Wiki article
     * @param page zero-based page index
     * @param size page size
     * @param viewerUserId optional scalar UUID of the authenticated viewer (null for guests)
     * @param sortMode comment sort mode (defaults to FEATURED if null)
     * @return immutable {@link WikiDiscussionFeedResponseDTO}
     */
    public WikiDiscussionFeedResponseDTO getDiscussionFeed(
            UUID articleId,
            int page,
            int size,
            UUID viewerUserId,
            CommentSortMode sortMode
    ) {
        if (articleId == null) {
            throw new IllegalArgumentException("articleId cannot be null");
        }
        if (page < 0) {
            throw new IllegalArgumentException("page cannot be negative: " + page);
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be greater than zero: " + size);
        }

        // Phase A: Mandatory core reads inside shared Spring-managed read-only transaction
        CoreThreadedFeedResult<Void> core = discussionCoreReadService.readThreadedFeed(
                CommentTarget.wikiArticle(articleId),
                page,
                size,
                sortMode,
                true,
                false,
                () -> verifyPublishedArticle(articleId)
        );

        if (core.survivingRootIds().isEmpty()) {
            return new WikiDiscussionFeedResponseDTO(
                    List.of(),
                    core.threadCount(),
                    core.commentCount(),
                    core.page(),
                    core.size(),
                    core.hasNext()
            );
        }

        // Phase B: Sequential best-effort Reaction enrichment after Phase A commits/closes
        ReactionEnrichmentResult reactionResult = reactionEnrichmentService.loadCommentReactionSummaries(
                "wiki article feed",
                core.participants().activeCommentIds(),
                viewerUserId,
                false
        );

        List<CommentThreadResponseDTO> threads = CommentThreadDtoAssembler.toThreadDtos(
                core.survivingRootIds(),
                core.threadViewsByRootId(),
                core.authorsMap(),
                viewerUserId,
                reactionResult.summariesByCommentId(),
                null
        );

        return new WikiDiscussionFeedResponseDTO(
                threads,
                core.threadCount(),
                core.commentCount(),
                core.page(),
                core.size(),
                core.hasNext()
        );
    }

    /**
     * Resolves a single discussion thread by root comment ID under a published Wiki article.
     *
     * @param articleId scalar UUID of the Wiki article
     * @param rootCommentId scalar UUID of the root comment
     * @param viewerUserId optional scalar UUID of the authenticated viewer (null for guests)
     * @return immutable {@link CommentThreadResponseDTO}
     */
    public CommentThreadResponseDTO getCommentThread(UUID articleId, UUID rootCommentId, UUID viewerUserId) {
        if (articleId == null) {
            throw new IllegalArgumentException("articleId cannot be null");
        }
        if (rootCommentId == null) {
            throw new IllegalArgumentException("rootCommentId cannot be null");
        }

        // Phase A: Eligibility, target-scope check, single thread read, and author batch inside one read-only transaction
        CoreSingleThreadResult<Void> core = discussionCoreReadService.readSingleThreadCore(
                CommentTarget.wikiArticle(articleId),
                rootCommentId,
                () -> verifyPublishedArticle(articleId)
        );

        // Phase B: Sequential best-effort Reaction enrichment after Phase A commits/closes
        ReactionEnrichmentResult reactionResult = reactionEnrichmentService.loadCommentReactionSummaries(
                "wiki article thread",
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

    private void verifyPublishedArticle(UUID articleId) {
        if (!wikiArticleQueryPort.isPublished(articleId)) {
            throw new PublishedWikiArticleNotFoundException(articleId);
        }
    }
}
