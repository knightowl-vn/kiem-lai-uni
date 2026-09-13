package com.universe.media.application.asset;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.exceptions.MediaAssetVersionNotFoundException;
import com.universe.media.application.ports.MediaAssetCurrentMetadataQueryPort;
import com.universe.media.application.ports.MediaAssetCurrentMetadataQueryPort.MediaAssetCurrentMetadataSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
public class GetMediaAssetCurrentMetadataUseCase {

    private final MediaAssetCurrentMetadataQueryPort queryPort;

    public GetMediaAssetCurrentMetadataUseCase(MediaAssetCurrentMetadataQueryPort queryPort) {
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort must not be null");
    }

    @Transactional(readOnly = true)
    public MediaAssetCurrentMetadataResult execute(GetMediaAssetCurrentMetadataQuery query) {
        Objects.requireNonNull(query, "GetMediaAssetCurrentMetadataQuery cannot be null.");
        UUID assetId = Objects.requireNonNull(query.assetId(), "Asset ID cannot be null.");

        MediaAssetCurrentMetadataSnapshot snapshot = queryPort.findByAssetId(assetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(assetId));

        if (!assetId.equals(snapshot.assetId())
                || !assetId.equals(snapshot.declaredCurrentVersionAssetId())
                || snapshot.declaredCurrentVersionNumber() == null
                || snapshot.declaredCurrentVersionNumber() != snapshot.currentVersionNumber()) {
            throw new MediaAssetVersionNotFoundException(assetId, snapshot.currentVersionNumber());
        }

        return new MediaAssetCurrentMetadataResult(
                snapshot.assetId(),
                snapshot.status(),
                snapshot.visibility(),
                snapshot.currentVersionNumber()
        );
    }
}
