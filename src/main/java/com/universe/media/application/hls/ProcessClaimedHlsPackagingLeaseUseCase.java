package com.universe.media.application.hls;

import com.universe.media.application.exceptions.StalePackagingLeaseException;
import com.universe.media.application.ports.storage.HlsArtifactDescriptor;
import com.universe.media.application.ports.storage.HlsArtifactStreamSource;
import com.universe.media.application.ports.storage.HlsExpectedArtifactManifest;
import com.universe.media.application.ports.storage.HlsPackageArtifactStoragePort;
import com.universe.media.application.ports.storage.HlsPackageIdentity;
import com.universe.media.application.ports.storage.HlsPackageKeyValidator;
import com.universe.media.application.ports.storage.HlsPackageUploadCommand;
import com.universe.media.application.ports.storage.HlsUploadedPackageVerification;
import com.universe.media.domain.MediaVideoHlsPackage;
import com.universe.media.domain.MediaVideoPackagingJob;
import com.universe.media.domain.StorageProviderId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

/**
 * Passive, non-transactional core orchestrator processing one already-claimed
 * {@link ClaimedVideoPackagingLease} from verified source resolution through remote
 * verification receipt and fenced READY publication (MS-08 HLS-2B3B1).
 *
 * <p><strong>Boundary & Supervision Notice:</strong>
 * This class never polls jobs, creates leases, starts threads, schedules timers, or enables remote
 * operations on startup. Unattended production safety additionally requires B2 periodic lease
 * heartbeating and active interruption backing {@link HlsPackagingLeaseGuard}.
 */
