package com.universe.community.application.usecase;

import com.universe.community.contracts.dto.CommunityPostRevisionPublicDTO;
import com.universe.community.contracts.port.CommunityPostQueryPort;
import com.universe.community.domain.exception.CommunityPostNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Use case to retrieve the public revision history for an existing Community Post.
 */
@Service
@Transactional(readOnly = true)
public class GetCommunityPostRevisionsUseCase {

    private final CommunityPostQueryPort communityPostQueryPort;

    public GetCommunityPostRevisionsUseCase(CommunityPostQueryPort communityPostQueryPort) {
        this.communityPostQueryPort = Objects.requireNonNull(
                communityPostQueryPort,
                "CommunityPostQueryPort cannot be null."
        );
    }

    /**
     * Retrieves the public edit revision history for the specified post ID, ordered newest-first.
     *
     * @param postId the post UUID
     * @return list of revisions ordered by revisionNumber DESC (empty if unedited)
     * @throws CommunityPostNotFoundException if the post does not exist or has been deleted
     */
    public List<CommunityPostRevisionPublicDTO> execute(UUID postId) {
        Objects.requireNonNull(postId, "Post ID cannot be null.");

        // Verify post existence via public read projection
        communityPostQueryPort.findPublicPostById(postId)
                .orElseThrow(() -> new CommunityPostNotFoundException(postId));

        return communityPostQueryPort.findPublicRevisionHistory(postId);
    }
}
