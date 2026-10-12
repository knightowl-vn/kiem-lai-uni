package com.universe.media.application.ports.storage;

/**
 * Observed summary of uploaded parts for a specific multipart upload ID.
 * <p>
 * Epistemic boundaries:
 * <ul>
 *     <li>{@link #PARTS_RETURNED}: Specific uploaded parts were observed.</li>
 *     <li>{@link #ZERO_PARTS_OBSERVED}: An active upload yielded zero completed parts in the listing.</li>
 *     <li>{@link #NO_SUCH_UPLOAD}: Provider returned NoSuchUpload.
 *         <b>NO_SUCH_UPLOAD must NOT be converted to ABORTED or CLEANED_UP.</b></li>
 *     <li>{@link #UNKNOWN}: Timeout, access denial, incomplete pagination budget, or other failure.</li>
 * </ul>
 */
public enum MultipartPartsSummary {
    PARTS_RETURNED,
    ZERO_PARTS_OBSERVED,
    NO_SUCH_UPLOAD,
    UNKNOWN
}
