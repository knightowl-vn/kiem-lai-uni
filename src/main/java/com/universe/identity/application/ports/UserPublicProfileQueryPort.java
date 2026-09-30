package com.universe.identity.application.ports;

import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.dto.UserPublicProfileDetailsDTO;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Port for batch querying and searching public user profiles without loading full domain aggregates.
 */
public interface UserPublicProfileQueryPort {

    /**
     * Batch-retrieves public user profiles for the specified set of user IDs.
     *
     * @param userIds set of user UUIDs to lookup
     * @return map from user UUID to UserPublicProfileDTO for existing users
     */
    Map<UUID, UserPublicProfileDTO> findPublicProfilesByIds(Set<UUID> userIds);

    /**
     * Retrieves public user profile by unique public handle.
     *
     * @param publicHandle public handle of the user
     * @return Optional containing UserPublicProfileDTO if found and active
     */
    Optional<UserPublicProfileDTO> findPublicProfileByHandle(String publicHandle);

    /**
     * Retrieves detailed public user profile (including bio) by unique public handle.
     *
     * @param publicHandle public handle of the user
     * @return Optional containing UserPublicProfileDetailsDTO if found and active
     */
    Optional<UserPublicProfileDetailsDTO> findPublicProfileDetailsByHandle(String publicHandle);

    /**
     * Searches active public users matching the search query with 5-tier ranking.
     *
     * @param query search keyword
     * @param limit maximum results to return
     * @return ranked list of UserPublicProfileDTO
     */
    List<UserPublicProfileDTO> searchPublicUsers(String query, int limit);
}
