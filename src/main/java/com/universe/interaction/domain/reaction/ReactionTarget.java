package com.universe.interaction.domain.reaction;

import java.util.Objects;
import java.util.UUID;

/**
 * Value object representing the target of an emotional reaction.
 *
 * <p>Uses scalar UUID to preserve Clean Architecture boundaries across modules.
 */
public record ReactionTarget(
        ReactionTargetType type,
        UUID targetId
) {
    public ReactionTarget {
        Objects.requireNonNull(type, "ReactionTargetType cannot be null.");
        Objects.requireNonNull(targetId, "Target ID cannot be null.");
    }

    public static ReactionTarget novelChapter(UUID chapterId) {
        return new ReactionTarget(ReactionTargetType.NOVEL_CHAPTER, chapterId);
    }

    public static ReactionTarget comment(UUID commentId) {
        return new ReactionTarget(ReactionTargetType.COMMENT, commentId);
    }

    public static ReactionTarget donghuaEpisode(UUID episodeId) {
        return new ReactionTarget(ReactionTargetType.DONGHUA_EPISODE, episodeId);
    }
}
