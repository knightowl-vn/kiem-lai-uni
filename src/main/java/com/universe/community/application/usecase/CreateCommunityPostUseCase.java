package com.universe.community.application.usecase;

import com.universe.community.application.command.CreateCommunityPostCommand;
import com.universe.community.application.port.out.CommunityPostRepositoryPort;
import com.universe.community.application.port.out.CommunitySettingsRepositoryPort;
import com.universe.community.application.service.CommunityPostCreationGuardService;
import com.universe.community.domain.CommunityPost;
import com.universe.community.domain.CommunityPostStatus;
import com.universe.community.domain.CommunityPublicationMode;
import com.universe.community.domain.CommunitySettings;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Use case orchestrating the creation of a new Community Post respecting runtime publication settings
 * and rate-limiting anti-spam policies.
 */
@Service
public class CreateCommunityPostUseCase {

    private final CommunityPostRepositoryPort communityPostRepositoryPort;
    private final CommunitySettingsRepositoryPort settingsRepositoryPort;
    private final IdGeneratorPort idGeneratorPort;
    private final ClockPort clockPort;
    private final CommunityPostCreationGuardService creationGuardService;

    public CreateCommunityPostUseCase(
            CommunityPostRepositoryPort communityPostRepositoryPort,
            CommunitySettingsRepositoryPort settingsRepositoryPort,
            IdGeneratorPort idGeneratorPort,
            ClockPort clockPort,
            CommunityPostCreationGuardService creationGuardService
    ) {
        this.communityPostRepositoryPort = Objects.requireNonNull(communityPostRepositoryPort, "CommunityPostRepositoryPort cannot be null.");
        this.settingsRepositoryPort = Objects.requireNonNull(settingsRepositoryPort, "CommunitySettingsRepositoryPort cannot be null.");
        this.idGeneratorPort = Objects.requireNonNull(idGeneratorPort, "IdGeneratorPort cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
        this.creationGuardService = Objects.requireNonNull(creationGuardService, "CommunityPostCreationGuardService cannot be null.");
    }

    @Transactional
    public CommunityPost execute(CreateCommunityPostCommand command) {
        Objects.requireNonNull(command, "CreateCommunityPostCommand cannot be null.");

        // 1. Acquire per-author write lock
        creationGuardService.acquireAuthorLock(command.actorUserId());

        // 2. Read runtime settings
        CommunitySettings settings = settingsRepositoryPort.getSettings();
        if (settings == null || settings.getPublicationMode() == null) {
            throw new IllegalStateException("Community publication settings are not configured.");
        }
        CommunityPublicationMode mode = settings.getPublicationMode();

        // 3. Caption normalization & anti-spam policy check
        Instant now = clockPort.now();
        String normalizedCaption = CommunityPost.validateAndNormalizeCaption(command.caption());
        String captionHash = creationGuardService.evaluateEligibility(command.actorUserId(), normalizedCaption, now);

        // 4. Create CommunityPost aggregate
        UUID postId = idGeneratorPort.generate();
        CommunityPostStatus status;
        Instant publishedAt;
        Instant reviewRequestedAt;

        if (mode == CommunityPublicationMode.PRE_MODERATION) {
            status = CommunityPostStatus.PENDING_REVIEW;
            publishedAt = null;
            reviewRequestedAt = now;
        } else {
            status = CommunityPostStatus.PUBLISHED;
            publishedAt = now;
            reviewRequestedAt = null;
        }

        CommunityPost post = CommunityPost.create(
                postId,
                command.actorUserId(),
                normalizedCaption,
                command.imageMediaAssetId(),
                status,
                now,
                publishedAt,
                reviewRequestedAt
        );

        // 5. Persist post
        CommunityPost savedPost = communityPostRepositoryPort.save(post);

        // 6. Record immutable creation event
        UUID eventId = idGeneratorPort.generate();
        creationGuardService.recordCreationEvent(eventId, command.actorUserId(), savedPost.getId(), captionHash, now);

        return savedPost;
    }
}
