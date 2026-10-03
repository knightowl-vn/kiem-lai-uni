package com.universe.community.domain.exception;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

/**
 * Thrown when an actor attempts to delete a CommunityPost that has been hidden by moderation.
 */
public class CommunityPostHiddenDeleteForbiddenException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public CommunityPostHiddenDeleteForbiddenException(UUID postId) {
        super("COMMUNITY_POST_HIDDEN_DELETE_FORBIDDEN", "Cannot delete post " + postId + " while hidden by moderation.");
    }
}
