package com.universe.media.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

public class MediaImageVariantNotFoundException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public MediaImageVariantNotFoundException(
            UUID versionId,
            String variantKey
    ) {
        super(
                "MEDIA_IMAGE_VARIANT_NOT_FOUND",
                "Media image variant not found for version: " + versionId + ", key: " + variantKey
        );
    }

    public MediaImageVariantNotFoundException(
            UUID assetId,
            int versionNumber,
            String variantKey
    ) {
        super(
                "MEDIA_IMAGE_VARIANT_NOT_FOUND",
                "Media image variant not found for asset: " + assetId + ", version: " + versionNumber + ", key: " + variantKey
        );
    }
}
