package com.universe.community.application.port.out;

import com.universe.community.domain.CommunityPostRevision;

import java.util.List;
import java.util.UUID;

/**
 * Persistence port for saving and querying {@link CommunityPostRevision} records.
 */
public interface CommunityPostRevisionRepositoryPort {

    /**
     * Saves an immutable CommunityPostRevision snapshot.
     *
     * @param revision the revision domain record
     * @return the saved revision
     */
    CommunityPostRevision save(CommunityPostRevision revision);

    /**
     * Finds all revisions for a post, ordered by revision number ascending.
     *
     * @param postId the post UUID
     * @return list of revisions
     */
    List<CommunityPostRevision> findByPostIdOrderByRevisionNumberAsc(UUID postId);
}
