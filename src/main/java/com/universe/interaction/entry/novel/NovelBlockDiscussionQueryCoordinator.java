package com.universe.interaction.entry.novel;

import com.universe.interaction.application.query.CommentThreadView;
import com.universe.interaction.application.query.GetCommentThreadsByRootIdsUseCase;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.entry.dto.ChapterBlockDiscussionResponseDTO;
import com.universe.interaction.entry.dto.CommentThreadResponseDTO;
import com.universe.novel.application.anchor.ChapterBlockDiscussionAnchorView;
import com.universe.novel.application.anchor.GetChapterBlockDiscussionAnchorsUseCase;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Cross-context composition coordinator for reading Novel block discussions.
 *
 * <p>Preserves bounded-context boundaries:
 * <ul>
 *   <li>Novel application has ZERO Interaction imports;</li>
 *   <li>Interaction application has ZERO Novel imports;</li>
 *   <li>Composition occurs cleanly at this entry layer;</li>
 *   <li>1 bulk Novel anchor resolution query + 1 bulk Interaction thread read (no N+1 loop);</li>
 *   <li>Read-only operation without write transactional overhead.</li>
 * </ul>
 */
@Service
public class NovelBlockDiscussionQueryCoordinator {

    private final GetChapterBlockDiscussionAnchorsUseCase getChapterBlockDiscussionAnchorsUseCase;
    private final GetCommentThreadsByRootIdsUseCase getCommentThreadsByRootIdsUseCase;

    public NovelBlockDiscussionQueryCoordinator(
            GetChapterBlockDiscussionAnchorsUseCase getChapterBlockDiscussionAnchorsUseCase,
            GetCommentThreadsByRootIdsUseCase getCommentThreadsByRootIdsUseCase
    ) {
        this.getChapterBlockDiscussionAnchorsUseCase = Objects.requireNonNull(
                getChapterBlockDiscussionAnchorsUseCase, "GetChapterBlockDiscussionAnchorsUseCase cannot be null"
        );
        this.getCommentThreadsByRootIdsUseCase = Objects.requireNonNull(
                getCommentThreadsByRootIdsUseCase, "GetCommentThreadsByRootIdsUseCase cannot be null"
        );
    }

    /**
     * Resolves the block discussion response for a chapter block by composing Novel block anchor
     * resolution with Interaction bulk visible thread retrieval.
     *
     * @param chapterId scalar UUID of the chapter
     * @param blockKey canonical Reader block key
     * @return immutable {@link ChapterBlockDiscussionResponseDTO}
     */
    public ChapterBlockDiscussionResponseDTO getBlockDiscussion(UUID chapterId, String blockKey) {
        if (chapterId == null) {
            throw new IllegalArgumentException("chapterId cannot be null");
        }
        if (blockKey == null || blockKey.trim().isEmpty()) {
            throw new IllegalArgumentException("blockKey cannot be blank");
        }

        // 1. Novel application query: loads current chapter snapshot, validates requested blockKey,
        // and resolves eligible chapter anchors to root comment IDs.
        ChapterBlockDiscussionAnchorView anchorView = getChapterBlockDiscussionAnchorsUseCase.execute(chapterId, blockKey);

        // 2. If no anchors resolved to this block, return empty threads with canonical block metadata immediately
        if (anchorView.rootCommentIds().isEmpty()) {
            return new ChapterBlockDiscussionResponseDTO(
                    anchorView.chapterId(),
                    anchorView.contentVersion(),
                    anchorView.blockKey(),
                    anchorView.canonicalText(),
                    0,
                    List.of()
            );
        }

        // 3. Interaction bulk visible thread read: loads visible root threads and their replies in bulk (no N+1 loop)
        CommentTarget target = CommentTarget.novelChapter(chapterId);
        List<CommentThreadView> threadViews = getCommentThreadsByRootIdsUseCase.execute(target, anchorView.rootCommentIds());

        List<CommentThreadResponseDTO> threads = threadViews.stream()
                .map(CommentThreadResponseDTO::from)
                .toList();

        return new ChapterBlockDiscussionResponseDTO(
                anchorView.chapterId(),
                anchorView.contentVersion(),
                anchorView.blockKey(),
                anchorView.canonicalText(),
                threads.size(),
                threads
        );
    }
}
