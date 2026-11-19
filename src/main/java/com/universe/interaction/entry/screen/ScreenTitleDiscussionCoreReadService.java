package com.universe.interaction.entry.screen;

import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.interaction.application.exceptions.CommentTargetNotEligibleException;
import com.universe.interaction.application.query.CommentThreadView;
import com.universe.interaction.application.query.GetCommentTargetMetricsUseCase;
import com.universe.interaction.application.query.GetCommentThreadsByRootIdsUseCase;
import com.universe.interaction.application.query.ListCommentRootsUseCase;
import com.universe.interaction.domain.CommentSortMode;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.entry.shared.DiscussionCoreReadService;
import com.universe.interaction.entry.shared.DiscussionCoreReadService.CoreThreadedFeedResult;
import com.universe.screen.contracts.dto.ScreenEntryOriginDTO;
import com.universe.screen.contracts.interfaces.ScreenDiscussionContract;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Thin Screen-specific core read adapter delegating shared Phase A transaction ownership and
 * generic feed retrieval to {@link DiscussionCoreReadService}.
 *
 * <p>Retains Screen-only policies: published-title gating and published episode-origin lookup
 * via {@link ScreenDiscussionContract}. Transaction demarcation ({@code @Transactional(readOnly = true)})
 * is owned exclusively by the Spring-proxied {@link DiscussionCoreReadService}.
 */
@Service
public class ScreenTitleDiscussionCoreReadService {

    private final ScreenDiscussionContract screenDiscussionContract;
    private final DiscussionCoreReadService discussionCoreReadService;

    @Autowired
    public ScreenTitleDiscussionCoreReadService(
            ScreenDiscussionContract screenDiscussionContract,
            DiscussionCoreReadService discussionCoreReadService
    ) {
        this.screenDiscussionContract = Objects.requireNonNull(
                screenDiscussionContract, "ScreenDiscussionContract cannot be null"
        );
        this.discussionCoreReadService = Objects.requireNonNull(
                discussionCoreReadService, "DiscussionCoreReadService cannot be null"
        );
    }

    public ScreenTitleDiscussionCoreReadService(
            ScreenDiscussionContract screenDiscussionContract,
            GetCommentTargetMetricsUseCase getCommentTargetMetricsUseCase,
            ListCommentRootsUseCase listCommentRootsUseCase,
            GetCommentThreadsByRootIdsUseCase getCommentThreadsByRootIdsUseCase,
            UserIdentityContract userIdentityContract
    ) {
        this(
                screenDiscussionContract,
                new DiscussionCoreReadService(
                        getCommentTargetMetricsUseCase,
                        listCommentRootsUseCase,
                        getCommentThreadsByRootIdsUseCase,
                        userIdentityContract
                )
        );
    }

    /**
     * Reads the core Screen discussion feed snapshot inside {@link DiscussionCoreReadService}'s
     * single read-only Phase A transaction.
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

        CoreThreadedFeedResult<Map<UUID, ScreenEntryOriginDTO>> core = discussionCoreReadService.readThreadedFeed(
                CommentTarget.screenTitle(titleId),
                page,
                size,
                sortMode,
                true,
                diagnosticEnabled,
                () -> {
                    if (!screenDiscussionContract.isPublishedTitle(titleId)) {
                        throw new CommentTargetNotEligibleException("ScreenTitle is not published: " + titleId);
                    }
                },
                () -> screenDiscussionContract.findPublishedEntryOriginsByTitleId(titleId)
        );

        DiscussionCoreReadService.CoreStageTimings sharedTimings = core.timings();
        CoreStageTimings screenTimings = diagnosticEnabled
                ? new CoreStageTimings(
                sharedTimings.eligibilityCheckNanos(),
                sharedTimings.targetMetricsNanos(),
                sharedTimings.rootSliceNanos(),
                sharedTimings.contextAttachmentNanos(),
                sharedTimings.threadsBatchNanos(),
                sharedTimings.participantCollectNanos(),
                sharedTimings.identityBatchNanos()
        )
                : CoreStageTimings.ZERO;

        return new CoreFeedReadResult(
                core.threadCount(),
                core.commentCount(),
                core.page(),
                core.size(),
                core.hasNext(),
                core.rawRootCount(),
                core.contextAttachment() != null ? core.contextAttachment() : Map.of(),
                core.survivingRootIds(),
                core.threadViewsByRootId(),
                core.participants().authorUserIds(),
                core.participants().activeCommentIds(),
                core.authorsMap(),
                screenTimings
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
            publishedOriginsById = (publishedOriginsById == null || publishedOriginsById.isEmpty())
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(publishedOriginsById));
            survivingRootIds = survivingRootIds == null ? List.of() : List.copyOf(survivingRootIds);
            threadViewsByRootId = (threadViewsByRootId == null || threadViewsByRootId.isEmpty())
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(threadViewsByRootId));
            authorUserIds = authorUserIds == null ? Set.of() : Set.copyOf(authorUserIds);
            activeCommentIds = activeCommentIds == null ? List.of() : List.copyOf(activeCommentIds);
            authorsMap = (authorsMap == null || authorsMap.isEmpty())
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(authorsMap));
            timings = timings == null ? CoreStageTimings.ZERO : timings;
        }
    }
}
