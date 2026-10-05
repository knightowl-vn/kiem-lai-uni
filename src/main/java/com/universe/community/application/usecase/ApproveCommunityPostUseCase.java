package com.universe.community.application.usecase;

import com.universe.community.application.command.ApproveCommunityPostCommand;
import com.universe.community.application.port.out.CommunityPostModerationEventRepositoryPort;
import com.universe.community.application.port.out.CommunityPostRepositoryPort;
import com.universe.community.application.port.out.CommunityPostRevisionRepositoryPort;
import com.universe.community.domain.CommunityPost;
import com.universe.community.domain.CommunityPostRevision;
import com.universe.community.domain.CommunityPostStatus;
import com.universe.community.domain.exception.CommunityPostNotFoundException;
import com.universe.community.domain.moderation.CommunityPostModerationAction;
import com.universe.community.domain.moderation.CommunityPostModerationEvent;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Use case orchestrating admin approval of a pending Community Post or pending caption edit.
 */
@Service
public class ApproveCommunityPostUseCase {

    private final CommunityPostRepositoryPort postRepositoryPort;
    private final CommunityPostModerationEventRepositoryPort moderationEventRepositoryPort;
    private final CommunityPostRevisionRepositoryPort revisionRepositoryPort;
    private final IdGeneratorPort idGeneratorPort;
    private final ClockPort clockPort;

    @Autowired
    public ApproveCommunityPostUseCase(
            CommunityPostRepositoryPort postRepositoryPort,
            CommunityPostModerationEventRepositoryPort moderationEventRepositoryPort,
            CommunityPostRevisionRepositoryPort revisionRepositoryPort,
            IdGeneratorPort idGeneratorPort,
            ClockPort clockPort
    ) {
        this.postRepositoryPort = Objects.requireNonNull(postRepositoryPort, "postRepositoryPort cannot be null.");
        this.moderationEventRepositoryPort = Objects.requireNonNull(moderationEventRepositoryPort, "moderationEventRepositoryPort cannot be null.");
        this.revisionRepositoryPort = revisionRepositoryPort;
        this.idGeneratorPort = idGeneratorPort;
        this.clockPort = Objects.requireNonNull(clockPort, "clockPort cannot be null.");
    }

    public ApproveCommunityPostUseCase(
            CommunityPostRepositoryPort postRepositoryPort,
            CommunityPostModerationEventRepositoryPort moderationEventRepositoryPort,
            ClockPort clockPort
    ) {
        this(postRepositoryPort, moderationEventRepositoryPort, null, UUID::randomUUID, clockPort);
    }

    @Transactional
    public void execute(ApproveCommunityPostCommand command) {
        Objects.requireNonNull(command, "ApproveCommunityPostCommand cannot be null.");

        CommunityPost post = postRepositoryPort.findByIdForUpdate(command.postId())
                .orElseThrow(() -> new CommunityPostNotFoundException(command.postId()));

        boolean isPendingEdit = post.isPendingCaptionEdit();
        if (post.getStatus() != CommunityPostStatus.PENDING_REVIEW && !isPendingEdit) {
            throw new IllegalStateException("Cannot approve post with status: " + post.getStatus());
        }

        Instant now = clockPort.now();
        String previousCaption = post.getCaption();
        String approvedCaption = isPendingEdit ? post.getPendingCaption() : null;

        post.approve(now);
        postRepositoryPort.save(post);

        if (isPendingEdit && revisionRepositoryPort != null) {
            UUID generatedRevisionId = idGeneratorPort != null ? idGeneratorPort.generate() : null;
            UUID revisionId = generatedRevisionId != null ? generatedRevisionId : UUID.randomUUID();
            int revisionNumber = post.getContentVersion();
            CommunityPostRevision revision = new CommunityPostRevision(
                    revisionId,
                    post.getId(),
                    revisionNumber,
                    post.getAuthorUserId(),
                    previousCaption,
                    approvedCaption,
                    now
            );
            revisionRepositoryPort.save(revision);
        }

        CommunityPostStatus fromStatus = isPendingEdit ? CommunityPostStatus.PUBLISHED : CommunityPostStatus.PENDING_REVIEW;
        CommunityPostModerationEvent event = new CommunityPostModerationEvent(
                UUID.randomUUID(),
                command.postId(),
                CommunityPostModerationAction.APPROVE,
                fromStatus,
                CommunityPostStatus.PUBLISHED,
                command.moderatorUserId(),
                command.reason(),
                now
        );
        moderationEventRepositoryPort.save(event);
    }
}
