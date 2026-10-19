package com.universe.screen.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.List;
import java.util.UUID;

public class ScreenVideoAlreadyReferencedException extends BaseApplicationException {

    private final UUID videoMediaAssetId;
    private final List<UUID> referencingEntryIds;

    public ScreenVideoAlreadyReferencedException(UUID videoMediaAssetId, List<UUID> referencingEntryIds) {
        super("SCREEN_VIDEO_ALREADY_REFERENCED",
                "Không thể hủy video media asset [" + videoMediaAssetId + "] vì đang được tham chiếu bởi ScreenEntry. Thao tác đã bị từ chối để bảo vệ toàn vẹn dữ liệu.");
        this.videoMediaAssetId = videoMediaAssetId;
        this.referencingEntryIds = referencingEntryIds != null ? List.copyOf(referencingEntryIds) : List.of();
    }

    public UUID getVideoMediaAssetId() {
        return videoMediaAssetId;
    }

    public List<UUID> getReferencingEntryIds() {
        return referencingEntryIds;
    }
}
