package com.universe.community.domain.exception;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

/**
 * Thrown when an actor attempts to edit a CommunityPost that already has a pending caption edit awaiting moderation review.
 */
public class CommunityPostPendingEditConflictException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public CommunityPostPendingEditConflictException(UUID postId) {
        super("COMMUNITY_POST_PENDING_EDIT_CONFLICT", "Bản chỉnh sửa hiện tại đang chờ quản trị viên duyệt.");
    }

    public CommunityPostPendingEditConflictException(String message) {
        super("COMMUNITY_POST_PENDING_EDIT_CONFLICT", message);
    }
}
