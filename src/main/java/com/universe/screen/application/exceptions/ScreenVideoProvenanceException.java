package com.universe.screen.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

/**
 * Thrown when a video media asset fails authoritative Screen provenance verification.
 */
public class ScreenVideoProvenanceException extends BaseApplicationException {

    public ScreenVideoProvenanceException(String message) {
        super("SCREEN_VIDEO_PROVENANCE_VIOLATION", message);
    }
}
