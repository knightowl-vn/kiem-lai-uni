package com.universe.media.application.ports.storage;

import com.universe.media.application.hls.VerifiedHlsPackageReceipt;
import com.universe.media.domain.ContentHash;

import java.time.Instant;
import java.util.Objects;

/**
 * Remote verification outcome passed from a trusted {@link HlsPackageArtifactStoragePort}
 * implementation (HLS-2B2B) to the HLS packaging completion worker (HLS-2B3).
 *
 * <p><strong>Structural vs. Network Provenance Boundary:</strong>
 * {@link #fromRemoteVerification} enforces strict structural, identity, bucket, completeness,
 * count, total-size, and master-digest matching invariants. However, a pure Java value type
 * cannot by itself prove that remote S3/R2 bytes were read over the network. Authentic remote
 * provenance in production depends on the HLS-2B3 worker obtaining this result exclusively
 * from the concrete {@link HlsPackageArtifactStoragePort} adapter after real remote
 * {@code ListObjectsV2} and streamed {@code GetObject} digest verification (never from local
 * packaging inventory or ETag-to-MD5 assumptions).
 */
public final class HlsUploadedPackageVerification {

    /**
     * Minimum remote verification observations gathered by the remote adapter.
     */
    public record RemoteVerificationEvidence(
            HlsRemoteIntegrityProof integrityProof,
            boolean remotePrefixListed,
            boolean allExpectedKeysFound,
            boolean zeroUnexpectedKeysUnderPrefix,
            int remoteDigestedManifestAndInitCount,
            ContentHash remoteObservedMasterSha256,
            long remoteObservedTotalBytes,
            int remoteObservedObjectCount,
            Instant verifiedAt
    ) {
        public RemoteVerificationEvidence {
            Objects.requireNonNull(integrityProof, "integrityProof cannot be null.");
            Objects.requireNonNull(remoteObservedMasterSha256, "remoteObservedMasterSha256 cannot be null.");
            Objects.requireNonNull(verifiedAt, "verifiedAt cannot be null.");
        }
    }

    private final HlsPackageIdentity identity;
    private final String verifiedBucketName;
    private final HlsRemoteIntegrityProof integrityProof;
    private final int verifiedObjectCount;
    private final long verifiedTotalBytes;
    private final boolean exactPrefixKeySetMatched;
    private final VerifiedHlsPackageReceipt remoteReceipt;

    private HlsUploadedPackageVerification(
            HlsPackageIdentity identity,
            String verifiedBucketName,
            HlsRemoteIntegrityProof integrityProof,
            int verifiedObjectCount,
            long verifiedTotalBytes,
            boolean exactPrefixKeySetMatched,
            VerifiedHlsPackageReceipt remoteReceipt
    ) {
        this.identity = identity;
        this.verifiedBucketName = verifiedBucketName;
        this.integrityProof = integrityProof;
        this.verifiedObjectCount = verifiedObjectCount;
        this.verifiedTotalBytes = verifiedTotalBytes;
        this.exactPrefixKeySetMatched = exactPrefixKeySetMatched;
        this.remoteReceipt = remoteReceipt;
    }

    /**
     * Validates structural parity between remote object-storage observations and the expected
     * artifact manifest, and builds the resulting {@link VerifiedHlsPackageReceipt} handoff.
     *
     * <p>Caller provenance: intended strictly for remote {@link HlsPackageArtifactStoragePort}
     * implementations after performing real remote listing and streamed GET digest verification.
     */
    public static HlsUploadedPackageVerification fromRemoteVerification(
            String trustedBucketName,
            HlsExpectedArtifactManifest expectedManifest,
            RemoteVerificationEvidence evidence
    ) {
        String validBucket = HlsPackageKeyValidator.validateBucketName(trustedBucketName);
        Objects.requireNonNull(expectedManifest, "expectedManifest cannot be null.");
        Objects.requireNonNull(evidence, "evidence cannot be null.");

        if (!evidence.remotePrefixListed()
                || !evidence.allExpectedKeysFound()
                || !evidence.zeroUnexpectedKeysUnderPrefix()) {
            throw new IllegalArgumentException(
                    "Remote verification requires full prefix listing, all expected keys present, and zero unexpected keys."
            );
        }
        int expectedManifestAndInitCount =
                1 + expectedManifest.stage2MediaPlaylists().size()
                        + (int) expectedManifest.stage1BinaryArtifacts().stream()
                                .filter(a -> a.artifactType() == HlsArtifactType.INIT_SEGMENT)
                                .count();
        if (evidence.remoteDigestedManifestAndInitCount() < expectedManifestAndInitCount) {
            throw new IllegalArgumentException(
                    "Remote verification requires streamed digest verification of all manifests and init segments ("
                            + expectedManifestAndInitCount + "), observed=" + evidence.remoteDigestedManifestAndInitCount()
            );
        }
        if (!expectedManifest.masterManifestSha256().equals(evidence.remoteObservedMasterSha256())) {
            throw new IllegalArgumentException(
                    "Remote master.m3u8 SHA-256 mismatch: expected=" + expectedManifest.masterManifestSha256()
                            + ", observed=" + evidence.remoteObservedMasterSha256()
            );
        }
        if (evidence.remoteObservedObjectCount() != expectedManifest.objectCount()) {
            throw new IllegalArgumentException(
                    "Remote object count mismatch: expected=" + expectedManifest.objectCount()
                            + ", observed=" + evidence.remoteObservedObjectCount()
            );
        }
        if (evidence.remoteObservedTotalBytes() != expectedManifest.totalSizeBytes()) {
            throw new IllegalArgumentException(
                    "Remote total bytes mismatch: expected=" + expectedManifest.totalSizeBytes()
                            + ", observed=" + evidence.remoteObservedTotalBytes()
            );
        }

        VerifiedHlsPackageReceipt receipt = new VerifiedHlsPackageReceipt(
                evidence.remoteObservedMasterSha256(),
                evidence.remoteObservedTotalBytes(),
                evidence.remoteObservedObjectCount(),
                expectedManifest.renditionsSummaryJson(),
                evidence.verifiedAt()
        );

        return new HlsUploadedPackageVerification(
                expectedManifest.identity(),
                validBucket,
                evidence.integrityProof(),
                evidence.remoteObservedObjectCount(),
                evidence.remoteObservedTotalBytes(),
                true,
                receipt
        );
    }

    public HlsPackageIdentity identity() {
        return identity;
    }

    public String verifiedBucketName() {
        return verifiedBucketName;
    }

    public HlsRemoteIntegrityProof integrityProof() {
        return integrityProof;
    }

    public int verifiedObjectCount() {
        return verifiedObjectCount;
    }

    public long verifiedTotalBytes() {
        return verifiedTotalBytes;
    }

    public boolean exactPrefixKeySetMatched() {
        return exactPrefixKeySetMatched;
    }

    public VerifiedHlsPackageReceipt remoteReceipt() {
        return remoteReceipt;
    }
}
