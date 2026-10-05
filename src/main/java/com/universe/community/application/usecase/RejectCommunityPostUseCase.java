package com.universe.community.application.usecase;

import com.universe.community.application.command.RejectCommunityPostCommand;
import com.universe.community.application.port.out.CommunityPostModerationEventRepositoryPort;
import com.universe.community.application.port.out.CommunityPostRepositoryPort;
import com.universe.community.domain.CommunityPost;
import com.universe.community.domain.CommunityPostStatus;
import com.universe.community.domain.exception.CommunityPostNotFoundException;
import com.universe.community.domain.moderation.CommunityPostModerationAction;
import com.universe.community.domain.moderation.CommunityPostModerationEvent;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Use case orchestrating admin rejection of a pending Community Post or pending caption edit.
 */
@Service
public class RejectCommunityPostUseCase {

    private final CommunityPostRepositoryPort postRepositoryPort;
    private final CommunityPostModerationEventRepositoryPort moderationEventRepositoryPort;
    private final ClockPort clockPort;

    public RejectCommunityPostUseCase(
            CommunityPostRepositoryPort postRepositoryPort,
            CommunityPostModerationEventRepositoryPort moderationEventRepositoryPort,
            ClockPort clockPort
    ) {
        this.postRepositoryPort = Objects.requireNonNull(postRepositoryPort, "postRepositoryPort cannot be null.");
        this.moderationEventRepositoryPort = Objects.requireNonNull(moderationEventRepositoryPort, "moderationEventRepositoryPort cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "clockPort cannot be null.");
    }

    @Transactional
    public void execute(RejectCommunityPostCommand command) {
        Objects.requireNonNull(command, "RejectCommunityPostCommand cannot be null.");

        CommunityPost post = postRepositoryPort.findByIdForUpdate(command.postId())
                .orElseThrow(() -> new CommunityPostNotFoundException(command.postId()));

        boolean isPendingEdit = post.isPendingCaptionEdit();
        if (post.getStatus() != CommunityPostStatus.PENDING_REVIEW && !isPendingEdit) {
            throw new IllegalStateException("Cannot reject post with status: " + post.getStatus());
        }

        Instant now = clockPort.now();
        post.reject(now);
        postRepositoryPort.save(post);

        CommunityPostStatus fromStatus = isPendingEdit ? CommunityPostStatus.PUBLISHED : CommunityPostStatus.PENDING_REVIEW;
        CommunityPostStatus toStatus = isPendingEdit ? CommunityPostStatus.PUBLISHED : CommunityPostStatus.REJECTED;

        CommunityPostModerationEvent event = new CommunityPostModerationEvent(
                UUID.randomUUID(),
                command.postId(),
                CommunityPostModerationAction.REJECT,
                fromStatus,
                toStatus,
                command.moderatorUserId(),
                command.reason(),
                now
        );
        moderationEventRepositoryPort.save(event);
    }
}
