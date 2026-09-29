package com.universe.community.domain.exception;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

/**
 * Thrown when an actor attempts an unauthorized operation on a CommunityPost.
 */
public class CommunityPostUnauthorizedException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public CommunityPostUnauthorizedException(String message) {
        super("COMMUNITY_POST_UNAUTHORIZED", message);
    }

    public CommunityPostUnauthorizedException(UUID actorUserId, UUID postId) {
        super("COMMUNITY_POST_UNAUTHORIZED", "User " + actorUserId + " is not authorized to modify post " + postId);
    }
}
