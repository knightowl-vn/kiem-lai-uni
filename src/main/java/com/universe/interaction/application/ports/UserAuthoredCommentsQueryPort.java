package com.universe.interaction.application.ports;

import com.universe.interaction.contracts.dto.authored.AuthoredCommentPageDTO;
import com.universe.interaction.domain.CommentTargetType;

import java.util.Set;
import java.util.UUID;

/**
 * Outbound query port for retrieving paginated active comments authored by a specific user.
 */
public interface UserAuthoredCommentsQueryPort {

    /**
     * Finds a deterministic page of active comments authored by the given user and matching target types.
     *
     * @param authorUserId unique author UUID
     * @param targetTypes  set of supported target types to include (e.g. NOVEL_CHAPTER, WIKI_ARTICLE)
     * @param page         zero-based page index
     * @param size         page size
     * @return {@link AuthoredCommentPageDTO}
     */
    AuthoredCommentPageDTO findAuthoredComments(
            UUID authorUserId,
            Set<CommentTargetType> targetTypes,
            int page,
            int size
    );
}
