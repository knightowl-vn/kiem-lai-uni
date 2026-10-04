package com.universe.community.application.usecase;

import com.universe.community.application.command.CreateCommunityPostCommand;
import com.universe.community.application.port.out.CommunityPostRepositoryPort;
import com.universe.community.application.port.out.CommunitySettingsRepositoryPort;
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
 * Use case orchestrating the creation of a new Community Post respecting runtime publication settings.
 */
@Service
public class CreateCommunityPostUseCase {

    private final CommunityPostRepositoryPort communityPostRepositoryPort;
    private final CommunitySettingsRepositoryPort settingsRepositoryPort;
    private final IdGeneratorPort idGeneratorPort;
    private final ClockPort clockPort;

    public CreateCommunityPostUseCase(
            CommunityPostRepositoryPort communityPostRepositoryPort,
            CommunitySettingsRepositoryPort settingsRepositoryPort,
            IdGeneratorPort idGeneratorPort,
            ClockPort clockPort
    ) {
        this.communityPostRepositoryPort = Objects.requireNonNull(communityPostRepositoryPort, "CommunityPostRepositoryPort cannot be null.");
        this.settingsRepositoryPort = Objects.requireNonNull(settingsRepositoryPort, "CommunitySettingsRepositoryPort cannot be null.");
        this.idGeneratorPort = Objects.requireNonNull(idGeneratorPort, "IdGeneratorPort cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
    }

    @Transactional
    public CommunityPost execute(CreateCommunityPostCommand command) {
        Objects.requireNonNull(command, "CreateCommunityPostCommand cannot be null.");

        UUID postId = idGeneratorPort.generate();
        Instant now = clockPort.now();

        CommunitySettings settings = settingsRepositoryPort.getSettings();
        if (settings == null || settings.getPublicationMode() == null) {
            throw new IllegalStateException("Community publication settings are not configured.");
        }
        CommunityPublicationMode mode = settings.getPublicationMode();

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
                command.caption(),
                command.imageMediaAssetId(),
                status,
                now,
                publishedAt,
                reviewRequestedAt
        );

        return communityPostRepositoryPort.save(post);
    }
}
