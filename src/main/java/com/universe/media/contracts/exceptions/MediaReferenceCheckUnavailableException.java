package com.universe.media.contracts.exceptions;

import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.shared.exceptions.BaseApplicationException;

import java.util.Objects;
import java.util.UUID;

/**
 * Thrown when a lifecycle operation (archive, delete, or physical purge) is rejected
 * because required consumer reference check protection is not registered or unavailable.
 *
 * <p>Enforces fail-closed protection so that critical consumer data (such as Screen VIDEO assets)
 * cannot silently bypass reference verification due to incomplete configuration or runtime wiring faults.
 */
public class MediaReferenceCheckUnavailableException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    private final UUID mediaAssetId;
    private final MediaTypeDTO mediaType;

    public MediaReferenceCheckUnavailableException(UUID mediaAssetId, MediaTypeDTO mediaType, String message) {
        super("MEDIA_REFERENCE_CHECK_UNAVAILABLE", message);
        this.mediaAssetId = Objects.requireNonNull(mediaAssetId, "MediaAsset ID cannot be null.");
        this.mediaType = Objects.requireNonNull(mediaType, "MediaTypeDTO cannot be null.");
    }

    public MediaReferenceCheckUnavailableException(UUID mediaAssetId, MediaTypeDTO mediaType) {
        this(
                mediaAssetId,
                mediaType,
                "Media asset [" + mediaAssetId + "] of type " + mediaType
                        + " cannot undergo lifecycle mutation: required reference check protection is not registered or unavailable."
        );
    }

    public UUID getMediaAssetId() {
        return mediaAssetId;
    }

    public MediaTypeDTO getMediaType() {
        return mediaType;
    }
}
