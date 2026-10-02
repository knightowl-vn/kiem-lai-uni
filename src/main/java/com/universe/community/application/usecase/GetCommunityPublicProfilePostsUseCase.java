package com.universe.community.application.usecase;

import com.universe.community.application.port.out.CommunityAuthorProfileDetails;
import com.universe.community.application.port.out.CommunityAuthorProfilePort;
import com.universe.community.contracts.dto.CommunityNewestFeedResponseDTO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Use case to retrieve paginated Community posts for a public author profile.
 */
@Service
@Transactional(readOnly = true)
public class GetCommunityPublicProfilePostsUseCase {

    private final CommunityAuthorProfilePort authorProfilePort;
    private final GetCommunityAuthorPostsUseCase getCommunityAuthorPostsUseCase;

    public GetCommunityPublicProfilePostsUseCase(
            CommunityAuthorProfilePort authorProfilePort,
            GetCommunityAuthorPostsUseCase getCommunityAuthorPostsUseCase
    ) {
        this.authorProfilePort = Objects.requireNonNull(authorProfilePort, "CommunityAuthorProfilePort cannot be null.");
        this.getCommunityAuthorPostsUseCase = Objects.requireNonNull(getCommunityAuthorPostsUseCase, "GetCommunityAuthorPostsUseCase cannot be null.");
    }

    public Optional<CommunityNewestFeedResponseDTO> execute(String publicHandle, String cursor, Integer requestedSize) {
        return execute(publicHandle, cursor, requestedSize, null);
    }

    public Optional<CommunityNewestFeedResponseDTO> execute(String publicHandle, String cursor, Integer requestedSize, UUID viewerUserId) {
        if (publicHandle == null || publicHandle.isBlank()) {
            return Optional.empty();
        }

        Optional<CommunityAuthorProfileDetails> authorOpt = authorProfilePort.findAuthorProfileByHandle(publicHandle);
        if (authorOpt.isEmpty()) {
            return Optional.empty();
        }

        CommunityAuthorProfileDetails author = authorOpt.get();

        CommunityNewestFeedResponseDTO postsFeed = (viewerUserId != null)
                ? getCommunityAuthorPostsUseCase.execute(
                        author.userId(),
                        cursor,
                        requestedSize,
                        viewerUserId
                )
                : getCommunityAuthorPostsUseCase.execute(
                        author.userId(),
                        cursor,
                        requestedSize
                );

        return Optional.of(postsFeed);
    }
}
