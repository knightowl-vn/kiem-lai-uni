package com.universe.interaction.application.mutation;

import com.universe.interaction.application.exceptions.IncompletePostInteractionCleanupException;
import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.application.ports.CommentRevisionRepositoryPort;
import com.universe.interaction.application.ports.CommunityPostInteractionCleanupPort;
import com.universe.interaction.application.ports.InteractionReportRepositoryPort;
import com.universe.interaction.application.ports.ReactionRepositoryPort;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentTargetType;
import com.universe.interaction.domain.reaction.ReactionTarget;
import com.universe.interaction.domain.reaction.ReactionTargetType;
import com.universe.interaction.domain.report.ReportTargetType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Service orchestrating deterministic multi-pass cleanup of interaction entities and stamping reports
 * when a Community post is physically deleted.
 *
 * <p><b>Lifecycle Precondition:</b>
 * Invoked by the Community post deletion coordinator while holding the Community post deletion/mutation barrier.
 *
 * <p><b>Execution Strategy:</b>
 * <ol>
 *   <li>Stabilizes and locks the entire comment set under Level 2 {@code PESSIMISTIC_WRITE} locks
 *       ({@link CommentRepositoryPort#lockAllCommentsByTarget});</li>
 *   <li>Stamps evidence retention timestamps on reports for both the post and all comments;</li>
 *   <li>Purges child reactions on post and comments;</li>
 *   <li>Purges comment revisions and comments;</li>
 *   <li>Authoritatively verifies zero-residual invariants across all interaction tables before returning.</li>
 * </ol>
 */
@Service
@Transactional
public class CleanupCommunityPostInteractionsUseCase implements CommunityPostInteractionCleanupPort {

    public static final int MAX_CLEANUP_PASSES = 3;

    private final CommentRepositoryPort commentRepositoryPort;
    private final CommentRevisionRepositoryPort commentRevisionRepositoryPort;
    private final ReactionRepositoryPort reactionRepositoryPort;
    private final InteractionReportRepositoryPort reportRepositoryPort;

    public CleanupCommunityPostInteractionsUseCase(
            CommentRepositoryPort commentRepositoryPort,
            CommentRevisionRepositoryPort commentRevisionRepositoryPort,
            ReactionRepositoryPort reactionRepositoryPort,
            InteractionReportRepositoryPort reportRepositoryPort
    ) {
        this.commentRepositoryPort = Objects.requireNonNull(commentRepositoryPort, "CommentRepositoryPort cannot be null.");
        this.commentRevisionRepositoryPort = Objects.requireNonNull(commentRevisionRepositoryPort, "CommentRevisionRepositoryPort cannot be null.");
        this.reactionRepositoryPort = Objects.requireNonNull(reactionRepositoryPort, "ReactionRepositoryPort cannot be null.");
        this.reportRepositoryPort = Objects.requireNonNull(reportRepositoryPort, "InteractionReportRepositoryPort cannot be null.");
    }

    @Override
    public void cleanupCommunityPostInteractions(UUID postId, Instant deletedAt) {
        Objects.requireNonNull(postId, "Post ID cannot be null.");
        Instant timestamp = (deletedAt != null) ? deletedAt : Instant.now();

        Set<UUID> allEverPurgedCommentIds = new HashSet<>();

        // Execute bounded settlement passes
        for (int pass = 1; pass <= MAX_CLEANUP_PASSES; pass++) {
            // 1. Acquire Level 2 pessimistic write locks on all comments for this post
            List<Comment> lockedComments = commentRepositoryPort.lockAllCommentsByTarget(CommentTargetType.COMMUNITY_POST, postId);
            List<UUID> currentPassCommentIds = lockedComments.stream().map(Comment::getId).toList();
            allEverPurgedCommentIds.addAll(currentPassCommentIds);

            // 2. Stamp targetDeletedAt on reports (retaining evidence)
            if (!currentPassCommentIds.isEmpty()) {
                reportRepositoryPort.stampTargetDeletedAtForTargets(ReportTargetType.COMMENT, currentPassCommentIds, timestamp);
            }
            reportRepositoryPort.stampTargetDeletedAtForTargets(ReportTargetType.COMMUNITY_POST, List.of(postId), timestamp);

            // 3. Purge reactions
            if (!currentPassCommentIds.isEmpty()) {
                reactionRepositoryPort.deleteAllByTargetIds(ReactionTargetType.COMMENT, currentPassCommentIds);
            }
            reactionRepositoryPort.deleteAllByTargetIds(ReactionTargetType.COMMUNITY_POST, List.of(postId));

            // 4. Purge revisions and comments
            if (!currentPassCommentIds.isEmpty()) {
                commentRevisionRepositoryPort.deleteAllByCommentIds(currentPassCommentIds);
                commentRepositoryPort.deleteAllByIds(currentPassCommentIds);
            }

            // 5. Check if all residual invariants are satisfied
            if (verifyResiduals(postId, allEverPurgedCommentIds)) {
                return;
            }
        }

        // Final authoritative postcondition verification
        throw new IncompletePostInteractionCleanupException(
                postId,
                "Residual active interaction graph remains after " + MAX_CLEANUP_PASSES + " settlement passes."
        );
    }

    private boolean verifyResiduals(UUID postId, Set<UUID> allEverPurgedCommentIds) {
        // A. Verify 0 comments remain for post
        List<UUID> remainingCommentIds = commentRepositoryPort.findAllCommentIdsByTarget(CommentTargetType.COMMUNITY_POST, postId);
        if (!remainingCommentIds.isEmpty()) {
            return false;
        }

        // B. Verify 0 post reactions remain
        long remainingPostReactions = reactionRepositoryPort.countTotalReactionsByTarget(ReactionTarget.communityPost(postId));
        if (remainingPostReactions > 0) {
            return false;
        }

        // C. Verify 0 comment reactions remain for all purged comment IDs
        if (!allEverPurgedCommentIds.isEmpty()) {
            Map<UUID, Long> commentReactions = reactionRepositoryPort.countTotalReactionsByTargetIds(
                    ReactionTargetType.COMMENT,
                    allEverPurgedCommentIds
            );
            long totalCommentReactions = commentReactions.values().stream().mapToLong(Long::longValue).sum();
            if (totalCommentReactions > 0) {
                return false;
            }

            // D. Verify 0 revisions remain for all purged comment IDs
            long totalCommentRevisions = commentRevisionRepositoryPort.countByCommentIds(allEverPurgedCommentIds);
            if (totalCommentRevisions > 0) {
                return false;
            }

            // E. Verify all comment reports are stamped
            long unstampedCommentReports = reportRepositoryPort.countUnstampedReportsByTargets(
                    ReportTargetType.COMMENT,
                    allEverPurgedCommentIds
            );
            if (unstampedCommentReports > 0) {
                return false;
            }
        }

        // F. Verify post reports are stamped
        long unstampedPostReports = reportRepositoryPort.countUnstampedReportsByTargets(
                ReportTargetType.COMMUNITY_POST,
                List.of(postId)
        );
        return unstampedPostReports == 0;
    }
}
