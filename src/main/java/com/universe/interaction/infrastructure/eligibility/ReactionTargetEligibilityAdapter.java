package com.universe.interaction.infrastructure.eligibility;

import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.application.ports.ReactionTargetEligibilityPort;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.reaction.ReactionTarget;
import com.universe.novel.application.ports.ReaderChapterAccessQueryPort;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/**
 * Infrastructure adapter implementing {@link ReactionTargetEligibilityPort}.
 *
 * <p>Dispatches eligibility evaluation to the owning bounded contexts:
 * <ul>
 *   <li>{@code NOVEL_CHAPTER}: delegates to {@link ReaderChapterAccessQueryPort} to ensure chapter is published and accessible.</li>
 *   <li>{@code COMMENT}: inspects {@link CommentRepositoryPort} to ensure comment exists and is {@code ACTIVE}.</li>
 *   <li>{@code DONGHUA_EPISODE}: fails closed (returns {@code false}) until a complete Donghua querying contract exists.</li>
 * </ul>
 */
@Component
public class ReactionTargetEligibilityAdapter implements ReactionTargetEligibilityPort {

    private final ReaderChapterAccessQueryPort readerChapterAccessQueryPort;
    private final CommentRepositoryPort commentRepositoryPort;

    public ReactionTargetEligibilityAdapter(
            ReaderChapterAccessQueryPort readerChapterAccessQueryPort,
            CommentRepositoryPort commentRepositoryPort
    ) {
        this.readerChapterAccessQueryPort = Objects.requireNonNull(
                readerChapterAccessQueryPort,
                "ReaderChapterAccessQueryPort cannot be null."
        );
        this.commentRepositoryPort = Objects.requireNonNull(
                commentRepositoryPort,
                "CommentRepositoryPort cannot be null."
        );
    }

    @Override
    public boolean isEligible(ReactionTarget target) {
        if (target == null) {
            return false;
        }

        return switch (target.type()) {
            case NOVEL_CHAPTER -> isNovelChapterEligible(target.targetId());
            case COMMENT -> isCommentEligible(target.targetId());
            case DONGHUA_EPISODE -> false; // Fail closed until real Donghua owning contract exists
        };
    }

    private boolean isNovelChapterEligible(UUID chapterId) {
        if (chapterId == null) {
            return false;
        }
        return readerChapterAccessQueryPort.findPublishedById(chapterId).isPresent();
    }

    private boolean isCommentEligible(UUID commentId) {
        if (commentId == null) {
            return false;
        }
        return commentRepositoryPort.findById(commentId)
                .map(Comment::isActive)
                .orElse(false);
    }
}
