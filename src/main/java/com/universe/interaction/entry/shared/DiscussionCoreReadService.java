package com.universe.interaction.entry.shared;

import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.interaction.application.query.CommentReadItem;
import com.universe.interaction.application.query.CommentReadSlice;
import com.universe.interaction.application.query.CommentTargetMetrics;
import com.universe.interaction.application.query.CommentThreadView;
import com.universe.interaction.application.query.GetCommentTargetMetricsUseCase;
import com.universe.interaction.application.query.GetCommentThreadUseCase;
import com.universe.interaction.application.query.GetCommentThreadsByRootIdsUseCase;
import com.universe.interaction.application.query.ListCommentRootsUseCase;
import com.universe.interaction.application.query.ValidateCommentTargetScopeUseCase;
import com.universe.interaction.domain.CommentSortMode;
import com.universe.interaction.domain.CommentTarget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Shared Spring-managed Phase A transactional core reader for threaded discussion feeds.
 *
 * <p>Owns the single {@code @Transactional(readOnly = true)} boundary covering mandatory core reads:
 * target eligibility gate, optional target metrics, root comment slice pagination, optional
 * module-local DB attachment lookup, batched visible thread retrieval, active participant
 * collection, and batched author profile lookup.
 *
 * <p>Returns detached, immutable value snapshots so the Phase A transaction commits and returns
 * its JDBC connection strictly before any supplemental Phase B Reaction enrichment begins.
 * Unchecked exceptions raised inside Phase A propagate to roll back the core transaction cleanly.
 */
@Service
@Transactional(readOnly = true)
public class DiscussionCoreReadService {

    private final GetCommentTargetMetricsUseCase getCommentTargetMetricsUseCase;
    private final ListCommentRootsUseCase listCommentRootsUseCase;
    private final GetCommentThreadsByRootIdsUseCase getCommentThreadsByRootIdsUseCase;
    private final ValidateCommentTargetScopeUseCase validateCommentTargetScopeUseCase;
    private final GetCommentThreadUseCase getCommentThreadUseCase;
    private final UserIdentityContract userIdentityContract;

    @Autowired
    public DiscussionCoreReadService(
            GetCommentTargetMetricsUseCase getCommentTargetMetricsUseCase,
            ListCommentRootsUseCase listCommentRootsUseCase,
            GetCommentThreadsByRootIdsUseCase getCommentThreadsByRootIdsUseCase,
            ValidateCommentTargetScopeUseCase validateCommentTargetScopeUseCase,
            GetCommentThreadUseCase getCommentThreadUseCase,
            UserIdentityContract userIdentityContract
    ) {
        this.getCommentTargetMetricsUseCase = Objects.requireNonNull(
                getCommentTargetMetricsUseCase,
                "GetCommentTargetMetricsUseCase cannot be null."
        );
        this.listCommentRootsUseCase = Objects.requireNonNull(
                listCommentRootsUseCase,
                "ListCommentRootsUseCase cannot be null."
        );
        this.getCommentThreadsByRootIdsUseCase = Objects.requireNonNull(
                getCommentThreadsByRootIdsUseCase,
                "GetCommentThreadsByRootIdsUseCase cannot be null."
        );
        this.validateCommentTargetScopeUseCase = Objects.requireNonNull(
                validateCommentTargetScopeUseCase,
                "ValidateCommentTargetScopeUseCase cannot be null."
        );
        this.getCommentThreadUseCase = Objects.requireNonNull(
                getCommentThreadUseCase,
                "GetCommentThreadUseCase cannot be null."
        );
        this.userIdentityContract = Objects.requireNonNull(
                userIdentityContract,
                "UserIdentityContract cannot be null."
        );
    }

    /**
     * Compatibility constructor for feed-only unit tests that do not exercise single-thread reads.
     */
    public DiscussionCoreReadService(
            GetCommentTargetMetricsUseCase getCommentTargetMetricsUseCase,
            ListCommentRootsUseCase listCommentRootsUseCase,
            GetCommentThreadsByRootIdsUseCase getCommentThreadsByRootIdsUseCase,
            UserIdentityContract userIdentityContract
    ) {
        this.getCommentTargetMetricsUseCase = Objects.requireNonNull(
                getCommentTargetMetricsUseCase,
                "GetCommentTargetMetricsUseCase cannot be null."
        );
        this.listCommentRootsUseCase = Objects.requireNonNull(
                listCommentRootsUseCase,
                "ListCommentRootsUseCase cannot be null."
        );
        this.getCommentThreadsByRootIdsUseCase = Objects.requireNonNull(
                getCommentThreadsByRootIdsUseCase,
                "GetCommentThreadsByRootIdsUseCase cannot be null."
        );
        this.validateCommentTargetScopeUseCase = null;
        this.getCommentThreadUseCase = null;
        this.userIdentityContract = Objects.requireNonNull(
                userIdentityContract,
                "UserIdentityContract cannot be null."
        );
    }

