package com.universe.screen.application.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Input query for discovering recoverable Screen video upload sessions.
 */
public record FindRecoverableScreenVideoUploadsQuery(
        String authenticatedActor,
        UUID targetEntryId,
        int page,
        int size
) {
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 50;

    public FindRecoverableScreenVideoUploadsQuery {
        Objects.requireNonNull(authenticatedActor, "Authenticated actor cannot be null.");
        if (authenticatedActor.isBlank()) {
            throw new IllegalArgumentException("Authenticated actor cannot be blank.");
        }
        if (page < 0) {
            page = 0;
        }
        if (size <= 0) {
            size = DEFAULT_PAGE_SIZE;
        } else if (size > MAX_PAGE_SIZE) {
            size = MAX_PAGE_SIZE;
        }
    }

    public static FindRecoverableScreenVideoUploadsQuery of(String authenticatedActor, UUID targetEntryId, Integer page, Integer size) {
        int effectivePage = (page != null && page >= 0) ? page : 0;
        int effectiveSize = (size != null && size > 0) ? Math.min(size, MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;
        return new FindRecoverableScreenVideoUploadsQuery(authenticatedActor, targetEntryId, effectivePage, effectiveSize);
    }
}
