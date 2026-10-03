package com.universe.community.application.usecase;

import com.universe.community.application.port.out.CommunityPostInteractionCleanupPort;
import com.universe.community.application.port.out.CommunityPostReportQueryPort;
import com.universe.community.application.port.out.CommunityPostRepositoryPort;
import com.universe.community.domain.CommunityPost;
import com.universe.community.domain.exception.CommunityPostNotFoundException;
import com.universe.community.domain.exception.CommunityPostPendingReportConflictException;
import com.universe.community.domain.exception.CommunityPostUnauthorizedException;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Use case orchestrating owner-initiated hard-delete of a Community Post,
 * coordinating Interaction graph cleanup, moderation report evidence retention,
 * and Media lifecycle.
 */
@Service
public class DeleteCommunityPostUseCase {

    private final CommunityPostRepositoryPort communityPostRepositoryPort;
    private final CommunityPostInteractionCleanupPort interactionCleanupPort;
    private final CommunityPostReportQueryPort reportQueryPort;
    private final MediaContract mediaContract;
    private final ClockPort clockPort;

    public DeleteCommunityPostUseCase(
            CommunityPostRepositoryPort communityPostRepositoryPort,
            CommunityPostInteractionCleanupPort interactionCleanupPort,
            CommunityPostReportQueryPort reportQueryPort,
            MediaContract mediaContract,
            ClockPort clockPort
    ) {
        this.communityPostRepositoryPort = Objects.requireNonNull(
                communityPostRepositoryPort,
                "CommunityPostRepositoryPort cannot be null."
        );
        this.interactionCleanupPort = Objects.requireNonNull(
                interactionCleanupPort,
                "CommunityPostInteractionCleanupPort cannot be null."
        );
        this.reportQueryPort = Objects.requireNonNull(
                reportQueryPort,
                "CommunityPostReportQueryPort cannot be null."
        );
        this.mediaContract = Objects.requireNonNull(
                mediaContract,
                "MediaContract cannot be null."
        );
        this.clockPort = Objects.requireNonNull(
                clockPort,
                "ClockPort cannot be null."
        );
    }

    /**
     * Executes owner hard-delete of a community post under a unified transaction boundary.
     *
     * @param actorUserId ID of the user requesting deletion
     * @param postId ID of the post to delete
     */
    @Transactional
    public void execute(UUID actorUserId, UUID postId) {
        Objects.requireNonNull(actorUserId, "Actor user ID cannot be null.");
        Objects.requireNonNull(postId, "Post ID cannot be null.");

        // 1. Load + pessimistically lock Community post (PESSIMISTIC_WRITE)
        CommunityPost post = communityPostRepositoryPort.findByIdForUpdate(postId)
                .orElseThrow(() -> new CommunityPostNotFoundException(postId));

        // 2. Author ownership check
        if (!post.getAuthorUserId().equals(actorUserId)) {
            throw new CommunityPostUnauthorizedException(actorUserId, postId);
        }

        // 3. Anti-evasion barrier: Prevent deletion if pending abuse reports exist
        if (reportQueryPort.hasPendingReports(postId)) {
            throw new CommunityPostPendingReportConflictException(postId);
        }

        Instant now = clockPort.now();

        // 4. Under the same deletion barrier / post lock: invoke Interaction cleanup
        interactionCleanupPort.cleanupCommunityPostInteractions(postId, now);

        // 5. If imageMediaAssetId != null: transition Media metadata to DELETED
        if (post.getImageMediaAssetId() != null) {
            mediaContract.delete(post.getImageMediaAssetId());
        }

        // 6. Delete Community post (foreign key CASCADE deletes revisions)
        communityPostRepositoryPort.deleteById(postId);
    }
}
