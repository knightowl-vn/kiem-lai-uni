package com.universe.interaction.application.mutation;

import com.universe.community.contracts.port.CommunityPostInteractionMutationPort;
import com.universe.interaction.application.exceptions.CommentTargetNotEligibleException;
import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.application.ports.CommentTargetEligibilityPort;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentTargetType;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Use case to orchestrate root comment creation on an eligible target.
 */
@Service
@Transactional
public class CreateRootCommentUseCase {

    private final CommentRepositoryPort commentRepositoryPort;
    private final CommentTargetEligibilityPort eligibilityPort;
    private final CommunityPostInteractionMutationPort communityPostMutationPort;
    private final IdGeneratorPort idGeneratorPort;
    private final ClockPort clockPort;

    public CreateRootCommentUseCase(
            CommentRepositoryPort commentRepositoryPort,
            CommentTargetEligibilityPort eligibilityPort,
            CommunityPostInteractionMutationPort communityPostMutationPort,
            IdGeneratorPort idGeneratorPort,
            ClockPort clockPort
    ) {
        this.commentRepositoryPort = Objects.requireNonNull(commentRepositoryPort, "CommentRepositoryPort cannot be null.");
        this.eligibilityPort = Objects.requireNonNull(eligibilityPort, "CommentTargetEligibilityPort cannot be null.");
        this.communityPostMutationPort = Objects.requireNonNull(communityPostMutationPort, "CommunityPostInteractionMutationPort cannot be null.");
        this.idGeneratorPort = Objects.requireNonNull(idGeneratorPort, "IdGeneratorPort cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
    }

    public Comment execute(CreateRootCommentCommand command) {
        Objects.requireNonNull(command, "CreateRootCommentCommand cannot be null.");

        // Authoritative mutation barrier:
        // For COMMUNITY_POST targets, acquire the Community post's PESSIMISTIC_WRITE lock inside this transaction
        if (command.target().type() == CommentTargetType.COMMUNITY_POST) {
            communityPostMutationPort.lockExistingPostForInteraction(command.target().targetId())
                    .orElseThrow(() -> new CommentTargetNotEligibleException(command.target()));
        } else if (!eligibilityPort.isEligible(command.target())) {
            throw new CommentTargetNotEligibleException(command.target());
        }

        UUID commentId = idGeneratorPort.generate();
        Instant createdAt = clockPort.now();

        Comment root = Comment.createRoot(
                commentId,
                command.target(),
                command.actorUserId(),
                command.body(),
                createdAt
        );

        return commentRepositoryPort.save(root);
    }
}
