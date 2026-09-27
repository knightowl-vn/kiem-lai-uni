package com.universe.media.application.asset;

/**
 * Result of an authoritative locked duplicate probe (MS-05G9.1).
 *
 * <p>Indicates whether the uploaded content hash matches the authoritative
 * current version of the media asset while holding the database row lock.
 */
public record AuthoritativeDuplicateProbeResult(
        boolean isDuplicate,
        int currentVersionNumber
) {
    public static AuthoritativeDuplicateProbeResult duplicate(int currentVersionNumber) {
        return new AuthoritativeDuplicateProbeResult(true, currentVersionNumber);
    }

    public static AuthoritativeDuplicateProbeResult different(int currentVersionNumber) {
        return new AuthoritativeDuplicateProbeResult(false, currentVersionNumber);
    }
}
