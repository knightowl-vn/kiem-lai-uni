package com.universe.community.application.usecase;

import com.universe.community.application.port.out.CommunityAuthorProfileDetails;
import com.universe.community.application.port.out.CommunityAuthorProfilePort;
import com.universe.community.contracts.dto.CommunityAuthorProfileDTO;
import com.universe.community.contracts.dto.CommunityNewestFeedResponseDTO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Use case to retrieve an author's public profile composition including the first page of their Community posts.
 */
@Service
@Transactional(readOnly = true)
public class GetCommunityPublicProfileUseCase {

    private final CommunityAuthorProfilePort authorProfilePort;
    private final GetCommunityAuthorPostsUseCase getCommunityAuthorPostsUseCase;

    public GetCommunityPublicProfileUseCase(
            CommunityAuthorProfilePort authorProfilePort,
            GetCommunityAuthorPostsUseCase getCommunityAuthorPostsUseCase
    ) {
        this.authorProfilePort = Objects.requireNonNull(authorProfilePort, "CommunityAuthorProfilePort cannot be null.");
        this.getCommunityAuthorPostsUseCase = Objects.requireNonNull(getCommunityAuthorPostsUseCase, "GetCommunityAuthorPostsUseCase cannot be null.");
    }

    public Optional<CommunityAuthorProfileDTO> execute(String publicHandle) {
        return execute(publicHandle, null);
    }

    public Optional<CommunityAuthorProfileDTO> execute(String publicHandle, UUID viewerUserId) {
        if (publicHandle == null || publicHandle.isBlank()) {
            return Optional.empty();
        }

        Optional<CommunityAuthorProfileDetails> authorOpt = authorProfilePort.findAuthorProfileByHandle(publicHandle);
        if (authorOpt.isEmpty()) {
            return Optional.empty();
        }

        CommunityAuthorProfileDetails author = authorOpt.get();

        // Fetch first page of authored posts (cursor = null, size = default 20)
        CommunityNewestFeedResponseDTO postsFeed = (viewerUserId != null)
                ? getCommunityAuthorPostsUseCase.execute(
                        author.userId(),
                        null,
                        GetCommunityAuthorPostsUseCase.DEFAULT_SIZE,
                        viewerUserId
                )
                : getCommunityAuthorPostsUseCase.execute(
                        author.userId(),
                        null,
                        GetCommunityAuthorPostsUseCase.DEFAULT_SIZE
                );

        return Optional.of(new CommunityAuthorProfileDTO(
                author.publicHandle(),
                author.displayName(),
                author.avatarUrl(),
                author.bio(),
                postsFeed
        ));
    }
}
