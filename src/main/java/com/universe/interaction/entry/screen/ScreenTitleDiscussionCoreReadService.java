package com.universe.interaction.entry.screen;

import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.interaction.application.exceptions.CommentTargetNotEligibleException;
import com.universe.interaction.application.query.CommentReadItem;
import com.universe.interaction.application.query.CommentReadSlice;
import com.universe.interaction.application.query.CommentTargetMetrics;
import com.universe.interaction.application.query.CommentThreadView;
import com.universe.interaction.application.query.GetCommentTargetMetricsUseCase;
import com.universe.interaction.application.query.GetCommentThreadsByRootIdsUseCase;
import com.universe.interaction.application.query.ListCommentRootsUseCase;
import com.universe.interaction.domain.CommentSortMode;
import com.universe.interaction.domain.CommentTarget;
import com.universe.screen.contracts.dto.ScreenEntryOriginDTO;
import com.universe.screen.contracts.interfaces.ScreenDiscussionContract;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Spring-managed Phase A transactional core reader for Screen title discussion feeds.
 *
 * <p>Executes all mandatory core read stages (publication eligibility, target metrics,
 * root slice, published episode origins, batch thread retrieval, and batch author profiles)
 * inside a single {@code @Transactional(readOnly = true)} transaction and returns an
 * immutable DTO/value snapshot with zero escaping lazy JPA state.
 */
@Service
@Transactional(readOnly = true)
public class ScreenTitleDiscussionCoreReadService {

    private final ScreenDiscussionContract screenDiscussionContract;
    private final GetCommentTargetMetricsUseCase getCommentTargetMetricsUseCase;
    private final ListCommentRootsUseCase listCommentRootsUseCase;
    private final GetCommentThreadsByRootIdsUseCase getCommentThreadsByRootIdsUseCase;
    private final UserIdentityContract userIdentityContract;

    public ScreenTitleDiscussionCoreReadService(
            ScreenDiscussionContract screenDiscussionContract,
            GetCommentTargetMetricsUseCase getCommentTargetMetricsUseCase,
            ListCommentRootsUseCase listCommentRootsUseCase,
            GetCommentThreadsByRootIdsUseCase getCommentThreadsByRootIdsUseCase,
            UserIdentityContract userIdentityContract
    ) {
        this.screenDiscussionContract = Objects.requireNonNull(
                screenDiscussionContract, "ScreenDiscussionContract cannot be null"
        );
        this.getCommentTargetMetricsUseCase = Objects.requireNonNull(
                getCommentTargetMetricsUseCase, "GetCommentTargetMetricsUseCase cannot be null"
        );
        this.listCommentRootsUseCase = Objects.requireNonNull(
                listCommentRootsUseCase, "ListCommentRootsUseCase cannot be null"
        );
        this.getCommentThreadsByRootIdsUseCase = Objects.requireNonNull(
                getCommentThreadsByRootIdsUseCase, "GetCommentThreadsByRootIdsUseCase cannot be null"
        );
        this.userIdentityContract = Objects.requireNonNull(
                userIdentityContract, "UserIdentityContract cannot be null"
        );
    }

