package com.universe.media.application.asset;

import com.universe.media.application.exceptions.DuplicateStorageLocationException;
import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.hls.CancelOrSupersedeMediaVideoPackagingUseCase;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.storage.MediaStorageCutoverBarrier;
import com.universe.media.application.storage.StorageKeyAllocationService;
import com.universe.media.domain.ContentHash;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetVersion;
import com.universe.media.domain.MediaType;
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
public class RegisterMediaAssetVersionUseCase {

    private final MediaAssetRepositoryPort mediaAssetRepositoryPort;
    private final MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;
    private final ClockPort clockPort;
    private final StorageKeyAllocationService storageKeyAllocationService;
    private final MediaStorageCutoverBarrier cutoverBarrier;
    private final CancelOrSupersedeMediaVideoPackagingUseCase cancelOrSupersedeUseCase;

    @Autowired
    public RegisterMediaAssetVersionUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort,
            ClockPort clockPort,
            StorageKeyAllocationService storageKeyAllocationService,
            MediaStorageCutoverBarrier cutoverBarrier,
            CancelOrSupersedeMediaVideoPackagingUseCase cancelOrSupersedeUseCase
    ) {
        this(
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort,
                clockPort,
                storageKeyAllocationService,
                cutoverBarrier,
                Objects.requireNonNull(cancelOrSupersedeUseCase, "CancelOrSupersedeMediaVideoPackagingUseCase cannot be null."),
                true
        );
    }

    public RegisterMediaAssetVersionUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort,
            ClockPort clockPort
    ) {
        this(mediaAssetRepositoryPort, mediaAssetVersionRepositoryPort, clockPort, null, null, null, false);
    }

    public RegisterMediaAssetVersionUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort,
            ClockPort clockPort,
            StorageKeyAllocationService storageKeyAllocationService
    ) {
        this(mediaAssetRepositoryPort, mediaAssetVersionRepositoryPort, clockPort, storageKeyAllocationService, null, null, false);
    }

    public RegisterMediaAssetVersionUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort,
            ClockPort clockPort,
            StorageKeyAllocationService storageKeyAllocationService,
            MediaStorageCutoverBarrier cutoverBarrier
    ) {
        this(mediaAssetRepositoryPort, mediaAssetVersionRepositoryPort, clockPort, storageKeyAllocationService, cutoverBarrier, null, false);
    }

    private RegisterMediaAssetVersionUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort,
            ClockPort clockPort,
            StorageKeyAllocationService storageKeyAllocationService,
            MediaStorageCutoverBarrier cutoverBarrier,
            CancelOrSupersedeMediaVideoPackagingUseCase cancelOrSupersedeUseCase,
            boolean strictVideoWiring
    ) {
        this.mediaAssetRepositoryPort = Objects.requireNonNull(mediaAssetRepositoryPort, "MediaAssetRepositoryPort cannot be null.");
        this.mediaAssetVersionRepositoryPort = Objects.requireNonNull(mediaAssetVersionRepositoryPort, "MediaAssetVersionRepositoryPort cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
        this.storageKeyAllocationService = storageKeyAllocationService;
        this.cutoverBarrier = cutoverBarrier;
        this.cancelOrSupersedeUseCase = strictVideoWiring
                ? Objects.requireNonNull(cancelOrSupersedeUseCase, "CancelOrSupersedeMediaVideoPackagingUseCase cannot be null.")
                : cancelOrSupersedeUseCase;
    }

    @Transactional
    public RegisterMediaAssetVersionResult execute(
            RegisterMediaAssetVersionCommand command
    ) {
        Objects.requireNonNull(command, "RegisterMediaAssetVersionCommand cannot be null.");

        UUID assetId = Objects.requireNonNull(command.assetId(), "Asset ID cannot be null.");
        MediaAsset asset = mediaAssetRepositoryPort.findByIdForUpdate(assetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(assetId));

        return createAndPersistNextVersion(asset, command);
    }

    @Transactional
    public AuthoritativeDuplicateProbeResult probeAuthoritativeDuplicate(
            UUID assetId,
            String contentHash
    ) {
        Objects.requireNonNull(assetId, "Asset ID cannot be null.");
        Objects.requireNonNull(contentHash, "Content hash cannot be null.");

        MediaAsset asset = mediaAssetRepositoryPort.findByIdForUpdate(assetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(assetId));

        if (asset.getStatus() != com.universe.media.domain.MediaAssetStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Cannot probe duplicate version for a media asset with status: " + asset.getStatus()
            );
        }

        var currentVersionOpt = mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(
                asset.getId(),
                asset.getCurrentVersionNumber()
        );

        if (currentVersionOpt.isPresent()
                && currentVersionOpt.get().getContentHash() != null
                && currentVersionOpt.get().getContentHash().value().equalsIgnoreCase(contentHash)) {
            return AuthoritativeDuplicateProbeResult.duplicate(asset.getCurrentVersionNumber());
        }

        return AuthoritativeDuplicateProbeResult.different(asset.getCurrentVersionNumber());
    }

    @Transactional
    public RegisterMediaAssetVersionConditionalResult registerConditionalVersion(
            RegisterMediaAssetVersionCommand command
    ) {
        Objects.requireNonNull(command, "RegisterMediaAssetVersionCommand cannot be null.");

        UUID assetId = Objects.requireNonNull(command.assetId(), "Asset ID cannot be null.");
        MediaAsset asset = mediaAssetRepositoryPort.findByIdForUpdate(assetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(assetId));

        if (asset.getStatus() != com.universe.media.domain.MediaAssetStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Cannot register a new version for a media asset with status: " + asset.getStatus()
            );
        }

        var currentVersionOpt = mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(
                asset.getId(),
                asset.getCurrentVersionNumber()
        );
        if (currentVersionOpt.isPresent()
                && currentVersionOpt.get().getContentHash() != null
                && currentVersionOpt.get().getContentHash().value().equalsIgnoreCase(command.contentHash())) {
            return RegisterMediaAssetVersionConditionalResult.unchanged(
                    asset.getId(),
                    asset.getCurrentVersionNumber(),
                    asset.getUpdatedAt()
            );
        }

        RegisterMediaAssetVersionResult created = createAndPersistNextVersion(asset, command);
        return RegisterMediaAssetVersionConditionalResult.versionCreated(
                created.assetId(),
                created.versionId(),
                created.newVersionNumber(),
                created.updatedAt()
        );
    }

    private RegisterMediaAssetVersionResult createAndPersistNextVersion(
            MediaAsset asset,
            RegisterMediaAssetVersionCommand command
    ) {
        StorageLocation storageLocation = StorageLocation.of(
                StorageProviderId.of(command.storageProviderId()),
                StorageKey.of(command.storageKey())
        );

        if (mediaAssetVersionRepositoryPort.existsByStorageLocation(storageLocation)) {
            throw new DuplicateStorageLocationException(storageLocation);
        }

        StorageKeyAllocation verifiedAllocation = verifyAllocationOwnership(
                storageLocation,
                command.expectedAllocationId(),
                command.reservationCorrelationId()
        );

        ContentHash contentHash = ContentHash.of(command.contentHash());
        MimeType mimeType = MimeType.of(command.mimeType());

        Instant now = clockPort.now();
        int newVersionNumber = asset.registerNextVersion(now);
        UUID versionId = UUID.randomUUID();

        MediaAssetVersion version = MediaAssetVersion.create(
                versionId,
                asset.getId(),
                newVersionNumber,
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

        boolean cutoverActive = (cutoverBarrier != null && cutoverBarrier.isCutoverActive());
        if (cutoverActive && verifiedAllocation != null && storageKeyAllocationService != null) {
            storageKeyAllocationService.commitAllocation(
                    verifiedAllocation.getId(),
                    StorageKeyAllocationType.ASSET_VERSION,
                    version.getId()
            );
        }

        if (asset.getMediaType() == MediaType.VIDEO) {
            if (cancelOrSupersedeUseCase == null) {
                throw new IllegalStateException(
                        "Cannot register new version for VIDEO asset [" + asset.getId()
                                + "]: required CancelOrSupersedeMediaVideoPackagingUseCase is unavailable (fail-closed)."
                );
            }
            cancelOrSupersedeUseCase.cancelAndSupersedeOlderVersionsInCurrentTransaction(
                    asset.getId(),
                    newVersionNumber,
                    "Superseded by asset version " + newVersionNumber
            );
        }

        return new RegisterMediaAssetVersionResult(
                asset.getId(),
                version.getId(),
                newVersionNumber,
                now
        );
    }

    private StorageKeyAllocation verifyAllocationOwnership(
            StorageLocation storageLocation,
            UUID expectedAllocationId,
            UUID reservationCorrelationId
    ) {
        boolean cutoverActive = (cutoverBarrier != null && cutoverBarrier.isCutoverActive());
        if (!cutoverActive || storageKeyAllocationService == null) {
            return null;
        }

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
                    "Cannot register media asset version on a tombstoned storage key allocation: " + storageLocation
            );
        }
        if (allocation.getStatus() == StorageKeyAllocationStatus.COMMITTED) {
            throw new IllegalStateException(
                    "Cannot reuse an already committed storage key allocation for a new media asset version: " + storageLocation
            );
        }
        if (allocation.getStatus() == StorageKeyAllocationStatus.RESERVED) {
            if (expectedAllocationId == null || !expectedAllocationId.equals(allocation.getId())) {
                throw new IllegalStateException(
                        "Reservation ownership mismatch: cannot claim storage allocation reserved by another process for: " + storageLocation
                );
            }
            if (reservationCorrelationId == null || !reservationCorrelationId.equals(allocation.getAllocatedById())) {
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
        return allocation;
    }
}