public class ProcessClaimedHlsPackagingLeaseUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProcessClaimedHlsPackagingLeaseUseCase.class);

    private final ResolveMediaVideoSourceSnapshotUseCase resolveSourceSnapshotUseCase;
    private final MediaVideoSourceDownloadPort sourceDownloadPort;
    private final HlsLocalPackagingPort localPackagingPort;
    private final HlsPackageArtifactStoragePort packageArtifactStoragePort;
    private final CompleteVerifiedHlsPackagingUseCase completeVerifiedHlsPackagingUseCase;
    private final FailMediaVideoPackagingJobUseCase failMediaVideoPackagingJobUseCase;
    private final StorageProviderId expectedOutputProviderId;
    private final String expectedOutputBucketName;
    private final HlsPackagingArtifactLimits artifactLimits;

    public ProcessClaimedHlsPackagingLeaseUseCase(
            ResolveMediaVideoSourceSnapshotUseCase resolveSourceSnapshotUseCase,
            MediaVideoSourceDownloadPort sourceDownloadPort,
            HlsLocalPackagingPort localPackagingPort,
            HlsPackageArtifactStoragePort packageArtifactStoragePort,
            CompleteVerifiedHlsPackagingUseCase completeVerifiedHlsPackagingUseCase,
            FailMediaVideoPackagingJobUseCase failMediaVideoPackagingJobUseCase,
            StorageProviderId expectedOutputProviderId,
            String expectedOutputBucketName,
            HlsPackagingArtifactLimits artifactLimits
    ) {
        this.resolveSourceSnapshotUseCase = Objects.requireNonNull(
                resolveSourceSnapshotUseCase,
                "ResolveMediaVideoSourceSnapshotUseCase cannot be null."
        );
        this.sourceDownloadPort = Objects.requireNonNull(
                sourceDownloadPort,
                "MediaVideoSourceDownloadPort cannot be null."
        );
        this.localPackagingPort = Objects.requireNonNull(
                localPackagingPort,
                "HlsLocalPackagingPort cannot be null."
        );
        this.packageArtifactStoragePort = Objects.requireNonNull(
                packageArtifactStoragePort,
                "HlsPackageArtifactStoragePort cannot be null."
        );
        this.completeVerifiedHlsPackagingUseCase = Objects.requireNonNull(
                completeVerifiedHlsPackagingUseCase,
                "CompleteVerifiedHlsPackagingUseCase cannot be null."
        );
        this.failMediaVideoPackagingJobUseCase = Objects.requireNonNull(
                failMediaVideoPackagingJobUseCase,
                "FailMediaVideoPackagingJobUseCase cannot be null."
        );
        Objects.requireNonNull(expectedOutputProviderId, "expectedOutputProviderId cannot be null.");
        HlsPackageKeyValidator.validateRequiredProvider(expectedOutputProviderId);
        this.expectedOutputProviderId = expectedOutputProviderId;
        this.expectedOutputBucketName = HlsPackageKeyValidator.validateBucketName(expectedOutputBucketName);
        this.artifactLimits = Objects.requireNonNull(artifactLimits, "HlsPackagingArtifactLimits cannot be null.");
    }

    /**
     * Executes one claimed packaging attempt strictly outside any ambient database transaction.
     */
    public HlsPackagingAttemptOutcome process(
            ClaimedVideoPackagingLease lease,
            String workerId,
            HlsPackagingLeaseGuard leaseGuard
    ) {
        Objects.requireNonNull(lease, "ClaimedVideoPackagingLease cannot be null.");
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId cannot be blank.");
        }
        Objects.requireNonNull(leaseGuard, "HlsPackagingLeaseGuard cannot be null.");

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "ProcessClaimedHlsPackagingLeaseUseCase.process must execute strictly outside any active "
                            + "database transaction (job [" + lease.jobId() + "])."
            );
        }

        String normalizedWorkerId = workerId.trim();
        DownloadedSourceScope sourceScope = null;
        PreparedHlsPackageScope preparedScope = null;
        boolean readyCommitted = false;
        Throwable sourceCleanupFailure = null;
        Throwable packageCleanupFailure = null;

        try {
            validateClaimedLeaseDestinationAndIdentity(lease);

            // Checkpoint 1: before resolving source metadata
            leaseGuard.ensureActive();
            MediaVideoSourceSnapshot snapshot = resolveSourceSnapshotUseCase.resolve(lease);
            validateResolvedSnapshotMatchesLease(lease, snapshot);

            // Checkpoint 2: before downloading source MP4
            leaseGuard.ensureActive();
            sourceScope = sourceDownloadPort.downloadVerifiedSource(snapshot);
            validateDownloadedSourceScope(snapshot, sourceScope);

            // Checkpoint 3 & 4: after download, before FFmpeg packaging
            leaseGuard.ensureActive();
            preparedScope = localPackagingPort.preparePackage(new PrepareLocalHlsPackageCommand(
                    sourceScope.sourceFile(),
                    lease.assetId(),
                    lease.sourceVersionNumber(),
                    lease.packageUuid(),
                    lease.storageProviderId(),
                    lease.segmentDurationSeconds(),
                    artifactLimits.maxObjectCount(),
                    artifactLimits.maxSingleObjectBytes()
            ));

            // Close downloaded source MP4 scope immediately once FFmpeg no longer needs it
            sourceCleanupFailure = closeQuietlyRecordingFailure(sourceScope, "source MP4 scope", lease);
            sourceScope = null;

            // Checkpoint 5 & 6: after local packaging, before inventory validation & remote upload
            leaseGuard.ensureActive();
            HlsExpectedArtifactManifest expectedManifest = validatePreparedPackageScope(lease, preparedScope);
            HlsArtifactStreamSource streamSource = preparedScope.artifactStreamSource();

            HlsPackageUploadCommand uploadCommand = new HlsPackageUploadCommand(
                    lease.storageBucketName(),
                    expectedManifest,
                    streamSource
            );
            HlsUploadedPackageVerification verification = packageArtifactStoragePort.uploadAndVerifyPackage(uploadCommand);
            VerifiedHlsPackageReceipt receipt = validateRemoteVerificationResult(lease, expectedManifest, verification);

            // Checkpoint 7 & 8: after remote verification, immediately before fenced READY completion
            leaseGuard.ensureActive();
            MediaVideoHlsPackage readyPackage = completeVerifiedHlsPackagingUseCase.completeWithVerifiedReceipt(
                    lease.assetId(),
                    lease.jobId(),
                    lease.packageId(),
                    normalizedWorkerId,
                    lease.leaseGeneration(),
                    receipt
            );
            readyCommitted = true;

            packageCleanupFailure = closeQuietlyRecordingFailure(preparedScope, "local HLS package scope", lease);
            preparedScope = null;

            Throwable combinedCleanup = combineFailures(sourceCleanupFailure, packageCleanupFailure);
            return HlsPackagingAttemptOutcome.success(lease, readyPackage, combinedCleanup);
        } catch (RuntimeException failure) {
            Throwable ensureClosedSourceErr = closeQuietlyRecordingFailure(sourceScope, "source MP4 scope", lease);
            sourceScope = null;
            Throwable ensureClosedPkgErr = closeQuietlyRecordingFailure(preparedScope, "local HLS package scope", lease);
            preparedScope = null;
            Throwable combinedCleanup = combineFailures(
                    combineFailures(sourceCleanupFailure, ensureClosedSourceErr),
                    combineFailures(packageCleanupFailure, ensureClosedPkgErr)
            );

            if (readyCommitted) {
                log.warn("Post-READY failure during local scope cleanup for job [{}], package [{}]: {}",
                        lease.jobId(), lease.packageId(), failure.getMessage());
                throw failure;
            }

            if (failure instanceof StalePackagingLeaseException
                    || leaseGuard.isCancelled()
                    || !leaseGuard.isLeaseConsideredLive()) {
                return HlsPackagingAttemptOutcome.staleOrCancelled(
                        lease,
                        "STALE_OR_CANCELLED",
                        failure.getMessage() != null ? failure.getMessage() : "Packaging lease cancelled or stale.",
                        combinedCleanup
                );
            }

            HlsPackagingFailureClassifier.Classification classification =
                    HlsPackagingFailureClassifier.classify(failure, lease.attemptNumber(), artifactLimits);
            if (classification.outcomeStatus() == HlsPackagingOutcomeStatus.STALE_OR_CANCELLED) {
                return HlsPackagingAttemptOutcome.staleOrCancelled(
                        lease,
                        classification.category(),
                        classification.errorReason(),
                        combinedCleanup
                );
            }

            try {
                MediaVideoPackagingJob updatedJob = failMediaVideoPackagingJobUseCase.execute(
                        lease.assetId(),
                        lease.jobId(),
                        normalizedWorkerId,
                        lease.leaseGeneration(),
                        classification.disposition(),
                        classification.errorReason(),
                        classification.retryBackoff()
                );
                return HlsPackagingAttemptOutcome.failure(
                        classification.outcomeStatus(),
                        lease,
                        updatedJob,
                        classification.category(),
                        classification.errorReason(),
                        combinedCleanup
                );
            } catch (StalePackagingLeaseException staleEx) {
                return HlsPackagingAttemptOutcome.staleOrCancelled(
                        lease,
                        "STALE_OR_CANCELLED",
                        staleEx.getMessage(),
                        combinedCleanup
                );
            }
        } finally {
            closeQuietlyRecordingFailure(sourceScope, "source MP4 scope", lease);
            closeQuietlyRecordingFailure(preparedScope, "local HLS package scope", lease);
        }
    }

    private void validateClaimedLeaseDestinationAndIdentity(ClaimedVideoPackagingLease lease) {
        try {
            if (!expectedOutputProviderId.equals(lease.storageProviderId())) {
                throw new IllegalArgumentException(
                        "Claimed storageProviderId mismatch: expected='" + expectedOutputProviderId.value()
                                + "', claimed='" + lease.storageProviderId().value() + "'."
                );
            }
            HlsPackageKeyValidator.validateTrustedDestination(
                    lease.storageProviderId(),
                    lease.storageBucketName(),
                    expectedOutputBucketName
            );
            HlsPackageKeyValidator.ParsedCanonicalPrefix parsed =
                    HlsPackageKeyValidator.validatePrefixMatchesIdentity(
                            lease.assetId(),
                            lease.sourceVersionNumber(),
                            lease.packageUuid(),
                            lease.basePrefix()
                    );
            if (!parsed.masterManifestKey().equals(lease.masterManifestKey())) {
                throw new IllegalArgumentException(
                        "Claimed masterManifestKey mismatch: expected='" + parsed.masterManifestKey().value()
                                + "', claimed='" + lease.masterManifestKey().value() + "'."
                );
            }
        } catch (IllegalArgumentException e) {
            throw new HlsPackagingOperationalException(e.getMessage(), e);
        }
    }

    private void validateResolvedSnapshotMatchesLease(
            ClaimedVideoPackagingLease lease,
            MediaVideoSourceSnapshot snapshot
    ) {
        if (snapshot == null) {
            throw new IllegalStateException("ResolveMediaVideoSourceSnapshotUseCase returned null snapshot.");
        }
        if (!lease.assetId().equals(snapshot.assetId())
                || !lease.sourceVersionId().equals(snapshot.sourceVersionId())
                || lease.sourceVersionNumber() != snapshot.sourceVersionNumber()) {
            throw new IllegalStateException(
                    "Resolved source snapshot identity does not match claimed lease for asset ["
                            + lease.assetId() + "]."
            );
        }
    }

    private void validateDownloadedSourceScope(
            MediaVideoSourceSnapshot snapshot,
            DownloadedSourceScope sourceScope
    ) {
        if (sourceScope == null || sourceScope.isClosed() || sourceScope.sourceFile() == null) {
            throw new IllegalStateException("Source download port returned null, closed, or fileless scope.");
        }
        MediaVideoSourceSnapshot returnedSnapshot = sourceScope.snapshot();
        if (returnedSnapshot == null
                || !snapshot.assetId().equals(returnedSnapshot.assetId())
                || !snapshot.sourceVersionId().equals(returnedSnapshot.sourceVersionId())
                || snapshot.sourceVersionNumber() != returnedSnapshot.sourceVersionNumber()) {
            throw new IllegalStateException("Downloaded source scope identity mismatch against requested snapshot.");
        }
        if (sourceScope.verifiedSizeBytes() != snapshot.expectedSizeBytes()) {
            throw new IllegalStateException(
                    "Downloaded source byte count (" + sourceScope.verifiedSizeBytes()
                            + ") mismatches expected snapshot size (" + snapshot.expectedSizeBytes() + ")."
            );
        }
        if (!snapshot.expectedContentHash().equals(sourceScope.verifiedSha256())) {
            throw new IllegalStateException("Downloaded source digest mismatches expected snapshot SHA-256.");
        }
    }

    private HlsExpectedArtifactManifest validatePreparedPackageScope(
            ClaimedVideoPackagingLease lease,
            PreparedHlsPackageScope preparedScope
    ) {
        if (preparedScope == null || preparedScope.isClosed()) {
            throw new IllegalStateException("Local packaging port returned null or already-closed scope.");
        }
        if (preparedScope.segmentDurationSeconds() != lease.segmentDurationSeconds()) {
            throw new IllegalStateException(
                    "Local packaged segmentDurationSeconds (" + preparedScope.segmentDurationSeconds()
                            + ") mismatches claimed lease (" + lease.segmentDurationSeconds() + ")."
            );
        }
        HlsExpectedArtifactManifest manifest = preparedScope.expectedManifest();
        if (manifest == null || preparedScope.artifactStreamSource() == null) {
            throw new IllegalStateException("Prepared local package scope missing manifest or artifact stream source.");
        }
        if (manifest.objectCount() != preparedScope.objectCount()
                || manifest.totalSizeBytes() != preparedScope.totalSizeBytes()) {
            throw new IllegalStateException("Prepared package scope summary counts mismatch expectedManifest.");
        }
        if (manifest.objectCount() > artifactLimits.maxObjectCount()) {
            throw new IllegalStateException(
                    "HLS artifact count (" + manifest.objectCount()
                            + ") exceeds configured limit (" + artifactLimits.maxObjectCount() + ")."
            );
        }
        for (HlsArtifactDescriptor descriptor : manifest.orderedArtifacts()) {
            if (descriptor.sizeBytes() > artifactLimits.maxSingleObjectBytes()) {
                throw new IllegalStateException(
                        "HLS artifact '" + descriptor.relativePath() + "' size (" + descriptor.sizeBytes()
                                + ") exceeds maxSingleObjectBytes (" + artifactLimits.maxSingleObjectBytes() + ")."
                );
            }
        }
        HlsPackageIdentity id = manifest.identity();
        if (!lease.assetId().equals(id.assetId())
                || lease.sourceVersionNumber() != id.sourceVersionNumber()
                || !lease.packageUuid().equals(id.packageUuid())
                || !lease.storageProviderId().equals(id.providerId())
                || !lease.basePrefix().equals(id.basePrefix())
                || !lease.masterManifestKey().equals(id.masterManifestKey())) {
            throw new IllegalStateException(
                    "Local HLS package manifest identity mismatches claimed lease (asset=" + lease.assetId()
                            + ", prefix=" + lease.basePrefix().value() + ")."
            );
        }
        return manifest;
    }

    private VerifiedHlsPackageReceipt validateRemoteVerificationResult(
            ClaimedVideoPackagingLease lease,
            HlsExpectedArtifactManifest expectedManifest,
            HlsUploadedPackageVerification verification
    ) {
        if (verification == null) {
            throw new IllegalStateException("Storage port returned null remote verification result.");
        }
        if (!verification.exactPrefixKeySetMatched()) {
            throw new IllegalStateException("Remote verification did not confirm exact prefix key set match.");
        }
        if (verification.integrityProof() == null) {
            throw new IllegalStateException("Remote verification missing HlsRemoteIntegrityProof.");
        }
        if (!expectedManifest.identity().equals(verification.identity())) {
            throw new IllegalStateException("Remote verification identity mismatches expected manifest.");
        }
        if (!lease.storageBucketName().trim().equals(verification.verifiedBucketName())) {
            throw new IllegalStateException(
                    "Remote verification bucket '" + verification.verifiedBucketName()
                            + "' mismatches claimed bucket '" + lease.storageBucketName() + "'."
            );
        }
        if (verification.verifiedObjectCount() != expectedManifest.objectCount()
                || verification.verifiedTotalBytes() != expectedManifest.totalSizeBytes()) {
            throw new IllegalStateException("Remote verification counts mismatch expected manifest.");
        }
        VerifiedHlsPackageReceipt receipt = verification.remoteReceipt();
        if (receipt == null) {
            throw new IllegalStateException("Remote verification returned null VerifiedHlsPackageReceipt.");
        }
        if (!expectedManifest.masterManifestSha256().equals(receipt.masterManifestSha256())
                || receipt.objectCount() != expectedManifest.objectCount()
                || receipt.totalSizeBytes() != expectedManifest.totalSizeBytes()) {
            throw new IllegalStateException("Remote VerifiedHlsPackageReceipt mismatches expected manifest.");
        }
        return receipt;
    }

    private Throwable closeQuietlyRecordingFailure(
            AutoCloseable resource,
            String label,
            ClaimedVideoPackagingLease lease
    ) {
        if (resource == null) {
            return null;
        }
        try {
            resource.close();
            return null;
        } catch (Throwable ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Failed closing {} for job [{}], package [{}]: {}",
                    label, lease.jobId(), lease.packageId(), ex.getMessage());
            return ex;
        }
    }

    private static Throwable combineFailures(Throwable primary, Throwable secondary) {
        if (primary == null) {
            return secondary;
        }
        if (secondary != null && primary != secondary) {
            primary.addSuppressed(secondary);
        }
        return primary;
    }
}