    /**
     * Executes mandatory Phase A core discussion feed reads inside one read-only transaction.
     *
     * @param target interaction comment target
     * @param page zero-based page index
     * @param size positive page size
     * @param sortMode comment sort mode (defaults to {@link CommentSortMode#FEATURED} when null)
     * @param includeMetrics whether to query {@link GetCommentTargetMetricsUseCase}
     * @param diagnosticEnabled whether to record monotonic per-stage timings
     * @param eligibilityGate fail-closed module target eligibility check
     * @return immutable {@link CoreThreadedFeedResult} with {@code contextAttachment == null}
     */
    public CoreThreadedFeedResult<Void> readThreadedFeed(
            CommentTarget target,
            int page,
            int size,
            CommentSortMode sortMode,
            boolean includeMetrics,
            boolean diagnosticEnabled,
            TargetEligibilityGate eligibilityGate
    ) {
        return readThreadedFeed(
                target,
                page,
                size,
                sortMode,
                includeMetrics,
                diagnosticEnabled,
                eligibilityGate,
                null
        );
    }

    /**
     * Executes mandatory Phase A core discussion feed reads inside one read-only transaction,
     * including an optional module-local read-only attachment lookup when the root slice is non-empty.
     *
     * @param target interaction comment target
     * @param page zero-based page index
     * @param size positive page size
     * @param sortMode comment sort mode (defaults to {@link CommentSortMode#FEATURED} when null)
     * @param includeMetrics whether to query {@link GetCommentTargetMetricsUseCase}
     * @param diagnosticEnabled whether to record monotonic per-stage timings
     * @param eligibilityGate fail-closed module target eligibility check
     * @param attachmentLoader optional local read-only lookup executed only when roots are non-empty;
     *                         must perform compatible local DB reads without remote I/O or mutations
     * @param <T> immutable attachment snapshot type
     * @return immutable {@link CoreThreadedFeedResult}
     */
    public <T> CoreThreadedFeedResult<T> readThreadedFeed(
            CommentTarget target,
            int page,
            int size,
            CommentSortMode sortMode,
            boolean includeMetrics,
            boolean diagnosticEnabled,
            TargetEligibilityGate eligibilityGate,
            ContextAttachmentLoader<T> attachmentLoader
    ) {
        if (target == null) {
            throw new IllegalArgumentException("target cannot be null");
        }
        if (page < 0) {
            throw new IllegalArgumentException("page cannot be negative: " + page);
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be greater than zero: " + size);
        }
        Objects.requireNonNull(eligibilityGate, "eligibilityGate cannot be null");

        CommentSortMode effectiveSortMode = (sortMode != null) ? sortMode : CommentSortMode.FEATURED;
        long t0 = diagnosticEnabled ? System.nanoTime() : 0L;

        // 1. Target eligibility gate: fail closed if target is missing/unpublished
        eligibilityGate.verifyEligible();
        long tEligibilityEnd = diagnosticEnabled ? System.nanoTime() : 0L;

        // 2. Optional target metrics aggregate
        CommentTargetMetrics metrics = includeMetrics
                ? getCommentTargetMetricsUseCase.execute(target)
                : CommentTargetMetrics.EMPTY;
        long tMetricsEnd = diagnosticEnabled ? System.nanoTime() : 0L;

        // 3. Active root comments slice
        CommentReadSlice rootSlice = listCommentRootsUseCase.execute(target, effectiveSortMode, page, size);
        long tSliceEnd = diagnosticEnabled ? System.nanoTime() : 0L;
        if (rootSlice.items().isEmpty()) {
            long tEnd = diagnosticEnabled ? System.nanoTime() : 0L;
            CoreStageTimings timings = diagnosticEnabled
                    ? new CoreStageTimings(
                    tEligibilityEnd - t0,
                    tMetricsEnd - tEligibilityEnd,
                    tSliceEnd - tMetricsEnd,
                    0L,
                    0L,
                    tEnd - tSliceEnd,
                    0L
            )
                    : CoreStageTimings.ZERO;
            return new CoreThreadedFeedResult<>(
                    metrics.threadCount(),
                    metrics.commentCount(),
                    rootSlice.page(),
                    rootSlice.size(),
                    rootSlice.hasNext(),
                    0,
                    null,
                    List.of(),
                    Map.of(),
                    DiscussionParticipants.EMPTY,
                    Map.of(),
                    timings
            );
        }

        List<CommentReadItem> roots = rootSlice.items();
        List<UUID> rootIds = roots.stream().map(CommentReadItem::id).toList();

        // 4. Optional local module metadata attachment (skipped when root slice is empty)
        T contextAttachment = (attachmentLoader != null) ? attachmentLoader.load() : null;
        long tAttachmentEnd = diagnosticEnabled ? System.nanoTime() : 0L;

        // 5. Batch retrieve visible threads for all roots in this slice
        List<CommentThreadView> threadViews = getCommentThreadsByRootIdsUseCase.execute(target, rootIds);
        long tThreadsEnd = diagnosticEnabled ? System.nanoTime() : 0L;
        Map<UUID, CommentThreadView> threadViewsByRootId = threadViews.stream()
                .collect(Collectors.toMap(
                        tv -> tv.root().id(),
                        tv -> tv,
                        (existing, replacement) -> existing
                ));

        // 6. Concurrent delete safety: preserve slice ordering among surviving roots only
        List<UUID> survivingRootIds = rootIds.stream()
                .filter(threadViewsByRootId::containsKey)
                .toList();

        if (survivingRootIds.isEmpty()) {
            long tEnd = diagnosticEnabled ? System.nanoTime() : 0L;
            CoreStageTimings timings = diagnosticEnabled
                    ? new CoreStageTimings(
                    tEligibilityEnd - t0,
                    tMetricsEnd - tEligibilityEnd,
                    tSliceEnd - tMetricsEnd,
                    tAttachmentEnd - tSliceEnd,
                    tThreadsEnd - tAttachmentEnd,
                    tEnd - tThreadsEnd,
                    0L
            )
                    : CoreStageTimings.ZERO;
            return new CoreThreadedFeedResult<>(
                    metrics.threadCount(),
                    metrics.commentCount(),
                    rootSlice.page(),
                    rootSlice.size(),
                    rootSlice.hasNext(),
                    roots.size(),
                    contextAttachment,
                    List.of(),
                    Map.of(),
                    DiscussionParticipants.EMPTY,
                    Map.of(),
                    timings
            );
        }

        // 7. Active participant & comment ID collection (excluding tombstones)
        DiscussionParticipants participants = DiscussionParticipants.collect(survivingRootIds, threadViewsByRootId);
        long tCollectEnd = diagnosticEnabled ? System.nanoTime() : 0L;

        // 8. Batch Identity public profile lookup
        Map<UUID, UserPublicProfileDTO> authorsMap = participants.authorUserIds().isEmpty()
                ? Map.of()
                : userIdentityContract.findPublicProfilesByIds(participants.authorUserIds());
        long tIdentityEnd = diagnosticEnabled ? System.nanoTime() : 0L;

        CoreStageTimings timings = diagnosticEnabled
                ? new CoreStageTimings(
                tEligibilityEnd - t0,
                tMetricsEnd - tEligibilityEnd,
                tSliceEnd - tMetricsEnd,
                tAttachmentEnd - tSliceEnd,
                tThreadsEnd - tAttachmentEnd,
                tCollectEnd - tThreadsEnd,
                tIdentityEnd - tCollectEnd
        )
                : CoreStageTimings.ZERO;

        return new CoreThreadedFeedResult<>(
                metrics.threadCount(),
                metrics.commentCount(),
                rootSlice.page(),
                rootSlice.size(),
                rootSlice.hasNext(),
                roots.size(),
                contextAttachment,
                survivingRootIds,
                threadViewsByRootId,
                participants,
                authorsMap != null ? authorsMap : Map.of(),
                timings
        );
    }

