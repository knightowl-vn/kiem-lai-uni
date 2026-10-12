package com.universe.media.application.ports.storage;

/**
 * Observed presence state of a storage object in provider metadata queries.
 * <p>
 * Epistemic boundaries:
 * <ul>
 *     <li>{@link #PRESENT}: Object metadata was successfully returned by the provider at the observed time.
 *         <b>PRESENT must NOT be interpreted as proof that a specific multipart upload completed.</b></li>
 *     <li>{@link #NOT_FOUND}: Provider returned a verified object-not-found response at the observed time.</li>
 *     <li>{@link #UNKNOWN}: Timeout, access denial, network failure, or other provider uncertainty.</li>
 * </ul>
 */
public enum StorageObjectPresence {
    PRESENT,
    NOT_FOUND,
    UNKNOWN
}
