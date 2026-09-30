package com.universe.interaction.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

/**
 * Thrown when post-deletion interaction cleanup fails to completely purge residual active interaction graph
 * within the configured bounded settlement passes.
 */
public class IncompletePostInteractionCleanupException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public IncompletePostInteractionCleanupException(UUID postId, String message) {
        super(
                "INCOMPLETE_POST_INTERACTION_CLEANUP",
                "Incomplete interaction cleanup for post " + postId + ": " + message
        );
    }
}
