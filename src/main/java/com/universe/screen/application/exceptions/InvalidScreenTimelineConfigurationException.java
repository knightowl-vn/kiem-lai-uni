package com.universe.screen.application.exceptions;

/**
 * Trusted domain/application validation exception thrown when Screen Intro/Outro timeline
 * configuration is invalid (e.g., incomplete Intro pair, invalid timestamp order, Intro/Outro
 * overlap, or exceeding known entry duration).
 *
 * <p>Extends {@link IllegalArgumentException} for backward compatibility with domain validation
 * contracts while allowing Admin controllers to distinguish trusted user-facing timeline validation
 * messages from untrusted infrastructure diagnostics.
 */
public class InvalidScreenTimelineConfigurationException extends IllegalArgumentException {

    public InvalidScreenTimelineConfigurationException(String message) {
        super(message);
    }

    public InvalidScreenTimelineConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
