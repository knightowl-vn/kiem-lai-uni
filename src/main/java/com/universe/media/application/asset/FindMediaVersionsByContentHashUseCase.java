package com.universe.media.application.asset;

import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.domain.ContentHash;
import com.universe.media.domain.MediaAssetVersion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
public class FindMediaVersionsByContentHashUseCase {

    private final MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;

    public FindMediaVersionsByContentHashUseCase(
            MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort
    ) {
        this.mediaAssetVersionRepositoryPort =
                Objects.requireNonNull(
                        mediaAssetVersionRepositoryPort,
                        "MediaAssetVersionRepositoryPort cannot be null."
                );
    }

    @Transactional(readOnly = true)
    public List<MediaVersionItemResult> execute(
            FindMediaVersionsByContentHashQuery query
    ) {
        Objects.requireNonNull(
                query,
                "FindMediaVersionsByContentHashQuery cannot be null."
        );

        ContentHash contentHash =
                ContentHash.of(query.contentHash());

        List<MediaAssetVersion> versions =
                mediaAssetVersionRepositoryPort.findByContentHash(contentHash);

        return versions.stream()
                .map(this::toVersionItemResult)
                .toList();
    }

    private MediaVersionItemResult toVersionItemResult(
            MediaAssetVersion version
    ) {
        return new MediaVersionItemResult(
                version.getId(),
                version.getAssetId(),
                version.getVersionNumber(),
                version.getStorageLocation().providerId().value(),
                version.getStorageLocation().key().value(),
                version.getPublicUrl(),
                version.getContentHash().value(),
                version.getMimeType().value(),
                version.getSizeBytes(),
                version.getOriginalFilename(),
                version.getCreatedAt()
        );
    }
}
