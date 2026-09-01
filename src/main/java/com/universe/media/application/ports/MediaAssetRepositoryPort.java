package com.universe.media.application.ports;

import com.universe.media.domain.MediaAsset;

import java.util.Optional;
import java.util.UUID;

public interface MediaAssetRepositoryPort {

    Optional<MediaAsset> findById(
            UUID id
    );

    MediaAsset save(
            MediaAsset asset
    );
}
