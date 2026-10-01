package com.universe.community.infrastructure.identity;

import com.universe.community.application.port.out.CommunityAuthorProfileDetails;
import com.universe.community.application.port.out.CommunityAuthorProfilePort;
import com.universe.identity.contracts.dto.UserPublicProfileDetailsDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * Community infrastructure adapter delegating author profile lookups to {@link UserIdentityContract}.
 */
@Component
public class IdentityCommunityAuthorProfileAdapter implements CommunityAuthorProfilePort {

    private final UserIdentityContract userIdentityContract;

    public IdentityCommunityAuthorProfileAdapter(UserIdentityContract userIdentityContract) {
        this.userIdentityContract = Objects.requireNonNull(userIdentityContract, "UserIdentityContract cannot be null.");
    }

    @Override
    public Optional<CommunityAuthorProfileDetails> findAuthorProfileByHandle(String publicHandle) {
        if (publicHandle == null || publicHandle.isBlank()) {
            return Optional.empty();
        }

        return userIdentityContract.findPublicProfileDetailsByHandle(publicHandle)
                .map(this::toAuthorDetails);
    }

    private CommunityAuthorProfileDetails toAuthorDetails(UserPublicProfileDetailsDTO dto) {
        return new CommunityAuthorProfileDetails(
                dto.userId(),
                dto.publicHandle(),
                dto.displayName(),
                dto.avatarUrl(),
                dto.bio()
        );
    }
}
