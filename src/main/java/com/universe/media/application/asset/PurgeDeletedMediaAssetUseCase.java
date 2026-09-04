package com.universe.media.application.asset;

import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.ports.MediaImageVariantRepositoryPort;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetVersion;
import com.universe.media.domain.MediaImageVariant;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrator use case for purging an expired DELETED MediaAsset.
 *
 * <p><strong>Transaction Boundary:</strong>
 * This orchestrator is intentionally <em>NOT</em> {@code @Transactional}. No database transaction is held
 * open during physical binary I/O against {@link BinaryStoragePort}.
 *
 * <p><strong>Purge Ordering:</strong>
 * <ol>
 *     <li>Check aggregate existence. If missing, treat as already-purged idempotent success (zero-count result).</li>
 *     <li>Validate eligibility via {@link MediaAsset#isPurgeEligible(Instant)}.</li>
 *     <li>Collect all persisted {@link com.universe.media.domain.StorageLocation} values for versions and variants.</li>
 *     <li>Physically delete all variant binaries via {@link BinaryStoragePort#delete(com.universe.media.domain.StorageKey)}.</li>
 *     <li>Physically delete all source version binaries via {@link BinaryStoragePort#delete(com.universe.media.domain.StorageKey)}.</li>
 *     <li>Execute atomic bottom-up relational metadata removal via {@link PurgeMediaAssetMetadataService#execute(UUID)}.</li>
 * </ol>
 *
 * <p><strong>Failure &amp; Retry Semantics:</strong>
 * If physical storage deletion fails, the process halts immediately. Database metadata remains in {@code DELETED}
 * status, ensuring the asset remains a candidate for future purge retry without corrupting relational state.
 */
@Service
public class PurgeDeletedMediaAssetUseCase {

    public static final Duration RETENTION_GRACE_PERIOD = Duration.ofDays(7);

    private final MediaAssetRepositoryPort mediaAssetRepositoryPort;
    private final MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;
    private final MediaImageVariantRepositoryPort mediaImageVariantRepositoryPort;
    private final BinaryStoragePort binaryStoragePort;
    private final PurgeMediaAssetMetadataService purgeMediaAssetMetadataService;
    private final ClockPort clockPort;

    public PurgeDeletedMediaAssetUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort,
            MediaImageVariantRepositoryPort mediaImageVariantRepositoryPort,
            BinaryStoragePort binaryStoragePort,
            PurgeMediaAssetMetadataService purgeMediaAssetMetadataService,
            ClockPort clockPort
    ) {
        this.mediaAssetRepositoryPort = Objects.requireNonNull(
                mediaAssetRepositoryPort,
                "MediaAssetRepositoryPort cannot be null."
        );
        this.mediaAssetVersionRepositoryPort = Objects.requireNonNull(
                mediaAssetVersionRepositoryPort,
                "MediaAssetVersionRepositoryPort cannot be null."
        );
        this.mediaImageVariantRepositoryPort = Objects.requireNonNull(
                mediaImageVariantRepositoryPort,
                "MediaImageVariantRepositoryPort cannot be null."
        );
        this.binaryStoragePort = Objects.requireNonNull(
                binaryStoragePort,
                "BinaryStoragePort cannot be null."
        );
        this.purgeMediaAssetMetadataService = Objects.requireNonNull(
                purgeMediaAssetMetadataService,
                "PurgeMediaAssetMetadataService cannot be null."
        );
        this.clockPort = Objects.requireNonNull(
                clockPort,
                "ClockPort cannot be null."
        );
    }

    public PurgeDeletedMediaAssetResult execute(
            PurgeDeletedMediaAssetCommand command
    ) {
        Objects.requireNonNull(command, "PurgeDeletedMediaAssetCommand cannot be null.");

        UUID assetId = Objects.requireNonNull(command.assetId(), "Asset ID cannot be null.");
        Instant now = clockPort.now();

        // 1. Load asset; if missing, treat as already-purged idempotent success
        Optional<MediaAsset> assetOpt = mediaAssetRepositoryPort.findById(assetId);
        if (assetOpt.isEmpty()) {
            return new PurgeDeletedMediaAssetResult(
                    assetId,
                    0,
                    0,
                    now
            );
        }

        MediaAsset asset = assetOpt.get();

        // 2. Validate eligibility using domain aggregate method
        Instant cutoff = now.minus(RETENTION_GRACE_PERIOD);
        if (!asset.isPurgeEligible(cutoff)) {
            throw new IllegalStateException(
                    "Media asset [ID: " + assetId + "] is not eligible for purge. Status: "
                            + asset.getStatus() + ", updatedAt: " + asset.getUpdatedAt() + ", cutoff: " + cutoff
            );
        }

        // 3. Collect versions and variants using persisted StorageLocation values only
        List<MediaAssetVersion> versions = mediaAssetVersionRepositoryPort.findAllByAssetId(assetId);
        List<UUID> versionIds = versions.stream().map(MediaAssetVersion::getId).toList();
        List<MediaImageVariant> variants = versionIds.isEmpty()
                ? List.of()
                : mediaImageVariantRepositoryPort.findAllByVersionIds(versionIds);

        // 4. Physical Storage Deletion (No DB transaction held)
        // 4a. Delete all derivative variant physical binaries
        for (MediaImageVariant variant : variants) {
            binaryStoragePort.delete(variant.getStorageLocation().key());
        }

        // 4b. Delete all source version physical binaries
        for (MediaAssetVersion version : versions) {
            binaryStoragePort.delete(version.getStorageLocation().key());
        }

        // 5. Relational Metadata Deletion (Strict bottom-up in one short write transaction, accepts only assetId)
        purgeMediaAssetMetadataService.execute(assetId);

        return new PurgeDeletedMediaAssetResult(
                assetId,
                versions.size(),
                variants.size(),
                now
        );
    }
}
