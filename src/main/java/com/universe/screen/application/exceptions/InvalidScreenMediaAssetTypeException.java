package com.universe.screen.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

public class InvalidScreenMediaAssetTypeException extends BaseApplicationException {

    public InvalidScreenMediaAssetTypeException(UUID mediaAssetId, String actualType) {
        this(mediaAssetId, "IMAGE", actualType);
    }

    public InvalidScreenMediaAssetTypeException(UUID mediaAssetId, String expectedType, String actualType) {
        super("INVALID_SCREEN_MEDIA_ASSET_TYPE",
                "Media asset [" + mediaAssetId + "] không hợp lệ. Yêu cầu loại " + expectedType + ", loại hiện tại: " + actualType + ".");
    }
}
