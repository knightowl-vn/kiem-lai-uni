package com.universe.interaction.entry.novel;

import com.universe.interaction.application.mutation.CreateRootCommentCommand;
import com.universe.interaction.application.mutation.CreateRootCommentUseCase;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentTarget;
import com.universe.novel.application.anchor.CreateChapterCommentTextAnchorCommand;
import com.universe.novel.application.anchor.CreateChapterCommentTextAnchorUseCase;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Cross-context composition coordinator for atomic Novel inline comment and anchor creation.
 *
 * <p>Preserves bounded-context separation:
 * <ul>
 *   <li>Novel application has ZERO Interaction imports;</li>
 *   <li>Interaction application has ZERO Novel imports;</li>
 *   <li>Composition occurs exclusively at this entry/composition layer;</li>
 *   <li>The outer transaction encompasses both the Interaction root comment creation and the
 *       Novel anchor persistence. If anchor validation or persistence fails, the transaction rolls back
 *       the root comment automatically.</li>
 * </ul>
 */
@Service
public class NovelInlineCommentCreationCoordinator {

    private final CreateRootCommentUseCase createRootCommentUseCase;
    private final CreateChapterCommentTextAnchorUseCase createChapterCommentTextAnchorUseCase;

    public NovelInlineCommentCreationCoordinator(
            CreateRootCommentUseCase createRootCommentUseCase,
            CreateChapterCommentTextAnchorUseCase createChapterCommentTextAnchorUseCase
    ) {
        this.createRootCommentUseCase = Objects.requireNonNull(createRootCommentUseCase, "CreateRootCommentUseCase cannot be null");
        this.createChapterCommentTextAnchorUseCase = Objects.requireNonNull(createChapterCommentTextAnchorUseCase, "CreateChapterCommentTextAnchorUseCase cannot be null");
    }

    @Transactional
    public UUID createInlineComment(
            UUID actorUserId,
            UUID chapterId,
            String body,
            long contentVersion,
            String blockKey,
            int startOffset,
            int endOffset
    ) {
        Objects.requireNonNull(actorUserId, "actorUserId cannot be null");
        Objects.requireNonNull(chapterId, "chapterId cannot be null");
        Objects.requireNonNull(body, "body cannot be null");

        // 1. Create root comment targeting the novel chapter
        CommentTarget target = CommentTarget.novelChapter(chapterId);
        CreateRootCommentCommand rootCommand = new CreateRootCommentCommand(actorUserId, target, body);
        Comment createdRoot = createRootCommentUseCase.execute(rootCommand);
        UUID rootCommentId = createdRoot.getId();

        // 2. Create and persist Novel text-anchor using the newly created root comment ID
        CreateChapterCommentTextAnchorCommand anchorCommand = new CreateChapterCommentTextAnchorCommand(
                rootCommentId,
                chapterId,
                contentVersion,
                blockKey,
                startOffset,
                endOffset
        );
        createChapterCommentTextAnchorUseCase.execute(anchorCommand);

        return rootCommentId;
    }
}
