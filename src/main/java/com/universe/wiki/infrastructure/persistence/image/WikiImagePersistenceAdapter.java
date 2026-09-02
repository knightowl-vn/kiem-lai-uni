package com.universe.wiki.infrastructure.persistence.image;

import com.universe.wiki.application.image
        .WikiImageAsset;
import com.universe.wiki.application.ports
        .WikiImageRepositoryPort;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class WikiImagePersistenceAdapter
        implements WikiImageRepositoryPort {

    private final SpringDataWikiImageJpaRepository
            repository;

    public WikiImagePersistenceAdapter(
            SpringDataWikiImageJpaRepository repository
    ) {
        this.repository =
                repository;
    }

    @Override
    public Optional<WikiImageAsset>
            findByContentHash(
                    String contentHash
            ) {

        if (
                contentHash == null
                || contentHash.isBlank()
        ) {
            return Optional.empty();
        }

        return repository
                .findByContentHash(
                        contentHash
                )
                .map(this::toAsset);
    }
    
    @Override
    public List<WikiImageAsset> findOrphanImages() {
        return repository
                .findOrphanImages()
                .stream()
                .map(this::toAsset)
                .toList();
    }
    
    @Override
    public List<WikiImageAsset>
            findCleanupCandidates(
                    Instant cutoff
            ) {

        if (cutoff == null) {
            throw new IllegalArgumentException(
                    "Wiki image cleanup cutoff "
                            + "không được để trống."
            );
        }

        return repository
                .findCleanupCandidates(
                        cutoff
                )
                .stream()
                .map(this::toAsset)
                .toList();
    }

    @Override
    public void save(
            WikiImageAsset asset
    ) {
        if (asset == null) {
            throw new IllegalArgumentException(
                    "Wiki image asset không được để trống."
            );
        }

        WikiImageJpaEntity entity =
                new WikiImageJpaEntity();

        entity.setId(
                asset.id().toString()
        );

        entity.setContentHash(
                asset.contentHash()
        );

        entity.setUrl(
                asset.url()
        );

        entity.setPublicId(
                asset.publicId()
        );

        entity.setMediaAssetId(
                asset.mediaAssetId() != null
                        ? asset.mediaAssetId().toString()
                        : null
        );

        entity.setSourceContentType(
                asset.sourceContentType()
        );

        entity.setSizeBytes(
                asset.sizeBytes()
        );

        entity.setCreatedAt(
                asset.createdAt()
        );

        repository.save(
                entity
        );
    }
    
    @Override
    public void deleteById(
            UUID imageId
    ) {
        if (imageId == null) {
            throw new IllegalArgumentException(
                    "Wiki image ID "
                            + "không được để trống."
            );
        }

        repository.deleteById(
                imageId.toString()
        );
    }

    private WikiImageAsset toAsset(
            WikiImageJpaEntity entity
    ) {
        UUID mediaAssetId =
                entity.getMediaAssetId() != null && !entity.getMediaAssetId().isBlank()
                        ? UUID.fromString(entity.getMediaAssetId())
                        : null;

        return new WikiImageAsset(
                UUID.fromString(
                        entity.getId()
                ),
                entity.getContentHash(),
                entity.getUrl(),
                entity.getPublicId(),
                mediaAssetId,
                entity.getSourceContentType(),
                entity.getSizeBytes(),
                entity.getCreatedAt()
        );
    }
}