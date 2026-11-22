package com.universe.media.application.hls;

import com.universe.shared.exceptions.BaseApplicationException;

/**
 * Thrown by {@link HlsLocalPackagingPort} when local FFmpeg/ffprobe execution, manifest
 * verification, or bounded artifact inventory fails.
 */
public class HlsLocalPackagingException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public HlsLocalPackagingException(String message, Throwable cause) {
        super("HLS_LOCAL_PACKAGING_FAILED", message, cause);
    }

    public HlsLocalPackagingException(String message) {
        super("HLS_LOCAL_PACKAGING_FAILED", message);
    }
}
