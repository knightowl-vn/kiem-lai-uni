package com.universe.media.application.ports.storage;

/**
 * Classification of remote verification proof recorded by an {@link HlsPackageArtifactStoragePort}
 * implementation in HLS-2B2B.
 *
 * <p><strong>Provenance Notice:</strong> Selecting an enum constant is only structural metadata;
 * actual cryptographic/remote provenance requires executing the checks inside a trusted remote
 * {@link HlsPackageArtifactStoragePort} adapter against object storage before handing a
 * {@code VerifiedHlsPackageReceipt} to HLS-2B3 job completion.
 */
public enum HlsRemoteIntegrityProof {
    STREAMED_GET_MANIFESTS_AND_INITS_WITH_EXACT_LISTING,
    STREAMED_GET_ALL_ARTIFACTS_WITH_EXACT_LISTING
}
