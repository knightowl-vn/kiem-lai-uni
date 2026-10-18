package com.universe.media.contracts.interfaces;

import com.universe.media.contracts.dto.MediaAssetStreamDTO;

import java.util.UUID;

/**
 * Consumer-neutral streaming contract for reading Media asset binary streams.
 *
 * <p>Intended for authorized internal consumers (such as Screen, Narration) that have
 * performed target and caller authorization.
 *
 * <p><strong>Stream Ownership:</strong> The caller is responsible for closing the returned
 * stream via {@link MediaAssetStreamDTO#close()} or {@link MediaAssetStreamDTO#contentStream()}.
 */
public interface MediaStreamingContract {

    /**
     * Opens a complete binary stream for the current version of an active media asset.
     *
     * @param assetId ID of the media asset
     * @return stream DTO containing metadata and open InputStream
     */
    MediaAssetStreamDTO openStream(UUID assetId);

    /**
     * Opens a bounded range binary stream for the current version of an active media asset.
     *
     * @param assetId        ID of the media asset
     * @param startInclusive zero-based starting byte offset
     * @param length         number of bytes to read
     * @return stream DTO containing metadata and open InputStream
     */
    MediaAssetStreamDTO openRangeStream(UUID assetId, long startInclusive, long length);
}
