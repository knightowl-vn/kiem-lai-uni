package com.universe.community.application.usecase;

import com.universe.community.application.command.CreateCommunityPostCommand;
import com.universe.community.application.port.out.CommunityPostRepositoryPort;
import com.universe.community.domain.CommunityPost;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Use case orchestrating the creation of a new Community Post.
 */
@Service
public class CreateCommunityPostUseCase {

    private final CommunityPostRepositoryPort communityPostRepositoryPort;
    private final IdGeneratorPort idGeneratorPort;
    private final ClockPort clockPort;

    public CreateCommunityPostUseCase(
            CommunityPostRepositoryPort communityPostRepositoryPort,
            IdGeneratorPort idGeneratorPort,
            ClockPort clockPort
    ) {
        this.communityPostRepositoryPort = Objects.requireNonNull(communityPostRepositoryPort, "CommunityPostRepositoryPort cannot be null.");
        this.idGeneratorPort = Objects.requireNonNull(idGeneratorPort, "IdGeneratorPort cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
    }

    @Transactional
    public CommunityPost execute(CreateCommunityPostCommand command) {
        Objects.requireNonNull(command, "CreateCommunityPostCommand cannot be null.");

        UUID postId = idGeneratorPort.generate();
        Instant createdAt = clockPort.now();

        CommunityPost post = CommunityPost.create(
                postId,
                command.actorUserId(),
                command.caption(),
                command.imageMediaAssetId(),
                createdAt
        );

        return communityPostRepositoryPort.save(post);
    }
}
