package com.universe.media.application.asset;

/**
 * Passive command record for executing a batch purge of expired DELETED media assets.
 *
 * @param batchSize the maximum number of candidates to process in this run (must be > 0).
 */
public record PurgeExpiredDeletedMediaAssetsCommand(
        int batchSize
) {
}
