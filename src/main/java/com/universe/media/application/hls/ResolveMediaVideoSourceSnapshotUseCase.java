package com.universe.media.application.hls;

import com.universe.media.application.asset.VideoUploadValidator;
import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.exceptions.MediaAssetVersionNotFoundException;
import com.universe.media.application.exceptions.UploadContentMimeMismatchException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaAssetVersion;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MimeType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Short read-only metadata use case resolving an immutable, validated {@link MediaVideoSourceSnapshot}
 * for a claimed HLS packaging lease (MS-08 HLS2B3A1).
 *
 * <p><strong>Transaction Boundary:</strong> This use case owns a short {@code readOnly = true}
 * database transaction solely for reading and validating metadata. Callers must NOT open or hold
 * a database transaction when passing the returned snapshot to {@link MediaVideoSourceDownloadPort}.
 *
 * <p><strong>Fencing Notice:</strong> Resolving a snapshot does not replace HLS-1 lease and
 * source-version fencing enforced later by {@link CompleteVerifiedHlsPackagingUseCase}.
 */
@Service
public class ResolveMediaVideoSourceSnapshotUseCase {

    private final MediaAssetRepositoryPort mediaAssetRepositoryPort;
    private final MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;
    private final long maxSourceBytes;

    @Autowired
    public ResolveMediaVideoSourceSnapshotUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort,
            @Value("${media.hls.source-download.max-source-bytes:" + VideoUploadValidator.MAX_MULTIPART_VIDEO_SIZE_BYTES + "}")
            long maxSourceBytes
    ) {
        this.mediaAssetRepositoryPort = Objects.requireNonNull(
                mediaAssetRepositoryPort,
                "MediaAssetRepositoryPort cannot be null."
        );
        this.mediaAssetVersionRepositoryPort = Objects.requireNonNull(
                mediaAssetVersionRepositoryPort,
                "MediaAssetVersionRepositoryPort cannot be null."
        );
        if (maxSourceBytes <= 0L) {
            throw new IllegalArgumentException("maxSourceBytes must be > 0, got: " + maxSourceBytes);
        }
        this.maxSourceBytes = maxSourceBytes;
    }

    public ResolveMediaVideoSourceSnapshotUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort
    ) {
        this(
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort,
                VideoUploadValidator.MAX_MULTIPART_VIDEO_SIZE_BYTES
        );
    }

    @Transactional(readOnly = true)
    public MediaVideoSourceSnapshot resolve(ClaimedVideoPackagingLease lease) {
        Objects.requireNonNull(lease, "ClaimedVideoPackagingLease cannot be null.");
        return resolve(lease.assetId(), lease.sourceVersionId(), lease.sourceVersionNumber());
    }

    @Transactional(readOnly = true)
    public MediaVideoSourceSnapshot resolve(
            UUID assetId,
            UUID expectedSourceVersionId,
            int expectedSourceVersionNumber
    ) {
        Objects.requireNonNull(assetId, "assetId cannot be null.");
        Objects.requireNonNull(expectedSourceVersionId, "expectedSourceVersionId cannot be null.");
        if (expectedSourceVersionNumber < 1) {
            throw new IllegalArgumentException(
                    "expectedSourceVersionNumber must be >= 1, got: " + expectedSourceVersionNumber
            );
        }

        MediaAsset asset = mediaAssetRepositoryPort.findById(assetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(assetId));

        if (asset.getStatus() != MediaAssetStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Cannot resolve HLS source snapshot for non-ACTIVE asset [" + assetId
                            + "]: status=" + asset.getStatus()
            );
        }

        if (asset.getMediaType() != MediaType.VIDEO) {
            throw new IllegalArgumentException(
                    "HLS source snapshot requires MediaType.VIDEO for asset [" + assetId
                            + "], found: " + asset.getMediaType()
            );
        }

        if (asset.getCurrentVersionNumber() != expectedSourceVersionNumber) {
            throw new IllegalStateException(
                    "Claimed source version v" + expectedSourceVersionNumber + " for asset [" + assetId
                            + "] is no longer authoritative (current is v" + asset.getCurrentVersionNumber() + ")."
            );
        }

        MediaAssetVersion version = mediaAssetVersionRepositoryPort
                .findByAssetIdAndVersionNumber(assetId, expectedSourceVersionNumber)
                .orElseThrow(() -> new MediaAssetVersionNotFoundException(assetId, expectedSourceVersionNumber));

        if (!assetId.equals(version.getAssetId())
                || !expectedSourceVersionId.equals(version.getId())
                || version.getVersionNumber() != expectedSourceVersionNumber) {
            throw new IllegalStateException(
                    "Source version identity mismatch for asset [" + assetId + "]: expectedVersionId="
                            + expectedSourceVersionId + ", actualVersionId=" + version.getId()
                            + ", expectedVersionNumber=" + expectedSourceVersionNumber
                            + ", actualVersionNumber=" + version.getVersionNumber()
            );
        }

        MimeType mimeType = version.getMimeType();
        if (mimeType == null || !VideoUploadValidator.CANONICAL_MIME_TYPE.equals(mimeType.value().toLowerCase(Locale.ROOT))) {
            throw new UploadContentMimeMismatchException(
                    "Unsupported source MIME type '" + (mimeType != null ? mimeType.value() : "null")
                            + "' for HLS packaging on asset [" + assetId + "]. Required: '"
                            + VideoUploadValidator.CANONICAL_MIME_TYPE + "'."
            );
        }

        long sizeBytes = version.getSizeBytes();
        if (sizeBytes <= 0L || sizeBytes > maxSourceBytes) {
            throw new IllegalArgumentException(
                    "Source video size (" + sizeBytes + " bytes) for asset [" + assetId
                            + "] violates allowed bounds (1.." + maxSourceBytes + ")."
            );
        }

        return new MediaVideoSourceSnapshot(
                asset.getId(),
                version.getId(),
                version.getVersionNumber(),
                version.getStorageLocation().providerId(),
                version.getStorageLocation().key(),
                version.getContentHash(),
                version.getContentHashStatus(),
                sizeBytes,
                mimeType
        );
    }
}
