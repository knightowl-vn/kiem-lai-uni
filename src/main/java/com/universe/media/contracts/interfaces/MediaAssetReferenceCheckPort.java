package com.universe.media.contracts.interfaces;

import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.exceptions.MediaAssetReferencedException;

import java.util.UUID;

/**
 * Consumer reference verification port.
 *
 * <p>Implemented by consumer bounded contexts (such as Screen) to protect Media assets from
 * being invalidated, archived, soft-deleted, or physically purged while an active reference
 * to the asset is maintained.
 */
public interface MediaAssetReferenceCheckPort {

    /**
     * Checks whether this reference check port supports and protects assets of the given {@link MediaTypeDTO}.
     *
     * <p>Consumer adapters declare the specific media types they monitor (e.g. Screen declares {@link MediaTypeDTO#VIDEO}).
     * Media lifecycle use cases use this declaration to filter applicable checkers and enforce fail-closed
     * protection when mandatory checker coverage is absent.
     *
     * @param mediaType the media type of the asset being evaluated
     * @return true if this port monitors references for the specified media type; false otherwise
     */
    default boolean supports(MediaTypeDTO mediaType) {
        return false;
    }

    /**
     * Asserts that the specified media asset is not currently referenced by any consumer entity.
     *
     * <p>When invoked within an active transaction, the implementation must acquire exclusive row locks
     * on any referencing rows (preserving established lock ordering) to prevent concurrent attachment races.
     *
     * @param mediaAssetId scalar UUID of the media asset to verify (may be null, in which case no-op)
     * @throws MediaAssetReferencedException if the asset is currently referenced by one or more entities
     */
    void ensureNotReferenced(UUID mediaAssetId) throws MediaAssetReferencedException;
}
