package com.universe.media.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

public class MediaAssetVersionContentHashMismatchException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public MediaAssetVersionContentHashMismatchException(
            UUID assetId,
            int versionNumber
    ) {
        super(
                "MEDIA_ASSET_VERSION_CONTENT_HASH_MISMATCH",
                "Media asset version content hash mismatch for asset: " + assetId + ", version: " + versionNumber
        );
    }
}
