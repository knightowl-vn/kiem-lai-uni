package com.universe.community.domain.exception;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

/**
 * Thrown when a requested CommunityPost is not found.
 */
public class CommunityPostNotFoundException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public CommunityPostNotFoundException(UUID postId) {
        super("COMMUNITY_POST_NOT_FOUND", "Community post not found: " + postId);
    }

    public CommunityPostNotFoundException(String message) {
        super("COMMUNITY_POST_NOT_FOUND", message);
    }
}
