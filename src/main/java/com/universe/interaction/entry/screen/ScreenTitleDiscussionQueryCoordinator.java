package com.universe.interaction.entry.screen;

import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.interaction.application.exceptions.CommentTargetNotEligibleException;
import com.universe.interaction.application.query.CommentReadItem;
import com.universe.interaction.application.query.CommentThreadView;
import com.universe.interaction.application.query.GetBatchReactionSummariesUseCase;
import com.universe.interaction.application.query.GetCommentTargetMetricsUseCase;
import com.universe.interaction.application.query.GetCommentThreadUseCase;
import com.universe.interaction.application.query.GetCommentThreadsByRootIdsUseCase;
import com.universe.interaction.application.query.ListCommentRootsUseCase;
import com.universe.interaction.application.query.ReactionSummary;
import com.universe.interaction.application.query.ValidateCommentTargetScopeUseCase;
import com.universe.interaction.entry.screen.ScreenTitleDiscussionCoreReadService.CoreFeedReadResult;
import com.universe.interaction.entry.screen.ScreenTitleDiscussionCoreReadService.CoreStageTimings;
import com.universe.interaction.domain.CommentSortMode;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.domain.reaction.ReactionTargetType;
import com.universe.interaction.entry.dto.CommentAuthorDTO;
import com.universe.interaction.entry.dto.CommentOriginDTO;
import com.universe.interaction.entry.dto.CommentReadDTO;
import com.universe.interaction.entry.dto.CommentThreadResponseDTO;
import com.universe.interaction.entry.dto.ReactionSummaryResponseDTO;
import com.universe.interaction.entry.screen.dto.ScreenDiscussionFeedResponseDTO;
import com.universe.screen.contracts.dto.ScreenEntryOriginDTO;
import com.universe.screen.contracts.interfaces.ScreenDiscussionContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Cross-context query composition coordinator for reading Screen title discussion feeds.
 *
 * <p>Preserves bounded-context boundaries and performance contracts:
 * <ul>
 *   <li>Interaction application has ZERO Screen domain imports;</li>
 *   <li>Screen module has ZERO Interaction domain imports;</li>
 *   <li>Entry-layer composition uses only scalar cross-context references (UUIDs);</li>
 *   <li>One publication check + one target metrics aggregate query + one root Slice query + one batch visible thread query for current page roots + one Identity profile query + one batch reaction summary query;</li>
 *   <li>Zero per-root N+1 access loops;</li>
 *   <li>Read-only operation without write transactional overhead.</li>
 * </ul>
 */
