package com.universe.media.contracts.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.Objects;
import java.util.UUID;

/**
 * Thrown when a generic Media lifecycle operation (archive, delete, or physical purge)
 * is rejected because the asset is currently referenced by one or more consumer entities.
 */
public class MediaAssetReferencedException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    private final UUID mediaAssetId;

    public MediaAssetReferencedException(UUID mediaAssetId, String message) {
        super("MEDIA_ASSET_REFERENCED", message);
        this.mediaAssetId = Objects.requireNonNull(mediaAssetId, "MediaAsset ID cannot be null.");
    }

    public MediaAssetReferencedException(UUID mediaAssetId) {
        this(
                mediaAssetId,
                "Media asset [" + mediaAssetId + "] cannot be archived, deleted, or purged because it is currently referenced by one or more consumers."
        );
    }

    public UUID getMediaAssetId() {
        return mediaAssetId;
    }
}
