package com.universe.community.infrastructure.identity;

import com.universe.community.application.port.out.CommunityAuthorProfileDetails;
import com.universe.community.application.port.out.CommunityAuthorProfilePort;
import com.universe.community.application.port.out.CommunityAuthorProfileSummary;
import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.dto.UserPublicProfileDetailsDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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

    @Override
    public Map<UUID, CommunityAuthorProfileSummary> findAuthorProfilesByIds(Set<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, UserPublicProfileDTO> profileMap = userIdentityContract.findPublicProfilesByIds(userIds);
        if (profileMap == null || profileMap.isEmpty()) {
            return Map.of();
        }

        Map<UUID, CommunityAuthorProfileSummary> summaryMap = new HashMap<>();
        for (Map.Entry<UUID, UserPublicProfileDTO> entry : profileMap.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                UserPublicProfileDTO dto = entry.getValue();
                summaryMap.put(entry.getKey(), new CommunityAuthorProfileSummary(
                        dto.userId(),
                        dto.publicHandle(),
                        dto.displayName(),
                        dto.avatarUrl()
                ));
            }
        }
        return Collections.unmodifiableMap(summaryMap);
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
