package com.universe.media.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Generic domain exception thrown when attempting to assign a client tag to a MediaAsset
 * that already has a different non-null client tag.
 */
public class ClientTagConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID assetId;
    private final String existingTag;
    private final String requestedTag;

    public ClientTagConflictException(UUID assetId, String existingTag, String requestedTag) {
        super(String.format("Media asset [%s] already has client tag [%s], conflicting with requested tag [%s].",
                assetId, existingTag, requestedTag));
        this.assetId = Objects.requireNonNull(assetId, "assetId must not be null.");
        this.existingTag = Objects.requireNonNull(existingTag, "existingTag must not be null.");
        this.requestedTag = Objects.requireNonNull(requestedTag, "requestedTag must not be null.");
    }

    public UUID getAssetId() {
        return assetId;
    }

    public String getExistingTag() {
        return existingTag;
    }

    public String getRequestedTag() {
        return requestedTag;
    }
}