@Service
public class ScreenTitleDiscussionQueryCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ScreenTitleDiscussionQueryCoordinator.class);

    private final ScreenDiscussionContract screenDiscussionContract;
    private final ScreenTitleDiscussionCoreReadService coreReadService;
    private final GetCommentThreadUseCase getCommentThreadUseCase;
    private final ValidateCommentTargetScopeUseCase validateCommentTargetScopeUseCase;
    private final UserIdentityContract userIdentityContract;
    private final GetBatchReactionSummariesUseCase getBatchReactionSummariesUseCase;
    private final boolean diagnosticEnabled;

    @Autowired
    public ScreenTitleDiscussionQueryCoordinator(
            ScreenDiscussionContract screenDiscussionContract,
            ScreenTitleDiscussionCoreReadService coreReadService,
            GetCommentThreadUseCase getCommentThreadUseCase,
            ValidateCommentTargetScopeUseCase validateCommentTargetScopeUseCase,
            UserIdentityContract userIdentityContract,
            GetBatchReactionSummariesUseCase getBatchReactionSummariesUseCase,
            @Value("${screen.performance.diagnostic-enabled:false}") boolean diagnosticEnabled
    ) {
        this.screenDiscussionContract = Objects.requireNonNull(
                screenDiscussionContract, "ScreenDiscussionContract cannot be null"
        );
        this.coreReadService = Objects.requireNonNull(
                coreReadService, "ScreenTitleDiscussionCoreReadService cannot be null"
        );
        this.getCommentThreadUseCase = Objects.requireNonNull(
                getCommentThreadUseCase, "GetCommentThreadUseCase cannot be null"
        );
        this.validateCommentTargetScopeUseCase = Objects.requireNonNull(
                validateCommentTargetScopeUseCase, "ValidateCommentTargetScopeUseCase cannot be null"
        );
        this.userIdentityContract = Objects.requireNonNull(
                userIdentityContract, "UserIdentityContract cannot be null"
        );
        this.getBatchReactionSummariesUseCase = Objects.requireNonNull(
                getBatchReactionSummariesUseCase, "GetBatchReactionSummariesUseCase cannot be null"
        );
        this.diagnosticEnabled = diagnosticEnabled;
    }

    public ScreenTitleDiscussionQueryCoordinator(
            ScreenDiscussionContract screenDiscussionContract,
            GetCommentTargetMetricsUseCase getCommentTargetMetricsUseCase,
            ListCommentRootsUseCase listCommentRootsUseCase,
            GetCommentThreadsByRootIdsUseCase getCommentThreadsByRootIdsUseCase,
            GetCommentThreadUseCase getCommentThreadUseCase,
            ValidateCommentTargetScopeUseCase validateCommentTargetScopeUseCase,
            UserIdentityContract userIdentityContract,
            GetBatchReactionSummariesUseCase getBatchReactionSummariesUseCase,
            boolean diagnosticEnabled
    ) {
        this(
                screenDiscussionContract,
                new ScreenTitleDiscussionCoreReadService(
                        screenDiscussionContract,
                        getCommentTargetMetricsUseCase,
                        listCommentRootsUseCase,
                        getCommentThreadsByRootIdsUseCase,
                        userIdentityContract
                ),
                getCommentThreadUseCase,
                validateCommentTargetScopeUseCase,
                userIdentityContract,
                getBatchReactionSummariesUseCase,
                diagnosticEnabled
        );
    }

    public ScreenTitleDiscussionQueryCoordinator(
            ScreenDiscussionContract screenDiscussionContract,
            GetCommentTargetMetricsUseCase getCommentTargetMetricsUseCase,
            ListCommentRootsUseCase listCommentRootsUseCase,
            GetCommentThreadsByRootIdsUseCase getCommentThreadsByRootIdsUseCase,
            GetCommentThreadUseCase getCommentThreadUseCase,
            ValidateCommentTargetScopeUseCase validateCommentTargetScopeUseCase,
            UserIdentityContract userIdentityContract,
            GetBatchReactionSummariesUseCase getBatchReactionSummariesUseCase
    ) {
        this(
                screenDiscussionContract,
                getCommentTargetMetricsUseCase,
                listCommentRootsUseCase,
                getCommentThreadsByRootIdsUseCase,
                getCommentThreadUseCase,
                validateCommentTargetScopeUseCase,
                userIdentityContract,
                getBatchReactionSummariesUseCase,
                false
        );
    }

    /**
     * Resolves a paginated slice of Screen title discussion feed items with optional viewer capabilities and sort mode.
     */
    public ScreenDiscussionFeedResponseDTO getDiscussionFeed(
            UUID titleId,
            int page,
            int size,
            UUID viewerUserId,
            CommentSortMode sortMode
    ) {
        if (titleId == null) {
            throw new IllegalArgumentException("titleId cannot be null");
        }
        if (page < 0) {
            throw new IllegalArgumentException("page cannot be negative: " + page);
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be greater than zero: " + size);
        }

        long t0 = diagnosticEnabled ? System.nanoTime() : 0L;
        CommentSortMode effectiveSortMode = (sortMode != null) ? sortMode : CommentSortMode.FEATURED;
        boolean authenticatedViewer = viewerUserId != null;

        // Phase A: Execute mandatory core reads inside a single Spring-managed read-only transaction
        CoreFeedReadResult coreResult;
        try {
            coreResult = coreReadService.readFeedCore(titleId, page, size, effectiveSortMode, diagnosticEnabled);
        } catch (CommentTargetNotEligibleException ex) {
            if (diagnosticEnabled) {
                logIneligibleDiagnostic(t0, System.nanoTime(), effectiveSortMode, page, size, authenticatedViewer);
            }
            throw ex;
        }

        CoreStageTimings coreTimings = coreResult.timings();
        List<UUID> survivingRootIds = coreResult.survivingRootIds();
        if (survivingRootIds.isEmpty()) {
            ScreenDiscussionFeedResponseDTO emptyResponse = new ScreenDiscussionFeedResponseDTO(
                    List.of(),
                    coreResult.threadCount(),
                    coreResult.commentCount(),
                    coreResult.page(),
                    coreResult.size(),
                    coreResult.hasNext()
            );
            if (diagnosticEnabled) {
                long tEnd = System.nanoTime();
                logFeedDiagnostic(
                        tEnd - t0,
                        coreTimings.publishCheckNanos(),
                        coreTimings.targetMetricsNanos(),
                        coreTimings.rootSliceNanos(),
                        coreTimings.entryOriginsNanos(),
                        coreTimings.threadsBatchNanos(),
                        coreTimings.identityBatchNanos(),
                        0L,
                        coreTimings.collectNanos(),
                        coreResult.rawRootCount(),
                        coreResult.publishedOriginsById().size(),
                        0,
                        0,
                        0,
                        0,
                        false,
                        coreResult.hasNext(),
                        effectiveSortMode,
                        page,
                        size,
                        authenticatedViewer,
                        true
                );
            }
            return emptyResponse;
        }

        // Phase B: Execute supplemental Reaction batch query in an independent sequential transaction
        List<UUID> activeCommentIds = coreResult.activeCommentIds();
        Map<UUID, ReactionSummaryResponseDTO> reactionSummariesByCommentId = Map.of();
        long reactionsBatchNanos = 0L;
        boolean reactionsFailed = false;
        if (!activeCommentIds.isEmpty()) {
            long tReactionsStart = diagnosticEnabled ? System.nanoTime() : 0L;
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
                reactionsFailed = true;
                log.warn(
                        "Failed to load reaction summaries for screen title discussion feed; using empty fallback: exceptionType={}, activeCommentCount={}, authenticatedViewer={}",
                        ex.getClass().getSimpleName(),
                        activeCommentIds.size(),
                        authenticatedViewer
                );
                reactionSummariesByCommentId = Map.of();
            } finally {
                if (diagnosticEnabled) {
                    reactionsBatchNanos = System.nanoTime() - tReactionsStart;
                }
            }
        }

        // Assemble threads preserving original slice ordering
        long tComposeStart = diagnosticEnabled ? System.nanoTime() : 0L;
        Map<UUID, CommentThreadView> threadViewsByRootId = coreResult.threadViewsByRootId();
        Map<UUID, UserPublicProfileDTO> authorsMap = coreResult.authorsMap();
        Map<UUID, ScreenEntryOriginDTO> publishedOriginsById = coreResult.publishedOriginsById();
        int totalReplyCount = 0;
        List<CommentThreadResponseDTO> threads = new ArrayList<>(survivingRootIds.size());
        for (UUID rootId : survivingRootIds) {
            CommentThreadView threadView = threadViewsByRootId.get(rootId);
            CommentReadItem authoritativeRoot = threadView.root();

            UserPublicProfileDTO rootProfile = (authorsMap != null && authoritativeRoot.authorUserId() != null)
                    ? authorsMap.get(authoritativeRoot.authorUserId())
                    : null;
            CommentAuthorDTO rootAuthorDTO = (rootProfile != null)
                    ? new CommentAuthorDTO(authoritativeRoot.authorUserId(), rootProfile.displayName(), rootProfile.avatarUrl(), rootProfile.publicHandle())
                    : CommentAuthorDTO.fallback(authoritativeRoot.authorUserId());

            ReactionSummaryResponseDTO rootReactionSummary = reactionSummariesByCommentId.get(rootId);
            CommentOriginDTO rootOrigin = resolveCommentOrigin(authoritativeRoot.originScreenEntryId(), publishedOriginsById);
            CommentReadDTO rootReadDTO = CommentReadDTO.from(authoritativeRoot, rootAuthorDTO, viewerUserId, rootReactionSummary, rootOrigin);

            List<CommentReadDTO> replyDTOs = new ArrayList<>();
            if (threadView.replies() != null) {
                totalReplyCount += threadView.replies().size();
                for (CommentReadItem replyItem : threadView.replies()) {
                    if (replyItem == null) {
                        continue;
                    }
                    if (replyItem.tombstone()) {
                        replyDTOs.add(CommentReadDTO.from(replyItem, viewerUserId));
                    } else {
                        UserPublicProfileDTO replyProfile = (authorsMap != null && replyItem.authorUserId() != null)
                                ? authorsMap.get(replyItem.authorUserId())
                                : null;
                        CommentAuthorDTO replyAuthorDTO = (replyProfile != null)
                                ? new CommentAuthorDTO(replyItem.authorUserId(), replyProfile.displayName(), replyProfile.avatarUrl(), replyProfile.publicHandle())
                                : CommentAuthorDTO.fallback(replyItem.authorUserId());
                        ReactionSummaryResponseDTO replyReactionSummary = reactionSummariesByCommentId.get(replyItem.id());
                        CommentOriginDTO replyOrigin = resolveCommentOrigin(replyItem.originScreenEntryId(), publishedOriginsById);
                        replyDTOs.add(CommentReadDTO.from(replyItem, replyAuthorDTO, viewerUserId, replyReactionSummary, replyOrigin));
                    }
                }
            }

            threads.add(new CommentThreadResponseDTO(rootReadDTO, replyDTOs));
        }

        ScreenDiscussionFeedResponseDTO response = new ScreenDiscussionFeedResponseDTO(
                threads,
                coreResult.threadCount(),
                coreResult.commentCount(),
                coreResult.page(),
                coreResult.size(),
                coreResult.hasNext()
        );
        if (diagnosticEnabled) {
            long tEnd = System.nanoTime();
            long composeNanos = coreTimings.collectNanos() + (tEnd - tComposeStart);
            logFeedDiagnostic(
                    tEnd - t0,
                    coreTimings.publishCheckNanos(),
                    coreTimings.targetMetricsNanos(),
                    coreTimings.rootSliceNanos(),
                    coreTimings.entryOriginsNanos(),
                    coreTimings.threadsBatchNanos(),
                    coreTimings.identityBatchNanos(),
                    reactionsBatchNanos,
                    composeNanos,
                    coreResult.rawRootCount(),
                    publishedOriginsById.size(),
                    threads.size(),
                    totalReplyCount,
                    coreResult.authorUserIds().size(),
                    activeCommentIds.size(),
                    reactionsFailed,
                    coreResult.hasNext(),
                    effectiveSortMode,
                    page,
                    size,
                    authenticatedViewer,
                    false
            );
        }
        return response;
    }

    /**
     * Resolves a single discussion thread by root comment ID under a published Screen title.
     */
    public CommentThreadResponseDTO getCommentThread(UUID titleId, UUID rootCommentId, UUID viewerUserId) {
        if (titleId == null) {
            throw new IllegalArgumentException("titleId cannot be null");
        }
        if (rootCommentId == null) {
            throw new IllegalArgumentException("rootCommentId cannot be null");
        }

        if (!screenDiscussionContract.isPublishedTitle(titleId)) {
            throw new CommentTargetNotEligibleException("ScreenTitle is not published: " + titleId);
        }

        CommentTarget expectedTarget = CommentTarget.screenTitle(titleId);
        validateCommentTargetScopeUseCase.executeRoot(rootCommentId, expectedTarget);

        CommentThreadView threadView = getCommentThreadUseCase.execute(rootCommentId);

        Set<UUID> authorUserIds = new HashSet<>();
        List<UUID> activeCommentIds = new ArrayList<>();

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

        Map<UUID, UserPublicProfileDTO> authorsMap = authorUserIds.isEmpty()
                ? Map.of()
                : userIdentityContract.findPublicProfilesByIds(authorUserIds);

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
                log.warn("Failed to load reaction summaries for screen title comment thread: titleId={}, rootCommentId={}", titleId, rootCommentId, ex);
                reactionSummariesByCommentId = Map.of();
            }
        }

        Map<UUID, ScreenEntryOriginDTO> publishedOriginsById = screenDiscussionContract.findPublishedEntryOriginsByTitleId(titleId);

        CommentReadItem authoritativeRoot = threadView.root();
        UserPublicProfileDTO rootProfile = (authorsMap != null && authoritativeRoot.authorUserId() != null)
                ? authorsMap.get(authoritativeRoot.authorUserId())
                : null;
        CommentAuthorDTO rootAuthorDTO = (rootProfile != null)
                ? new CommentAuthorDTO(authoritativeRoot.authorUserId(), rootProfile.displayName(), rootProfile.avatarUrl(), rootProfile.publicHandle())
                : CommentAuthorDTO.fallback(authoritativeRoot.authorUserId());

        ReactionSummaryResponseDTO rootReactionSummary = reactionSummariesByCommentId.get(authoritativeRoot.id());
        CommentOriginDTO rootOrigin = resolveCommentOrigin(authoritativeRoot.originScreenEntryId(), publishedOriginsById);
        CommentReadDTO rootReadDTO = CommentReadDTO.from(authoritativeRoot, rootAuthorDTO, viewerUserId, rootReactionSummary, rootOrigin);

        List<CommentReadDTO> replyDTOs = new ArrayList<>();
        if (threadView.replies() != null) {
            for (CommentReadItem replyItem : threadView.replies()) {
                if (replyItem == null) {
                    continue;
                }
                if (replyItem.tombstone()) {
                    replyDTOs.add(CommentReadDTO.from(replyItem, viewerUserId));
                } else {
                    UserPublicProfileDTO replyProfile = (authorsMap != null && replyItem.authorUserId() != null)
                            ? authorsMap.get(replyItem.authorUserId())
                            : null;
                    CommentAuthorDTO replyAuthorDTO = (replyProfile != null)
                            ? new CommentAuthorDTO(replyItem.authorUserId(), replyProfile.displayName(), replyProfile.avatarUrl(), replyProfile.publicHandle())
                            : CommentAuthorDTO.fallback(replyItem.authorUserId());
                    ReactionSummaryResponseDTO replyReactionSummary = reactionSummariesByCommentId.get(replyItem.id());
                    CommentOriginDTO replyOrigin = resolveCommentOrigin(replyItem.originScreenEntryId(), publishedOriginsById);
                    replyDTOs.add(CommentReadDTO.from(replyItem, replyAuthorDTO, viewerUserId, replyReactionSummary, replyOrigin));
                }
            }
        }

        return new CommentThreadResponseDTO(rootReadDTO, replyDTOs);
    }

    private CommentOriginDTO resolveCommentOrigin(UUID originScreenEntryId, Map<UUID, ScreenEntryOriginDTO> publishedOriginsById) {
        if (originScreenEntryId == null || publishedOriginsById == null) {
            return null;
        }
        ScreenEntryOriginDTO origin = publishedOriginsById.get(originScreenEntryId);
        if (origin == null) {
            return null;
        }
        return new CommentOriginDTO(origin.entryId(), origin.displayBadge(), origin.slug());
    }

    private void logIneligibleDiagnostic(
            long startNanos,
            long endNanos,
            CommentSortMode sortMode,
            int page,
            int size,
            boolean authenticatedViewer
    ) {
        if (!diagnosticEnabled || !log.isInfoEnabled()) {
            return;
        }
        long elapsedNanos = Math.max(0L, endNanos - startNanos);
        log.info(
                "[PERF-DIAGNOSTIC] /api/screen/titles/{titleId}/comments feed: total={}ms, publish_check={}ms, eligible=false, sort={}, page={}, size={}, auth={}",
                formatMs(elapsedNanos),
                formatMs(elapsedNanos),
                sortMode.name(),
                page,
                size,
                authenticatedViewer
        );
    }

    private void logFeedDiagnostic(
            long totalNanos,
            long publishCheckNanos,
            long targetMetricsNanos,
            long rootSliceNanos,
            long entryOriginsNanos,
            long threadsBatchNanos,
            long identityBatchNanos,
            long reactionsBatchNanos,
            long dtoComposeNanos,
            int rootCount,
            int originCount,
            int threadCount,
            int replyCount,
            int authorCount,
            int activeCommentCount,
            boolean reactionsFailed,
            boolean hasNext,
            CommentSortMode sortMode,
            int page,
            int size,
            boolean authenticatedViewer,
            boolean emptyFeed
    ) {
        if (!diagnosticEnabled || !log.isInfoEnabled()) {
            return;
        }
        log.info(
                "[PERF-DIAGNOSTIC] /api/screen/titles/{titleId}/comments feed: total={}ms, publish_check={}ms, target_metrics={}ms, root_slice={}ms (roots={}), entry_origins={}ms (origins={}), threads_batch={}ms (threads={}, replies={}), identity_batch={}ms (authors={}), reactions_batch={}ms (active_comments={}, failed={}), dto_compose={}ms, sort={}, page={}, size={}, has_next={}, auth={}, empty={}",
                formatMs(totalNanos),
                formatMs(publishCheckNanos),
                formatMs(targetMetricsNanos),
                formatMs(rootSliceNanos),
                rootCount,
                formatMs(entryOriginsNanos),
                originCount,
                formatMs(threadsBatchNanos),
                threadCount,
                replyCount,
                formatMs(identityBatchNanos),
                authorCount,
                formatMs(reactionsBatchNanos),
                activeCommentCount,
                reactionsFailed,
                formatMs(dtoComposeNanos),
                sortMode.name(),
                page,
                size,
                hasNext,
                authenticatedViewer,
                emptyFeed
        );
    }

    private static String formatMs(long nanos) {
        return String.format(Locale.ROOT, "%.2f", Math.max(0L, nanos) / 1_000_000.0);
    }
}
