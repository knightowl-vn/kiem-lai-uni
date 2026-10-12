package com.universe.media.application.ports.storage;

/**
 * Observed presence state of a specific multipart upload in provider listings.
 * <p>
 * Epistemic boundaries:
 * <ul>
 *     <li>{@link #ACTIVE}: The exact bucket/key/uploadId is currently observable as active in provider listings.</li>
 *     <li>{@link #NOT_LISTED}: Complete, successful listing completed without the exact upload ID.
 *         <b>NOT_LISTED must NOT be interpreted as previously aborted.</b></li>
 *     <li>{@link #UNKNOWN}: Provider failure, insufficient visibility, incomplete pagination, or ambiguous response.</li>
 * </ul>
 */
public enum MultipartUploadPresence {
    ACTIVE,
    NOT_LISTED,
    UNKNOWN
}
