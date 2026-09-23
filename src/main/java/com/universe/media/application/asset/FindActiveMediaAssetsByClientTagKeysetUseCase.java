package com.universe.media.application.asset;

import com.universe.media.application.ports.MediaAssetCandidate;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * Use case to discover active media assets by client tag using keyset pagination.
 *
 * <p>Query is executed under read-only transaction semantics without mutating asset state or binary storage.
 */
@Service
public class FindActiveMediaAssetsByClientTagKeysetUseCase {

    private final MediaAssetRepositoryPort mediaAssetRepositoryPort;

    public FindActiveMediaAssetsByClientTagKeysetUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort
    ) {
        this.mediaAssetRepositoryPort = Objects.requireNonNull(
                mediaAssetRepositoryPort,
                "MediaAssetRepositoryPort cannot be null."
        );
    }

    @Transactional(readOnly = true)
    public List<MediaAssetCandidate> execute(
            FindActiveMediaAssetsKeysetQuery query
    ) {
        Objects.requireNonNull(
                query,
                "FindActiveMediaAssetsKeysetQuery cannot be null."
        );

        return mediaAssetRepositoryPort.findActiveByClientTagKeyset(
                query.clientTag(),
                query.createdBeforeUpperBound(),
                query.lastCreatedAt(),
                query.lastAssetId(),
                query.pageSize()
        );
    }
}
