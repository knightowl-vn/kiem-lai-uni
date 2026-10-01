package com.universe.community.application.port.out;

import java.util.Optional;

/**
 * Outbound port for resolving author public profile identity details from Identity context.
 */
public interface CommunityAuthorProfilePort {

    /**
     * Resolves author public profile details by public handle.
     *
     * @param publicHandle the public handle
     * @return Optional containing the author profile details if found and active
     */
    Optional<CommunityAuthorProfileDetails> findAuthorProfileByHandle(String publicHandle);
}
