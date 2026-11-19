package com.universe.interaction.entry.shared;

import com.universe.interaction.application.query.GetBatchReactionSummariesUseCase;
import com.universe.interaction.application.query.ReactionSummary;
import com.universe.interaction.domain.reaction.ReactionTargetType;
import com.universe.interaction.entry.dto.ReactionSummaryResponseDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Shared non-transactional collaborator for Phase B supplemental Reaction enrichment on comment reads.
 *
 * <p>Callers MUST invoke this service only after any Phase A core read transaction has completed
 * and closed. Delegates to {@link GetBatchReactionSummariesUseCase} in an independent sequential
 * read-only transaction and degrades best-effort on runtime failures while emitting a sanitized
 * warning without UUIDs, SQL fragments, or private exception payloads.
 */
@Service
public class CommentReactionEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(CommentReactionEnrichmentService.class);

    private final GetBatchReactionSummariesUseCase getBatchReactionSummariesUseCase;

    public CommentReactionEnrichmentService(GetBatchReactionSummariesUseCase getBatchReactionSummariesUseCase) {
        this.getBatchReactionSummariesUseCase = Objects.requireNonNull(
                getBatchReactionSummariesUseCase,
                "GetBatchReactionSummariesUseCase cannot be null."
        );
    }

    /**
     * Loads batched Reaction summaries for active comments with best-effort failure isolation.
     *
     * @param contextLabel short static label identifying the calling discussion surface (no IDs/PII)
     * @param activeCommentIds active non-tombstone comment IDs
     * @param viewerUserId optional authenticated viewer ID (null for guests)
     * @param trackTiming whether to capture monotonic elapsed nanoseconds
     * @return immutable {@link ReactionEnrichmentResult}
     */
    public ReactionEnrichmentResult loadCommentReactionSummaries(
            String contextLabel,
            List<UUID> activeCommentIds,
            UUID viewerUserId,
            boolean trackTiming
    ) {
        if (activeCommentIds == null || activeCommentIds.isEmpty()) {
            return ReactionEnrichmentResult.EMPTY_SUCCESS;
        }

        String safeContext = (contextLabel == null || contextLabel.isBlank()) ? "comment" : contextLabel.trim();
        boolean authenticatedViewer = viewerUserId != null;
        long tStart = trackTiming ? System.nanoTime() : 0L;

        try {
            Map<UUID, ReactionSummary> summaries = getBatchReactionSummariesUseCase.execute(
                    ReactionTargetType.COMMENT,
                    activeCommentIds,
                    viewerUserId
            );
            long elapsed = trackTiming ? Math.max(0L, System.nanoTime() - tStart) : 0L;
            if (summaries == null || summaries.isEmpty()) {
                return new ReactionEnrichmentResult(Map.of(), false, elapsed);
            }
            Map<UUID, ReactionSummaryResponseDTO> dtoMap = summaries.entrySet().stream()
                    .filter(e -> e.getKey() != null && e.getValue() != null)
                    .collect(Collectors.toUnmodifiableMap(
                            Map.Entry::getKey,
                            e -> ReactionSummaryResponseDTO.from(e.getValue())
                    ));
            return new ReactionEnrichmentResult(dtoMap, false, elapsed);
        } catch (RuntimeException ex) {
            long elapsed = trackTiming ? Math.max(0L, System.nanoTime() - tStart) : 0L;
            log.warn(
                    "Failed to load reaction summaries for {} discussion; using empty fallback: exceptionType={}, activeCommentCount={}, authenticatedViewer={}",
                    safeContext,
                    ex.getClass().getSimpleName(),
                    activeCommentIds.size(),
                    authenticatedViewer
            );
            return new ReactionEnrichmentResult(Map.of(), true, elapsed);
        }
    }

    /**
     * Immutable result of a supplemental Phase B Reaction lookup.
     */
    public record ReactionEnrichmentResult(
            Map<UUID, ReactionSummaryResponseDTO> summariesByCommentId,
            boolean failed,
            long elapsedNanos
    ) {
        public static final ReactionEnrichmentResult EMPTY_SUCCESS =
                new ReactionEnrichmentResult(Map.of(), false, 0L);

        public ReactionEnrichmentResult {
            summariesByCommentId = (summariesByCommentId == null || summariesByCommentId.isEmpty())
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(summariesByCommentId));
            elapsedNanos = Math.max(0L, elapsedNanos);
        }
    }
}
