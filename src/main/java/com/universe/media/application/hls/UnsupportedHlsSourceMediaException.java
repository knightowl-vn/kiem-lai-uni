package com.universe.media.application.hls;

import com.universe.shared.exceptions.BaseApplicationException;

/**
 * Thrown by {@link HlsLocalPackagingPort} when an immutable source MP4 has unsupported media
 * characteristics (e.g., 10-bit/HDR signaling or invalid display dimensions) that cannot succeed
 * upon retry.
 */
public class UnsupportedHlsSourceMediaException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public UnsupportedHlsSourceMediaException(String message, Throwable cause) {
        super("UNSUPPORTED_HLS_SOURCE_MEDIA", message, cause);
    }

    public UnsupportedHlsSourceMediaException(String message) {
        super("UNSUPPORTED_HLS_SOURCE_MEDIA", message);
    }
}
