package com.universe.community.domain.exception;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

/**
 * Thrown when an actor attempts to delete a CommunityPost that has pending moderation reports.
 */
public class CommunityPostPendingReportConflictException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public CommunityPostPendingReportConflictException(UUID postId) {
        super("COMMUNITY_POST_PENDING_REPORT_CONFLICT", "Cannot delete post " + postId + " while pending moderation reports exist.");
    }
}
