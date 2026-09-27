package com.universe.identity.application.ports;

import com.universe.identity.contracts.dto.UserPublicProfileDTO;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Port for batch querying public user profiles without loading full domain aggregates.
 */
public interface UserPublicProfileQueryPort {

    /**
     * Batch-retrieves public user profiles for the specified set of user IDs.
     *
     * @param userIds set of user UUIDs to lookup
     * @return map from user UUID to UserPublicProfileDTO for existing users
     */
    Map<UUID, UserPublicProfileDTO> findPublicProfilesByIds(Set<UUID> userIds);
}
