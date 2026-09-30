package com.universe.interaction.application.query;

import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.application.ports.CommunityPostEngagementQueryPort;
import com.universe.interaction.application.ports.ReactionRepositoryPort;
import com.universe.interaction.domain.CommentTargetType;
import com.universe.interaction.domain.reaction.ReactionTargetType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Service orchestrating batch retrieval of engagement metrics (reactions and comments) for Community posts.
 */
@Service
@Transactional(readOnly = true)
public class GetCommunityPostEngagementCountsUseCase implements CommunityPostEngagementQueryPort {

    private final ReactionRepositoryPort reactionRepositoryPort;
    private final CommentRepositoryPort commentRepositoryPort;

    public GetCommunityPostEngagementCountsUseCase(
            ReactionRepositoryPort reactionRepositoryPort,
            CommentRepositoryPort commentRepositoryPort
    ) {
        this.reactionRepositoryPort = Objects.requireNonNull(reactionRepositoryPort, "ReactionRepositoryPort cannot be null.");
        this.commentRepositoryPort = Objects.requireNonNull(commentRepositoryPort, "CommentRepositoryPort cannot be null.");
    }

    @Override
    public Map<UUID, CommunityPostEngagementCountsDTO> getEngagementCountsForPosts(Collection<UUID> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, Long> reactionCounts = reactionRepositoryPort.countTotalReactionsByTargetIds(
                ReactionTargetType.COMMUNITY_POST,
                postIds
        );
        Map<UUID, Long> commentCounts = commentRepositoryPort.countActiveCommentsByTargetIds(
                CommentTargetType.COMMUNITY_POST,
                postIds
        );

        Map<UUID, CommunityPostEngagementCountsDTO> result = new HashMap<>();
        for (UUID postId : postIds) {
            if (postId != null) {
                long reactions = reactionCounts.getOrDefault(postId, 0L);
                long comments = commentCounts.getOrDefault(postId, 0L);
                result.put(postId, new CommunityPostEngagementCountsDTO(postId, reactions, comments));
            }
        }

        return result;
    }
}
