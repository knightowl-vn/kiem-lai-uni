package com.universe.community.application.port.out;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Outbound port for resolving author public profile identity details and summaries from Identity context.
 */
public interface CommunityAuthorProfilePort {

    /**
     * Resolves author public profile details by public handle.
     *
     * @param publicHandle the public handle
     * @return Optional containing the author profile details if found and active
     */
    Optional<CommunityAuthorProfileDetails> findAuthorProfileByHandle(String publicHandle);

    /**
     * Resolves author public profile summaries in bulk by user IDs.
     *
     * @param userIds set of user IDs to resolve
     * @return Map of userId to author profile summary (containing only found users)
     */
    Map<UUID, CommunityAuthorProfileSummary> findAuthorProfilesByIds(Set<UUID> userIds);
}
