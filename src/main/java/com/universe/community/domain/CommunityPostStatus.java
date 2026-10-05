package com.universe.community.domain;

/**
 * Canonical lifecycle status for a Community Post.
 */
public enum CommunityPostStatus {
    PUBLISHED,
    PENDING_REVIEW,
    HIDDEN,
    REJECTED;

    public boolean isPublished() {
        return this == PUBLISHED;
    }

    public boolean isPendingReview() {
        return this == PENDING_REVIEW;
    }

    public boolean isHidden() {
        return this == HIDDEN;
    }

    public boolean isRejected() {
        return this == REJECTED;
    }
}
