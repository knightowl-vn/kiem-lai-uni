package com.universe.interaction.entry.shared;

import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.interaction.application.query.CommentReadItem;
import com.universe.interaction.application.query.CommentReadSlice;
import com.universe.interaction.application.query.CommentTargetMetrics;
import com.universe.interaction.application.query.CommentThreadView;
import com.universe.interaction.application.query.CountVisibleActiveRepliesByRootIdsUseCase;
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
    private final CountVisibleActiveRepliesByRootIdsUseCase countVisibleActiveRepliesByRootIdsUseCase;
    private final UserIdentityContract userIdentityContract;

    @Autowired
    public DiscussionCoreReadService(
            GetCommentTargetMetricsUseCase getCommentTargetMetricsUseCase,
            ListCommentRootsUseCase listCommentRootsUseCase,
            GetCommentThreadsByRootIdsUseCase getCommentThreadsByRootIdsUseCase,
            ValidateCommentTargetScopeUseCase validateCommentTargetScopeUseCase,
            GetCommentThreadUseCase getCommentThreadUseCase,
            CountVisibleActiveRepliesByRootIdsUseCase countVisibleActiveRepliesByRootIdsUseCase,
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
        this.countVisibleActiveRepliesByRootIdsUseCase = Objects.requireNonNull(
                countVisibleActiveRepliesByRootIdsUseCase,
                "CountVisibleActiveRepliesByRootIdsUseCase cannot be null."
        );
        this.userIdentityContract = Objects.requireNonNull(
                userIdentityContract,
                "UserIdentityContract cannot be null."
        );
    }

    /**
     * Compatibility constructor for callers that do not exercise roots-only feed reads.
     */
    public DiscussionCoreReadService(
            GetCommentTargetMetricsUseCase getCommentTargetMetricsUseCase,
            ListCommentRootsUseCase listCommentRootsUseCase,
            GetCommentThreadsByRootIdsUseCase getCommentThreadsByRootIdsUseCase,
            ValidateCommentTargetScopeUseCase validateCommentTargetScopeUseCase,
            GetCommentThreadUseCase getCommentThreadUseCase,
            UserIdentityContract userIdentityContract
    ) {
        this(
                Objects.requireNonNull(getCommentTargetMetricsUseCase, "GetCommentTargetMetricsUseCase cannot be null."),
                Objects.requireNonNull(listCommentRootsUseCase, "ListCommentRootsUseCase cannot be null."),
                Objects.requireNonNull(getCommentThreadsByRootIdsUseCase, "GetCommentThreadsByRootIdsUseCase cannot be null."),
                Objects.requireNonNull(validateCommentTargetScopeUseCase, "ValidateCommentTargetScopeUseCase cannot be null."),
                Objects.requireNonNull(getCommentThreadUseCase, "GetCommentThreadUseCase cannot be null."),
                null,
                userIdentityContract,
                true
        );
    }

    private DiscussionCoreReadService(
            GetCommentTargetMetricsUseCase getCommentTargetMetricsUseCase,
            ListCommentRootsUseCase listCommentRootsUseCase,
            GetCommentThreadsByRootIdsUseCase getCommentThreadsByRootIdsUseCase,
            ValidateCommentTargetScopeUseCase validateCommentTargetScopeUseCase,
            GetCommentThreadUseCase getCommentThreadUseCase,
            CountVisibleActiveRepliesByRootIdsUseCase countVisibleActiveRepliesByRootIdsUseCase,
            UserIdentityContract userIdentityContract,
            boolean internalMarker
    ) {
        this.getCommentTargetMetricsUseCase = getCommentTargetMetricsUseCase;
        this.listCommentRootsUseCase = listCommentRootsUseCase;
        this.getCommentThreadsByRootIdsUseCase = getCommentThreadsByRootIdsUseCase;
        this.validateCommentTargetScopeUseCase = validateCommentTargetScopeUseCase;
        this.getCommentThreadUseCase = getCommentThreadUseCase;
        this.countVisibleActiveRepliesByRootIdsUseCase = countVisibleActiveRepliesByRootIdsUseCase;
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
        this(
                Objects.requireNonNull(getCommentTargetMetricsUseCase, "GetCommentTargetMetricsUseCase cannot be null."),
                Objects.requireNonNull(listCommentRootsUseCase, "ListCommentRootsUseCase cannot be null."),
                Objects.requireNonNull(getCommentThreadsByRootIdsUseCase, "GetCommentThreadsByRootIdsUseCase cannot be null."),
                null,
                null,
                null,
                userIdentityContract,
                true
        );
    }

    /**
     * Compatibility factory for unit tests exercising feed coordinators with {@code includeMetrics = false}.
     */
    public static DiscussionCoreReadService forSliceReadWithoutMetrics(
            ListCommentRootsUseCase listCommentRootsUseCase,
            GetCommentThreadsByRootIdsUseCase getCommentThreadsByRootIdsUseCase,
            UserIdentityContract userIdentityContract
    ) {
        return new DiscussionCoreReadService(
                null,
                Objects.requireNonNull(listCommentRootsUseCase, "ListCommentRootsUseCase cannot be null."),
                Objects.requireNonNull(getCommentThreadsByRootIdsUseCase, "GetCommentThreadsByRootIdsUseCase cannot be null."),
                null,
                null,
                null,
                userIdentityContract,
                true
        );
    }

    /**
     * Compatibility factory for unit tests exercising known-root coordinators.
     */
    public static DiscussionCoreReadService forKnownRootsRead(
            GetCommentThreadsByRootIdsUseCase getCommentThreadsByRootIdsUseCase,
            UserIdentityContract userIdentityContract
    ) {
        return new DiscussionCoreReadService(
                null,
                null,
                Objects.requireNonNull(getCommentThreadsByRootIdsUseCase, "GetCommentThreadsByRootIdsUseCase cannot be null."),
                null,
                null,
                null,
                userIdentityContract,
                true
        );
    }

    /**
     * Compatibility factory for unit tests exercising roots-only feed and single-thread coordinators.
     */
    public static DiscussionCoreReadService forRootsOnlyFeedAndSingleThread(
            GetCommentTargetMetricsUseCase getCommentTargetMetricsUseCase,
            ListCommentRootsUseCase listCommentRootsUseCase,
            CountVisibleActiveRepliesByRootIdsUseCase countVisibleActiveRepliesByRootIdsUseCase,
            ValidateCommentTargetScopeUseCase validateCommentTargetScopeUseCase,
            GetCommentThreadUseCase getCommentThreadUseCase,
            UserIdentityContract userIdentityContract
    ) {
        return new DiscussionCoreReadService(
                Objects.requireNonNull(getCommentTargetMetricsUseCase, "GetCommentTargetMetricsUseCase cannot be null."),
                Objects.requireNonNull(listCommentRootsUseCase, "ListCommentRootsUseCase cannot be null."),
                null,
                Objects.requireNonNull(validateCommentTargetScopeUseCase, "ValidateCommentTargetScopeUseCase cannot be null."),
                Objects.requireNonNull(getCommentThreadUseCase, "GetCommentThreadUseCase cannot be null."),
                Objects.requireNonNull(countVisibleActiveRepliesByRootIdsUseCase, "CountVisibleActiveRepliesByRootIdsUseCase cannot be null."),
                userIdentityContract,
                true
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
        return executeThreadedFeedRead(
                target,
                page,
                size,
                sortMode,
                includeMetrics,
                diagnosticEnabled,
                eligibilityGate,
                attachmentLoader,
                null,
                null
        );
    }

    /**
     * Executes mandatory Phase A core discussion feed reads inside one read-only transaction,
     * including a local read-only attachment lookup invoked only with surviving root IDs
     * after concurrent-delete thread reconciliation.
     *
     * @param target interaction comment target
     * @param page zero-based page index
     * @param size positive page size
     * @param sortMode comment sort mode (defaults to {@link CommentSortMode#FEATURED} when null)
     * @param includeMetrics whether to query {@link GetCommentTargetMetricsUseCase}
     * @param diagnosticEnabled whether to record monotonic per-stage timings
     * @param eligibilityGate fail-closed module target eligibility check
     * @param survivingRootsAttachmentLoader optional local read-only lookup executed only when surviving roots are non-empty
     * @param <T> immutable attachment snapshot type
     * @return immutable {@link CoreThreadedFeedResult}
     */
    public <T> CoreThreadedFeedResult<T> readThreadedFeedForSurvivingRoots(
            CommentTarget target,
            int page,
            int size,
            CommentSortMode sortMode,
            boolean includeMetrics,
            boolean diagnosticEnabled,
            TargetEligibilityGate eligibilityGate,
            SurvivingRootsAttachmentLoader<T> survivingRootsAttachmentLoader
    ) {
        return executeThreadedFeedRead(
                target,
                page,
                size,
                sortMode,
                includeMetrics,
                diagnosticEnabled,
                eligibilityGate,
                null,
                survivingRootsAttachmentLoader,
                null
        );
    }

    /**
     * Executes mandatory Phase A core discussion feed reads inside one read-only transaction,
     * invoking an optional post-thread attachment lookup with the surviving root IDs and resolved
     * visible thread views so callers can scope supplemental metadata lookups strictly to visible items.
     */
    public <T> CoreThreadedFeedResult<T> readThreadedFeedForSurvivingThreads(
            CommentTarget target,
            int page,
            int size,
            CommentSortMode sortMode,
            boolean includeMetrics,
            boolean diagnosticEnabled,
            TargetEligibilityGate eligibilityGate,
            SurvivingThreadsAttachmentLoader<T> survivingThreadsAttachmentLoader
    ) {
        return executeThreadedFeedRead(
                target,
                page,
                size,
                sortMode,
                includeMetrics,
                diagnosticEnabled,
                eligibilityGate,
                null,
                null,
                survivingThreadsAttachmentLoader
        );
    }

    private <T> CoreThreadedFeedResult<T> executeThreadedFeedRead(
            CommentTarget target,
            int page,
            int size,
            CommentSortMode sortMode,
            boolean includeMetrics,
            boolean diagnosticEnabled,
            TargetEligibilityGate eligibilityGate,
            ContextAttachmentLoader<T> preThreadsAttachmentLoader,
            SurvivingRootsAttachmentLoader<T> postThreadsAttachmentLoader,
            SurvivingThreadsAttachmentLoader<T> postThreadsWithViewsAttachmentLoader
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
        if (listCommentRootsUseCase == null) {
            throw new IllegalStateException("ListCommentRootsUseCase is required for threaded feed reads.");
        }
        if (getCommentThreadsByRootIdsUseCase == null) {
            throw new IllegalStateException("GetCommentThreadsByRootIdsUseCase is required for threaded feed reads.");
        }
        if (includeMetrics && getCommentTargetMetricsUseCase == null) {
            throw new IllegalStateException("GetCommentTargetMetricsUseCase is required when includeMetrics is true.");
        }

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

        // 4. Optional pre-thread local module metadata attachment (skipped when root slice is empty)
        long tPreAttachStart = diagnosticEnabled ? System.nanoTime() : 0L;
        T contextAttachment = (preThreadsAttachmentLoader != null) ? preThreadsAttachmentLoader.load() : null;
        long tPreAttachEnd = diagnosticEnabled ? System.nanoTime() : 0L;

        // 5. Batch retrieve visible threads for all roots in this slice
        long tThreadsStart = diagnosticEnabled ? System.nanoTime() : 0L;
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
                    tPreAttachEnd - tPreAttachStart,
                    tThreadsEnd - tThreadsStart,
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

        // 7. Optional post-thread attachment resolved strictly for surviving roots/threads
        long tPostAttachStart = diagnosticEnabled ? System.nanoTime() : 0L;
        if (postThreadsAttachmentLoader != null) {
            contextAttachment = postThreadsAttachmentLoader.load(survivingRootIds);
        } else if (postThreadsWithViewsAttachmentLoader != null) {
            contextAttachment = postThreadsWithViewsAttachmentLoader.load(
                    survivingRootIds,
                    Collections.unmodifiableMap(threadViewsByRootId)
            );
        }
        long tPostAttachEnd = diagnosticEnabled ? System.nanoTime() : 0L;

        // 8. Active participant & comment ID collection (excluding tombstones)
        long tCollectStart = diagnosticEnabled ? System.nanoTime() : 0L;
        DiscussionParticipants participants = DiscussionParticipants.collect(survivingRootIds, threadViewsByRootId);
        long tCollectEnd = diagnosticEnabled ? System.nanoTime() : 0L;

        // 9. Batch Identity public profile lookup
        Map<UUID, UserPublicProfileDTO> authorsMap = participants.authorUserIds().isEmpty()
                ? Map.of()
                : userIdentityContract.findPublicProfilesByIds(participants.authorUserIds());
        long tIdentityEnd = diagnosticEnabled ? System.nanoTime() : 0L;

        long contextAttachmentNanos = (tPreAttachEnd - tPreAttachStart) + (tPostAttachEnd - tPostAttachStart);
        CoreStageTimings timings = diagnosticEnabled
                ? new CoreStageTimings(
                tEligibilityEnd - t0,
                tMetricsEnd - tEligibilityEnd,
                tSliceEnd - tMetricsEnd,
                contextAttachmentNanos,
                tThreadsEnd - tThreadsStart,
                tCollectEnd - tCollectStart,
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
     * Executes mandatory Phase A known-root discussion reads (eligibility gate, module-local known-root
     * selection, batched visible thread retrieval, active participant collection, and batched author
     * lookup) inside one read-only transaction.
     *
     * @param target interaction comment target
     * @param eligibilityGate fail-closed module target eligibility check
     * @param rootsResolver module-local read-only callback resolving ordered root comment IDs and metadata
     * @param <T> immutable context attachment snapshot type
     * @return immutable {@link CoreKnownRootsResult}
     */
    public <T> CoreKnownRootsResult<T> readKnownRootsCore(
            CommentTarget target,
            TargetEligibilityGate eligibilityGate,
            KnownRootsResolver<T> rootsResolver
    ) {
        if (target == null) {
            throw new IllegalArgumentException("target cannot be null");
        }
        Objects.requireNonNull(eligibilityGate, "eligibilityGate cannot be null");
        Objects.requireNonNull(rootsResolver, "rootsResolver cannot be null");
        if (getCommentThreadsByRootIdsUseCase == null) {
            throw new IllegalStateException("GetCommentThreadsByRootIdsUseCase is required for known-root reads.");
        }

        // 1. Fail-closed target eligibility check
        eligibilityGate.verifyEligible();

        // 2. Module-local known-root selection and attachment resolution
        KnownRootsSelection<T> selection = Objects.requireNonNull(
                rootsResolver.resolve(),
                "rootsResolver returned null selection"
        );
        List<UUID> rootIds = selection.rootIds();
        if (rootIds.isEmpty()) {
            return new CoreKnownRootsResult<>(
                    selection.contextAttachment(),
                    List.of(),
                    DiscussionParticipants.EMPTY,
                    Map.of()
            );
        }

        // 3. Batch retrieve visible threads for resolved root IDs
        List<CommentThreadView> rawThreads = getCommentThreadsByRootIdsUseCase.execute(target, rootIds);
        List<CommentThreadView> threadViews = (rawThreads == null) ? List.of() : List.copyOf(rawThreads);
        if (threadViews.isEmpty()) {
            return new CoreKnownRootsResult<>(
                    selection.contextAttachment(),
                    List.of(),
                    DiscussionParticipants.EMPTY,
                    Map.of()
            );
        }

        // 4. Active participant & comment ID collection (excluding tombstones)
        DiscussionParticipants participants = DiscussionParticipants.collectForThreads(threadViews);

        // 5. Batch Identity public profile lookup
        Map<UUID, UserPublicProfileDTO> authorsMap = participants.authorUserIds().isEmpty()
                ? Map.of()
                : userIdentityContract.findPublicProfilesByIds(participants.authorUserIds());

        return new CoreKnownRootsResult<>(
                selection.contextAttachment(),
                threadViews,
                participants,
                authorsMap != null ? authorsMap : Map.of()
        );
    }

    /**
     * Executes mandatory Phase A roots-only discussion feed reads inside one read-only transaction.
     *
     * <p>Executes: eligibility gate -> target metrics -> active root slice -> batch active reply counts
     * for returned root IDs -> root-only participant collection -> batched Identity profiles for root authors.
     * Intentionally never invokes {@link GetCommentThreadsByRootIdsUseCase} or hydrates reply entities.
     *
     * @param target interaction comment target
     * @param page zero-based page index
     * @param size positive page size
     * @param sortMode comment sort mode (defaults to {@link CommentSortMode#FEATURED} when null)
     * @param eligibilityGate fail-closed module target eligibility check
     * @return immutable {@link CoreRootsOnlyFeedResult}
     */
    public CoreRootsOnlyFeedResult readRootsOnlyFeed(
            CommentTarget target,
            int page,
            int size,
            CommentSortMode sortMode,
            TargetEligibilityGate eligibilityGate
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
        if (getCommentTargetMetricsUseCase == null
                || listCommentRootsUseCase == null
                || countVisibleActiveRepliesByRootIdsUseCase == null) {
            throw new IllegalStateException(
                    "GetCommentTargetMetricsUseCase, ListCommentRootsUseCase, and CountVisibleActiveRepliesByRootIdsUseCase are required for roots-only feed reads."
            );
        }

        CommentSortMode effectiveSortMode = (sortMode != null) ? sortMode : CommentSortMode.FEATURED;

        // 1. Fail-closed target eligibility check
        eligibilityGate.verifyEligible();

        // 2. Authoritative target metrics aggregate
        CommentTargetMetrics metrics = getCommentTargetMetricsUseCase.execute(target);

        // 3. Active root comments slice (no reply entity hydration)
        CommentReadSlice rootSlice = listCommentRootsUseCase.execute(target, effectiveSortMode, page, size);
        if (rootSlice.items().isEmpty()) {
            return new CoreRootsOnlyFeedResult(
                    metrics.threadCount(),
                    metrics.commentCount(),
                    rootSlice.page(),
                    rootSlice.size(),
                    rootSlice.hasNext(),
                    List.of(),
                    Map.of(),
                    DiscussionParticipants.EMPTY,
                    Map.of()
            );
        }

        List<CommentReadItem> roots = List.copyOf(rootSlice.items());
        List<UUID> rootIds = roots.stream().map(CommentReadItem::id).toList();

        // 4. Batch active reply counts strictly for roots in this slice
        Map<UUID, Long> replyCountsByRootId = countVisibleActiveRepliesByRootIdsUseCase.execute(rootIds);

        // 5. Collect active root participants only (zero reply authors, zero reply IDs)
        DiscussionParticipants participants = DiscussionParticipants.collectForRoots(roots);

        // 6. Batch Identity public profile lookup strictly for root authors
        Map<UUID, UserPublicProfileDTO> authorsMap = participants.authorUserIds().isEmpty()
                ? Map.of()
                : userIdentityContract.findPublicProfilesByIds(participants.authorUserIds());

        return new CoreRootsOnlyFeedResult(
                metrics.threadCount(),
                metrics.commentCount(),
                rootSlice.page(),
                rootSlice.size(),
                rootSlice.hasNext(),
                roots,
                replyCountsByRootId != null ? replyCountsByRootId : Map.of(),
                participants,
                authorsMap != null ? authorsMap : Map.of()
        );
    }

    /**
     * Immutable detached result of a Phase A roots-only discussion feed read.
     */
    public record CoreRootsOnlyFeedResult(
            long threadCount,
            long commentCount,
            int page,
            int size,
            boolean hasNext,
            List<CommentReadItem> roots,
            Map<UUID, Long> replyCountsByRootId,
            DiscussionParticipants participants,
            Map<UUID, UserPublicProfileDTO> authorsMap
    ) {
        public CoreRootsOnlyFeedResult {
            roots = (roots == null || roots.isEmpty()) ? List.of() : List.copyOf(roots);
            replyCountsByRootId = (replyCountsByRootId == null || replyCountsByRootId.isEmpty())
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(replyCountsByRootId));
            participants = participants == null ? DiscussionParticipants.EMPTY : participants;
            authorsMap = (authorsMap == null || authorsMap.isEmpty())
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(authorsMap));
        }
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
     * Module-local selection of known root comment IDs plus detached context metadata.
     */
    public record KnownRootsSelection<T>(
            List<UUID> rootIds,
            T contextAttachment
    ) {
        public KnownRootsSelection {
            rootIds = (rootIds == null || rootIds.isEmpty()) ? List.of() : List.copyOf(rootIds);
        }
    }

    /**
     * Immutable detached result of a Phase A known-root discussion read.
     */
    public record CoreKnownRootsResult<T>(
            T contextAttachment,
            List<CommentThreadView> threadViews,
            DiscussionParticipants participants,
            Map<UUID, UserPublicProfileDTO> authorsMap
    ) {
        public CoreKnownRootsResult {
            threadViews = (threadViews == null || threadViews.isEmpty()) ? List.of() : List.copyOf(threadViews);
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

    /**
     * Optional context metadata callback executed within Phase A's read-only transaction
     * strictly for surviving roots after thread reconciliation. Must perform only compatible
     * local reads and return an immutable snapshot.
     */
    @FunctionalInterface
    public interface SurvivingRootsAttachmentLoader<T> {
        T load(List<UUID> survivingRootIds);
    }

    /**
     * Optional context metadata callback executed within Phase A's read-only transaction
     * after thread reconciliation, receiving surviving root IDs and resolved visible threads.
     */
    @FunctionalInterface
    public interface SurvivingThreadsAttachmentLoader<T> {
        T load(List<UUID> survivingRootIds, Map<UUID, CommentThreadView> threadViewsByRootId);
    }

    /**
     * Callback executed within Phase A's read-only transaction to resolve ordered known root IDs
     * and detached module metadata. Must perform only compatible local reads.
     */
    @FunctionalInterface
    public interface KnownRootsResolver<T> {
        KnownRootsSelection<T> resolve();
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
