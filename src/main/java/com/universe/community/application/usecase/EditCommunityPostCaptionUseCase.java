package com.universe.community.application.usecase;

import com.universe.community.application.command.EditCommunityPostCaptionCommand;
import com.universe.community.application.port.out.CommunityPostRepositoryPort;
import com.universe.community.application.port.out.CommunityPostRevisionRepositoryPort;
import com.universe.community.application.port.out.CommunitySettingsRepositoryPort;
import com.universe.community.domain.CommunityPost;
import com.universe.community.domain.CommunityPostRevision;
import com.universe.community.domain.CommunityPublicationMode;
import com.universe.community.domain.CommunitySettings;
import com.universe.community.domain.exception.CommunityPostNotFoundException;
import com.universe.community.domain.exception.CommunityPostUnauthorizedException;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Use case orchestrating caption editing on a Community Post and archiving prior captions into immutable revisions.
 * Respects runtime publication settings: under PRE_MODERATION, an effective edit routes the post to PENDING_REVIEW
 * while preserving its original publishedAt timestamp.
 */
@Service
public class EditCommunityPostCaptionUseCase {

    private final CommunityPostRepositoryPort communityPostRepositoryPort;
    private final CommunityPostRevisionRepositoryPort communityPostRevisionRepositoryPort;
    private final CommunitySettingsRepositoryPort settingsRepositoryPort;
    private final IdGeneratorPort idGeneratorPort;
    private final ClockPort clockPort;

    public EditCommunityPostCaptionUseCase(
            CommunityPostRepositoryPort communityPostRepositoryPort,
            CommunityPostRevisionRepositoryPort communityPostRevisionRepositoryPort,
            CommunitySettingsRepositoryPort settingsRepositoryPort,
            IdGeneratorPort idGeneratorPort,
            ClockPort clockPort
    ) {
        this.communityPostRepositoryPort = Objects.requireNonNull(communityPostRepositoryPort, "CommunityPostRepositoryPort cannot be null.");
        this.communityPostRevisionRepositoryPort = Objects.requireNonNull(communityPostRevisionRepositoryPort, "CommunityPostRevisionRepositoryPort cannot be null.");
        this.settingsRepositoryPort = Objects.requireNonNull(settingsRepositoryPort, "CommunitySettingsRepositoryPort cannot be null.");
        this.idGeneratorPort = Objects.requireNonNull(idGeneratorPort, "IdGeneratorPort cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
    }

    @Transactional
    public CommunityPost execute(EditCommunityPostCaptionCommand command) {
        Objects.requireNonNull(command, "EditCommunityPostCaptionCommand cannot be null.");

        // 1. Load current post with exclusive pessimistic row lock
        CommunityPost post = communityPostRepositoryPort.findByIdForUpdate(command.postId())
                .orElseThrow(() -> new CommunityPostNotFoundException(command.postId()));

        // 2. Author ownership check
        if (!post.getAuthorUserId().equals(command.actorUserId())) {
            throw new CommunityPostUnauthorizedException(command.actorUserId(), command.postId());
        }

        // 3. Snapshot previous caption, fetch current runtime publication mode, and capture current time
        String previousCaption = post.getCaption();
        Instant now = clockPort.now();

        CommunitySettings settings = settingsRepositoryPort.getSettings();
        if (settings == null || settings.getPublicationMode() == null) {
            throw new IllegalStateException("Community publication settings are not configured.");
        }
        CommunityPublicationMode mode = settings.getPublicationMode();

        // 4. Mutate post caption in domain (handles validation, trimming, no-op detection, and PRE_MODERATION re-review transition)
        boolean changed = post.editCaption(command.actorUserId(), command.newCaption(), now, mode);
        if (!changed) {
            return post;
        }

        // 5. Persist updated post first
        CommunityPost savedPost = communityPostRepositoryPort.save(post);

        // 6. Only archive historical revision under AUTO_PUBLISH. Under PRE_MODERATION, revision is deferred until approval.
        if (mode == CommunityPublicationMode.AUTO_PUBLISH) {
            UUID revisionId = idGeneratorPort.generate();
            int revisionNumber = post.getContentVersion();

            CommunityPostRevision revision = new CommunityPostRevision(
                    revisionId,
                    post.getId(),
                    revisionNumber,
                    command.actorUserId(),
                    previousCaption,
                    post.getCaption(),
                    now
            );
            communityPostRevisionRepositoryPort.save(revision);
        }

        return savedPost;
    }
}
