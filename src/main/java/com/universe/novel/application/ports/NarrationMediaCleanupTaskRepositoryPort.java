package com.universe.novel.application.ports;

import com.universe.novel.domain.narration.NarrationMediaCleanupTask;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Application port for durable persistence of narration media cleanup tasks (MS-04.9H.8D1A).
 */
public interface NarrationMediaCleanupTaskRepositoryPort {

    Optional<NarrationMediaCleanupTask> findById(UUID id);

    Optional<NarrationMediaCleanupTask> findByMediaAssetId(UUID mediaAssetId);

    List<NarrationMediaCleanupTask> findOldest(int limit);

    NarrationMediaCleanupTask save(NarrationMediaCleanupTask task);

    void deleteById(UUID id);

    void deleteByMediaAssetId(UUID mediaAssetId);
}
