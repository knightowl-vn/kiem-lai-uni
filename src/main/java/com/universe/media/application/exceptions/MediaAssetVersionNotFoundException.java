package com.universe.media.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

public class MediaAssetVersionNotFoundException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public MediaAssetVersionNotFoundException(
            UUID assetId,
            int versionNumber
    ) {
        super(
                "MEDIA_ASSET_VERSION_NOT_FOUND",
                "Media asset version not found for asset: " + assetId + ", version: " + versionNumber
        );
    }
}
