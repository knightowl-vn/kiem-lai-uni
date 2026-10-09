package com.universe.media.application.asset;

import com.universe.media.application.exceptions.DuplicateStorageLocationException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.storage.MediaStorageCutoverBarrier;
import com.universe.media.application.storage.StorageKeyAllocationService;
import com.universe.media.domain.ContentHash;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetVersion;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageKeyAllocation;
import com.universe.media.domain.StorageKeyAllocationStatus;
import com.universe.media.domain.StorageKeyAllocationType;
import com.universe.media.domain.StorageLocation;
import com.universe.media.domain.StorageProviderId;
import com.universe.shared.time.ClockPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class RegisterMediaAssetUseCase {

    private final MediaAssetRepositoryPort mediaAssetRepositoryPort;
    private final MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;
    private final ClockPort clockPort;
    private final StorageKeyAllocationService storageKeyAllocationService;
    private final MediaStorageCutoverBarrier cutoverBarrier;

    @Autowired
    public RegisterMediaAssetUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort,
            ClockPort clockPort,
            StorageKeyAllocationService storageKeyAllocationService,
            MediaStorageCutoverBarrier cutoverBarrier
    ) {
        this.mediaAssetRepositoryPort = Objects.requireNonNull(mediaAssetRepositoryPort, "MediaAssetRepositoryPort cannot be null.");
        this.mediaAssetVersionRepositoryPort = Objects.requireNonNull(mediaAssetVersionRepositoryPort, "MediaAssetVersionRepositoryPort cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
        this.storageKeyAllocationService = storageKeyAllocationService;
        this.cutoverBarrier = cutoverBarrier;
    }

    public RegisterMediaAssetUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort,
            ClockPort clockPort
    ) {
        this(mediaAssetRepositoryPort, mediaAssetVersionRepositoryPort, clockPort, null, null);
    }

    public RegisterMediaAssetUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort,
            ClockPort clockPort,
            StorageKeyAllocationService storageKeyAllocationService
    ) {
        this(mediaAssetRepositoryPort, mediaAssetVersionRepositoryPort, clockPort, storageKeyAllocationService, null);
    }

    @Transactional
    public RegisterMediaAssetResult execute(
            RegisterMediaAssetCommand command
    ) {
        Objects.requireNonNull(command, "RegisterMediaAssetCommand cannot be null.");

        StorageLocation storageLocation = StorageLocation.of(
                StorageProviderId.of(command.storageProviderId()),
                StorageKey.of(command.storageKey())
        );

        if (mediaAssetVersionRepositoryPort.existsByStorageLocation(storageLocation)) {
            throw new DuplicateStorageLocationException(storageLocation);
        }

        StorageKeyAllocation verifiedAllocation = null;
        boolean cutoverActive = (cutoverBarrier != null && cutoverBarrier.isCutoverActive());

        if (cutoverActive && storageKeyAllocationService != null) {
            Optional<StorageKeyAllocation> allocOpt = storageKeyAllocationService.findByStorageLocation(
                    storageLocation.providerId(),
                    storageLocation.key()
            );

            if (allocOpt.isEmpty()) {
                throw new IllegalStateException(
                        "Active cutover barrier requires a valid storage key allocation, but none was found for: " + storageLocation
                );
            }

            StorageKeyAllocation allocation = allocOpt.get();
            if (allocation.getStatus() == StorageKeyAllocationStatus.TOMBSTONE) {
                throw new IllegalStateException(
                        "Cannot register media asset on a tombstoned storage key allocation: " + storageLocation
                );
            }
            if (allocation.getStatus() == StorageKeyAllocationStatus.COMMITTED) {
                throw new IllegalStateException(
                        "Cannot reuse an already committed storage key allocation for a new media asset: " + storageLocation
                );
            }
            if (allocation.getStatus() == StorageKeyAllocationStatus.RESERVED) {
                if (command.expectedAllocationId() == null || !command.expectedAllocationId().equals(allocation.getId())) {
                    throw new IllegalStateException(
                            "Reservation ownership mismatch: cannot claim storage allocation reserved by another process for: " + storageLocation
                    );
                }
                if (command.reservationCorrelationId() == null || !command.reservationCorrelationId().equals(allocation.getAllocatedById())) {
                    throw new IllegalStateException(
                            "Reservation ownership mismatch: caller does not hold the correlation token for storage allocation: " + allocation.getId()
                    );
                }
                if (allocation.getAllocatedByType() != StorageKeyAllocationType.ASSET_VERSION) {
                    throw new IllegalStateException(
                            "Cannot claim storage allocation reserved for " + allocation.getAllocatedByType() + " as ASSET_VERSION."
                    );
                }
            }
            verifiedAllocation = allocation;
        }

        ContentHash contentHash = ContentHash.of(command.contentHash());
        MimeType mimeType = MimeType.of(command.mimeType());

        UUID assetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Instant now = clockPort.now();

        MediaAsset asset = MediaAsset.registerInitial(
                assetId,
                command.mediaType(),
                command.visibility(),
                command.clientTag(),
                now
        );

        MediaAssetVersion version = MediaAssetVersion.create(
                versionId,
                assetId,
                1,
                storageLocation,
                command.publicUrl(),
                contentHash,
                mimeType,
                command.sizeBytes(),
                command.originalFilename(),
                now
        );

        mediaAssetRepositoryPort.save(asset);
        mediaAssetVersionRepositoryPort.save(version);

        if (cutoverActive && verifiedAllocation != null && storageKeyAllocationService != null) {
            storageKeyAllocationService.commitAllocation(
                    verifiedAllocation.getId(),
                    StorageKeyAllocationType.ASSET_VERSION,
                    version.getId()
            );
        }

        return new RegisterMediaAssetResult(
                asset.getId(),
                version.getId(),
                version.getVersionNumber(),
                asset.getMediaType(),
                asset.getVisibility(),
                asset.getStatus(),
                asset.getCreatedAt()
        );
    }
}
