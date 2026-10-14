package com.universe.screen.entry.admin.dto;

import java.util.UUID;

/**
 * Response returned after completing multipart upload and attaching video to ScreenEntry.
 */
public record CompleteVideoUploadResponse(
        UUID videoMediaAssetId,
        String playbackUrl,
        String status,
        String message
) {
}
