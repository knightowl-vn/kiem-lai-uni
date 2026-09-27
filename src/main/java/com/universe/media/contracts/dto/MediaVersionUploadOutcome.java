package com.universe.media.contracts.dto;

/**
 * Outcome of a conditional media asset version upload (MS-05G9).
 *
 * <p>Consumer-neutral enumeration indicating whether an uploaded binary resulted
 * in a newly created version or was identical to the current authoritative version.
 */
public enum MediaVersionUploadOutcome {

    /**
     * The uploaded binary's SHA-256 digest matched the current version's content hash.
     * No storage write, version row creation, or variant generation occurred.
     */
    UNCHANGED,

    /**
     * The uploaded binary differed from the current version.
     * A new immutable {@code MediaAssetVersion} was persisted and storage written.
     */
    VERSION_CREATED
}
