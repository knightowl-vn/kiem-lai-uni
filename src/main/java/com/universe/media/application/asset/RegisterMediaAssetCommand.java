package com.universe.media.application.asset;

import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;

import java.util.UUID;

public record RegisterMediaAssetCommand(
        MediaType mediaType,
        MediaVisibility visibility,
        String storageProviderId,
        String storageKey,
        String publicUrl,
        String contentHash,
        String mimeType,
        long sizeBytes,
        String originalFilename,
        String clientTag,
        UUID expectedAllocationId,
        UUID reservationCorrelationId
) {
    public RegisterMediaAssetCommand(
            MediaType mediaType,
            MediaVisibility visibility,
            String storageProviderId,
            String storageKey,
            String publicUrl,
            String contentHash,
            String mimeType,
            long sizeBytes,
            String originalFilename,
            String clientTag,
            UUID expectedAllocationId
    ) {
        this(mediaType, visibility, storageProviderId, storageKey, publicUrl, contentHash, mimeType, sizeBytes, originalFilename, clientTag, expectedAllocationId, null);
    }

    public RegisterMediaAssetCommand(
            MediaType mediaType,
            MediaVisibility visibility,
            String storageProviderId,
            String storageKey,
            String publicUrl,
            String contentHash,
            String mimeType,
            long sizeBytes,
            String originalFilename,
            String clientTag
    ) {
        this(mediaType, visibility, storageProviderId, storageKey, publicUrl, contentHash, mimeType, sizeBytes, originalFilename, clientTag, null, null);
    }

    public RegisterMediaAssetCommand(
            MediaType mediaType,
            MediaVisibility visibility,
            String storageProviderId,
            String storageKey,
            String publicUrl,
            String contentHash,
            String mimeType,
            long sizeBytes,
            String originalFilename,
            UUID expectedAllocationId
    ) {
        this(mediaType, visibility, storageProviderId, storageKey, publicUrl, contentHash, mimeType, sizeBytes, originalFilename, null, expectedAllocationId, null);
    }

    public RegisterMediaAssetCommand(
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
        this(mediaType, visibility, storageProviderId, storageKey, publicUrl, contentHash, mimeType, sizeBytes, originalFilename, null, null, null);
    }
}
