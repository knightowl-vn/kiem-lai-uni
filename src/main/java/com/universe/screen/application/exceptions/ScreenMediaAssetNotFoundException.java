package com.universe.screen.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

public class ScreenMediaAssetNotFoundException extends BaseApplicationException {

    public ScreenMediaAssetNotFoundException(UUID mediaAssetId) {
        super("SCREEN_MEDIA_ASSET_NOT_FOUND", "Media asset [" + mediaAssetId + "] không tồn tại trên hệ thống hoặc đã bị xóa.");
    }
}
