package com.universe.community.application.usecase;

import com.universe.community.application.command.ApproveCommunityPostCommand;
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
 * Use case orchestrating admin approval of a pending Community Post.
 */
@Service
public class ApproveCommunityPostUseCase {

    private final CommunityPostRepositoryPort postRepositoryPort;
    private final CommunityPostModerationEventRepositoryPort moderationEventRepositoryPort;
    private final ClockPort clockPort;

    public ApproveCommunityPostUseCase(
            CommunityPostRepositoryPort postRepositoryPort,
            CommunityPostModerationEventRepositoryPort moderationEventRepositoryPort,
            ClockPort clockPort
    ) {
        this.postRepositoryPort = Objects.requireNonNull(postRepositoryPort, "postRepositoryPort cannot be null.");
        this.moderationEventRepositoryPort = Objects.requireNonNull(moderationEventRepositoryPort, "moderationEventRepositoryPort cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "clockPort cannot be null.");
    }

    @Transactional
    public void execute(ApproveCommunityPostCommand command) {
        Objects.requireNonNull(command, "ApproveCommunityPostCommand cannot be null.");

        CommunityPost post = postRepositoryPort.findByIdForUpdate(command.postId())
                .orElseThrow(() -> new CommunityPostNotFoundException(command.postId()));

        if (post.getStatus() != CommunityPostStatus.PENDING_REVIEW) {
            throw new IllegalStateException("Cannot approve post with status: " + post.getStatus());
        }

        Instant now = clockPort.now();
        post.approve(now);
        postRepositoryPort.save(post);

        CommunityPostModerationEvent event = new CommunityPostModerationEvent(
                UUID.randomUUID(),
                command.postId(),
                CommunityPostModerationAction.APPROVE,
                CommunityPostStatus.PENDING_REVIEW,
                CommunityPostStatus.PUBLISHED,
                command.moderatorUserId(),
                command.reason(),
                now
        );
        moderationEventRepositoryPort.save(event);
    }
}