    /**
     * Executes mandatory Phase A single-thread reads (eligibility, target/root scope validation,
     * thread hydration, participant collection, and batched author lookup) inside one read-only transaction.
     */
    public CoreSingleThreadResult<Void> readSingleThreadCore(
            CommentTarget expectedTarget,
            UUID rootCommentId,
            TargetEligibilityGate eligibilityGate
    ) {
        return readSingleThreadCore(expectedTarget, rootCommentId, eligibilityGate, null);
    }

    /**
     * Executes mandatory Phase A single-thread reads plus an optional compatible local attachment lookup
     * inside one read-only transaction.
     */
    public <T> CoreSingleThreadResult<T> readSingleThreadCore(
            CommentTarget expectedTarget,
            UUID rootCommentId,
            TargetEligibilityGate eligibilityGate,
            ContextAttachmentLoader<T> attachmentLoader
    ) {
        if (expectedTarget == null) {
            throw new IllegalArgumentException("expectedTarget cannot be null");
        }
        if (rootCommentId == null) {
            throw new IllegalArgumentException("rootCommentId cannot be null");
        }
        Objects.requireNonNull(eligibilityGate, "eligibilityGate cannot be null");
        if (validateCommentTargetScopeUseCase == null || getCommentThreadUseCase == null) {
            throw new IllegalStateException(
                    "ValidateCommentTargetScopeUseCase and GetCommentThreadUseCase are required for single-thread reads."
            );
        }

        // 1. Fail-closed target eligibility check
        eligibilityGate.verifyEligible();

        // 2. Cross-target & root comment guard
        validateCommentTargetScopeUseCase.executeRoot(rootCommentId, expectedTarget);

        // 3. Thread retrieval (active root + visible flat replies)
        CommentThreadView threadView = getCommentThreadUseCase.execute(rootCommentId);

        // 4. Optional local context attachment
        T contextAttachment = (attachmentLoader != null) ? attachmentLoader.load() : null;

        // 5. Collect active participants and batch resolve author profiles
        DiscussionParticipants participants = DiscussionParticipants.collectForSingleThread(threadView);
        Map<UUID, UserPublicProfileDTO> authorsMap = participants.authorUserIds().isEmpty()
                ? Map.of()
                : userIdentityContract.findPublicProfilesByIds(participants.authorUserIds());

        return new CoreSingleThreadResult<>(
                threadView,
                participants,
                authorsMap != null ? authorsMap : Map.of(),
                contextAttachment
        );
    }

