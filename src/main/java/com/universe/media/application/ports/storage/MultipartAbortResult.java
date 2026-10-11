package com.universe.media.application.ports.storage;

/**
 * Outcome of an abort operation on a remote multipart upload.
 * <p>
 * <b>Important Safety Boundaries:</b>
 * <ul>
 *     <li>{@link #ABORT_ACCEPTED} indicates the provider acknowledged the abort request. It does
 *     not prove that no previously completed object exists, that all in-flight transfers have
 *     halted, that all parts were reclaimed, or that local database records may be released.</li>
 *     <li>{@link #UPLOAD_ALREADY_ABSENT} indicates the provider reported the upload ID was not
 *     found (e.g. {@code NoSuchUpload}). It does not distinguish whether the upload was previously
 *     aborted, previously completed, or never existed, and must never be interpreted as proof that
 *     cleanup succeeded.</li>
 * </ul>
 */
public enum MultipartAbortResult {

    /**
     * Provider acknowledged the abort request.
     */
    ABORT_ACCEPTED,

    /**
     * Provider reported that the specified upload ID does not exist (e.g. {@code NoSuchUpload}).
     */
    UPLOAD_ALREADY_ABSENT;

    /**
     * Returns true if the provider acknowledged the abort request.
     */
    public boolean isAbortAccepted() {
        return this == ABORT_ACCEPTED;
    }

    /**
     * Returns true if the upload was already absent on the provider.
     */
    public boolean isUploadAlreadyAbsent() {
        return this == UPLOAD_ALREADY_ABSENT;
    }
}
