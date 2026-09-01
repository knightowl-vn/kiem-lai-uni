package com.universe.media.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

public class MediaAssetNotFoundException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public MediaAssetNotFoundException(
            UUID assetId
    ) {
        super(
                "MEDIA_ASSET_NOT_FOUND",
                "Media asset not found: " + assetId
        );
    }
}