    /**
     * Immutable detached result of a Phase A single-thread read.
     */
    public record CoreSingleThreadResult<T>(
            CommentThreadView threadView,
            DiscussionParticipants participants,
            Map<UUID, UserPublicProfileDTO> authorsMap,
            T contextAttachment
    ) {
        public CoreSingleThreadResult {
            Objects.requireNonNull(threadView, "threadView cannot be null");
            participants = participants == null ? DiscussionParticipants.EMPTY : participants;
            authorsMap = (authorsMap == null || authorsMap.isEmpty())
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(authorsMap));
        }
    }

    /**
     * Fail-closed target eligibility policy callback executed within Phase A's read-only transaction.
     * Implementations must perform only local read-only checks.
     */
    @FunctionalInterface
    public interface TargetEligibilityGate {
        void verifyEligible();
    }

    /**
     * Optional context metadata callback executed within Phase A's read-only transaction
     * only when the root slice is non-empty. Must perform only compatible local reads
     * and return an immutable snapshot.
     */
    @FunctionalInterface
    public interface ContextAttachmentLoader<T> {
        T load();
    }

    public record CoreStageTimings(
            long eligibilityCheckNanos,
            long targetMetricsNanos,
            long rootSliceNanos,
            long contextAttachmentNanos,
            long threadsBatchNanos,
            long participantCollectNanos,
            long identityBatchNanos
    ) {
        public static final CoreStageTimings ZERO = new CoreStageTimings(0L, 0L, 0L, 0L, 0L, 0L, 0L);
    }

    public record CoreThreadedFeedResult<T>(
            int threadCount,
            int commentCount,
            int page,
            int size,
            boolean hasNext,
            int rawRootCount,
            T contextAttachment,
            List<UUID> survivingRootIds,
            Map<UUID, CommentThreadView> threadViewsByRootId,
            DiscussionParticipants participants,
            Map<UUID, UserPublicProfileDTO> authorsMap,
            CoreStageTimings timings
    ) {
        public CoreThreadedFeedResult {
            survivingRootIds = survivingRootIds == null ? List.of() : List.copyOf(survivingRootIds);
            threadViewsByRootId = (threadViewsByRootId == null || threadViewsByRootId.isEmpty())
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(threadViewsByRootId));
            participants = participants == null ? DiscussionParticipants.EMPTY : participants;
            authorsMap = (authorsMap == null || authorsMap.isEmpty())
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(authorsMap));
            timings = timings == null ? CoreStageTimings.ZERO : timings;
        }
    }
}
