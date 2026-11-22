package com.universe.media.application.hls;

import com.universe.shared.exceptions.BaseApplicationException;

/**
 * Thrown when HLS packaging cannot proceed due to missing deployment prerequisites,
 * invalid bucket/provider configuration, or missing host executables.
 */
public class HlsPackagingOperationalException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public HlsPackagingOperationalException(String message, Throwable cause) {
        super("HLS_PACKAGING_OPERATIONAL_BLOCKED", message, cause);
    }

    public HlsPackagingOperationalException(String message) {
        super("HLS_PACKAGING_OPERATIONAL_BLOCKED", message);
    }
}
