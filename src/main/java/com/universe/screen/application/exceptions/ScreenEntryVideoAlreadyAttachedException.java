package com.universe.screen.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

public class ScreenEntryVideoAlreadyAttachedException extends BaseApplicationException {

    public ScreenEntryVideoAlreadyAttachedException(UUID entryId, UUID currentVideoAssetId) {
        super("SCREEN_ENTRY_VIDEO_ALREADY_ATTACHED",
                "ScreenEntry [" + entryId + "] đã có video media asset [" + currentVideoAssetId + "] được gắn. Thay thế video chưa được hỗ trợ trong phiên bản hiện tại.");
    }
}
