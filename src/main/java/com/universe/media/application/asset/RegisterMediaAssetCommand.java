package com.universe.media.application.asset;

import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;

public record RegisterMediaAssetCommand(
        MediaType mediaType,
        MediaVisibility visibility,
        String storageProviderId,
        String storageKey,
        String publicUrl,
        String contentHash,
        String mimeType,
        long sizeBytes,
        String originalFilename
) {
}
