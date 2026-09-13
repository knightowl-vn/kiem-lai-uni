package com.universe.wiki.application.image;

import java.time.Instant;
import java.util.UUID;

public record WikiImageAsset(
        UUID id,
        String contentHash,
        String url,
        String publicId,
        UUID mediaAssetId,
        String sourceContentType,
        long sizeBytes,
        Instant createdAt
) {

    public WikiImageAsset(
            UUID id,
            String contentHash,
            String url,
            String publicId,
            String sourceContentType,
            long sizeBytes,
            Instant createdAt
    ) {
        this(
                id,
                contentHash,
                url,
                publicId,
                null,
                sourceContentType,
                sizeBytes,
                createdAt
        );
    }
}