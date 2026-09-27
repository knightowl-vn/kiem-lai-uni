package com.universe.interaction.application.query;

import com.universe.interaction.application.ports.UserAuthoredCommentsQueryPort;
import com.universe.interaction.contracts.dto.authored.AuthoredCommentPageDTO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Use case to retrieve paginated active comments authored by a specific user scoped by context filter.
 */
@Service
public class ListUserAuthoredCommentsUseCase {

    public static final int DEFAULT_PAGE_SIZE = 20;

    private final UserAuthoredCommentsQueryPort queryPort;

    public ListUserAuthoredCommentsUseCase(UserAuthoredCommentsQueryPort queryPort) {
        this.queryPort = Objects.requireNonNull(queryPort, "UserAuthoredCommentsQueryPort cannot be null.");
    }

    @Transactional(readOnly = true)
    public AuthoredCommentPageDTO execute(UUID authorUserId, UserCommentContextFilter filter, int page, int size) {
        Objects.requireNonNull(authorUserId, "authorUserId cannot be null.");
        UserCommentContextFilter effectiveFilter = filter != null ? filter : UserCommentContextFilter.ALL;
        int effectivePage = Math.max(0, page);
        int effectiveSize = size > 0 ? size : DEFAULT_PAGE_SIZE;

        return queryPort.findAuthoredComments(
                authorUserId,
                effectiveFilter.targetTypes(),
                effectivePage,
                effectiveSize
        );
    }
}
