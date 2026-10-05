package com.universe.community.domain;

/**
 * Canonical runtime publication mode governing Community post creation and effective edits.
 */
public enum CommunityPublicationMode {
    AUTO_PUBLISH,
    PRE_MODERATION;

    public boolean isAutoPublish() {
        return this == AUTO_PUBLISH;
    }

    public boolean isPreModeration() {
        return this == PRE_MODERATION;
    }
}
