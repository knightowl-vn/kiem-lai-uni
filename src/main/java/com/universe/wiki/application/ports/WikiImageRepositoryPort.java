package com.universe.wiki.application.ports;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.universe.wiki.application.image.WikiImageAsset;

public interface WikiImageRepositoryPort {

    Optional<WikiImageAsset>
            findByContentHash(
                    String contentHash
            );
    
    List<WikiImageAsset> findOrphanImages();
    
    List<WikiImageAsset>
    findCleanupCandidates(
            Instant cutoff
    );
    
    void save(
            WikiImageAsset asset
    );
    
    void deleteById(
            UUID imageId
    );
}