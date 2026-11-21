package com.universe.media.application.ports.storage;

import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;

/**
 * Dedicated storage boundary for HLS package artifact upload, remote verification,
 * and validated prefix cleanup. Intentionally separated from {@link BinaryStoragePort}
 * to isolate private HLS buckets and prevent broad unrestricted prefix deletes.
 *
 * <p><strong>HLS-2B2B / HLS-2B3 Trust Handoff:</strong> Only the concrete remote adapter
 * implementing this port may produce {@link HlsUploadedPackageVerification} and its embedded
 * {@code VerifiedHlsPackageReceipt} after executing remote object listing and streamed GET digest
 * checks. Packaging workers in HLS-2B3 must obtain that receipt strictly through this port boundary
 * (never from local inventory alone) before calling {@code CompleteVerifiedHlsPackagingUseCase}.
 */
public interface HlsPackageArtifactStoragePort {

    /**
     * Uploads a locally verified HLS package under an immutable package prefix in deterministic stages
     * and returns a verified remote receipt only after independent remote verification succeeds.
     */
    HlsUploadedPackageVerification uploadAndVerifyPackage(HlsPackageUploadCommand command);

    /**
     * Independently verifies an already-uploaded remote HLS package without trusting local-only state.
     */
    HlsUploadedPackageVerification verifyRemotePackage(HlsRemotePackageVerificationCommand command);

    /**
     * Purges all HLS package objects under a validated immutable package prefix
     * (e.g. {@code objects/hls/<assetId>/v<ver>/<pkgUuid>/}).
     */
    void purgePackagePrefix(StorageProviderId providerId, String bucketName, StorageKey validatedBasePrefix);
}