    /**
     * Reads the core Screen discussion feed snapshot inside a single read-only transaction.
     */
    public CoreFeedReadResult readFeedCore(
            UUID titleId,
            int page,
            int size,
            CommentSortMode sortMode,
            boolean diagnosticEnabled
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

        CommentSortMode effectiveSortMode = (sortMode != null) ? sortMode : CommentSortMode.FEATURED;
        long t0 = diagnosticEnabled ? System.nanoTime() : 0L;

        // 1. Publication gate: fail closed if title is missing or not published
        boolean published = screenDiscussionContract.isPublishedTitle(titleId);
        long tPublishEnd = diagnosticEnabled ? System.nanoTime() : 0L;
        if (!published) {
            throw new CommentTargetNotEligibleException("ScreenTitle is not published: " + titleId);
        }

        CommentTarget target = CommentTarget.screenTitle(titleId);

        // 2. Direct persistence aggregate: compute title metrics
        CommentTargetMetrics metrics = getCommentTargetMetricsUseCase.execute(target);
        long tMetricsEnd = diagnosticEnabled ? System.nanoTime() : 0L;
        int threadCount = metrics.threadCount();
        int commentCount = metrics.commentCount();

        // 3. Interaction query: load active root comments slice for this screen title
        CommentReadSlice rootSlice = listCommentRootsUseCase.execute(target, effectiveSortMode, page, size);
        long tSliceEnd = diagnosticEnabled ? System.nanoTime() : 0L;
        if (rootSlice.items().isEmpty()) {
            long tEnd = diagnosticEnabled ? System.nanoTime() : 0L;
            CoreStageTimings timings = diagnosticEnabled
                    ? new CoreStageTimings(
                    tPublishEnd - t0,
                    tMetricsEnd - tPublishEnd,
                    tSliceEnd - tMetricsEnd,
                    0L,
                    0L,
                    tEnd - tSliceEnd,
                    0L
            )
                    : CoreStageTimings.ZERO;
            return new CoreFeedReadResult(
                    threadCount,
                    commentCount,
                    rootSlice.page(),
                    rootSlice.size(),
                    rootSlice.hasNext(),
                    0,
                    Map.of(),
                    List.of(),
                    Map.of(),
                    Set.of(),
                    List.of(),
                    Map.of(),
                    timings
            );
        }

        List<CommentReadItem> roots = rootSlice.items();
        List<UUID> rootIds = roots.stream().map(CommentReadItem::id).toList();

        // 3b. Screen query: lookup published entry origins for this title to format episode origin badges
        Map<UUID, ScreenEntryOriginDTO> publishedOriginsById = screenDiscussionContract.findPublishedEntryOriginsByTitleId(titleId);
        long tOriginsEnd = diagnosticEnabled ? System.nanoTime() : 0L;

        // 4. Interaction query: batch retrieve visible threads for all roots in this slice
        List<CommentThreadView> threadViews = getCommentThreadsByRootIdsUseCase.execute(target, rootIds);
        long tThreadsEnd = diagnosticEnabled ? System.nanoTime() : 0L;
        Map<UUID, CommentThreadView> threadViewsByRootId = threadViews.stream()
                .collect(Collectors.toMap(
                        tv -> tv.root().id(),
                        tv -> tv,
                        (existing, replacement) -> existing
                ));

        // 5. Concurrent delete safety: preserve slice ordering among surviving roots only
        List<UUID> survivingRootIds = rootIds.stream()
                .filter(threadViewsByRootId::containsKey)
                .toList();

        if (survivingRootIds.isEmpty()) {
            long tEnd = diagnosticEnabled ? System.nanoTime() : 0L;
            CoreStageTimings timings = diagnosticEnabled
                    ? new CoreStageTimings(
                    tPublishEnd - t0,
                    tMetricsEnd - tPublishEnd,
                    tSliceEnd - tMetricsEnd,
                    tOriginsEnd - tSliceEnd,
                    tThreadsEnd - tOriginsEnd,
                    tEnd - tThreadsEnd,
                    0L
            )
                    : CoreStageTimings.ZERO;
            return new CoreFeedReadResult(
                    threadCount,
                    commentCount,
                    rootSlice.page(),
                    rootSlice.size(),
                    rootSlice.hasNext(),
                    roots.size(),
                    publishedOriginsById != null ? publishedOriginsById : Map.of(),
                    List.of(),
                    Map.of(),
                    Set.of(),
                    List.of(),
                    Map.of(),
                    timings
            );
        }

        // 6. Identity & Reaction targets: collect active authors and active comment IDs
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
        long tCollectEnd = diagnosticEnabled ? System.nanoTime() : 0L;

        Map<UUID, UserPublicProfileDTO> authorsMap = authorUserIds.isEmpty()
                ? Map.of()
                : userIdentityContract.findPublicProfilesByIds(authorUserIds);
        long tIdentityEnd = diagnosticEnabled ? System.nanoTime() : 0L;

        CoreStageTimings timings = diagnosticEnabled
                ? new CoreStageTimings(
                tPublishEnd - t0,
                tMetricsEnd - tPublishEnd,
                tSliceEnd - tMetricsEnd,
                tOriginsEnd - tSliceEnd,
                tThreadsEnd - tOriginsEnd,
                tCollectEnd - tThreadsEnd,
                tIdentityEnd - tCollectEnd
        )
                : CoreStageTimings.ZERO;

        return new CoreFeedReadResult(
                threadCount,
                commentCount,
                rootSlice.page(),
                rootSlice.size(),
                rootSlice.hasNext(),
                roots.size(),
                publishedOriginsById != null ? publishedOriginsById : Map.of(),
                survivingRootIds,
                threadViewsByRootId,
                authorUserIds,
                activeCommentIds,
                authorsMap != null ? authorsMap : Map.of(),
                timings
        );
    }

    public record CoreStageTimings(
            long publishCheckNanos,
            long targetMetricsNanos,
            long rootSliceNanos,
            long entryOriginsNanos,
            long threadsBatchNanos,
            long collectNanos,
            long identityBatchNanos
    ) {
        public static final CoreStageTimings ZERO = new CoreStageTimings(0L, 0L, 0L, 0L, 0L, 0L, 0L);
    }

    public record CoreFeedReadResult(
            int threadCount,
            int commentCount,
            int page,
            int size,
            boolean hasNext,
            int rawRootCount,
            Map<UUID, ScreenEntryOriginDTO> publishedOriginsById,
            List<UUID> survivingRootIds,
            Map<UUID, CommentThreadView> threadViewsByRootId,
            Set<UUID> authorUserIds,
            List<UUID> activeCommentIds,
            Map<UUID, UserPublicProfileDTO> authorsMap,
            CoreStageTimings timings
    ) {
        public CoreFeedReadResult {
            publishedOriginsById = publishedOriginsById == null ? Map.of() : Collections.unmodifiableMap(publishedOriginsById);
            survivingRootIds = survivingRootIds == null ? List.of() : List.copyOf(survivingRootIds);
            threadViewsByRootId = threadViewsByRootId == null ? Map.of() : Collections.unmodifiableMap(threadViewsByRootId);
            authorUserIds = authorUserIds == null ? Set.of() : Collections.unmodifiableSet(authorUserIds);
            activeCommentIds = activeCommentIds == null ? List.of() : List.copyOf(activeCommentIds);
            authorsMap = authorsMap == null ? Map.of() : Collections.unmodifiableMap(authorsMap);
            timings = timings == null ? CoreStageTimings.ZERO : timings;
        }
    }
}
