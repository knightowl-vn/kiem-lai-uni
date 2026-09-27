package com.universe.media.application.asset;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Application-owned query for active media assets matching an opaque client tag using keyset pagination.
 *
 * <p>Enforces application-level boundary invariants independently of presentation or external contracts.
 */
public record FindActiveMediaAssetsKeysetQuery(
        String clientTag,
        Instant createdBeforeUpperBound,
        Instant lastCreatedAt,
        UUID lastAssetId,
        int pageSize
) {

    public static final int MAX_PAGE_SIZE = 100;

    public FindActiveMediaAssetsKeysetQuery {
        Objects.requireNonNull(
                clientTag,
                "Client tag cannot be null."
        );
        if (clientTag.isBlank()) {
            throw new IllegalArgumentException(
                    "Client tag cannot be blank."
            );
        }
        if (clientTag.length() > 64) {
            throw new IllegalArgumentException(
                    "Client tag cannot exceed 64 characters."
            );
        }

        Objects.requireNonNull(
                createdBeforeUpperBound,
                "Created before upper bound cannot be null."
        );

        if (pageSize <= 0) {
            throw new IllegalArgumentException(
                    "Page size must be greater than zero: " + pageSize
            );
        }
        if (pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "Page size cannot exceed " + MAX_PAGE_SIZE + ": " + pageSize
            );
        }

        if ((lastCreatedAt == null && lastAssetId != null)
                || (lastCreatedAt != null && lastAssetId == null)) {
            throw new IllegalArgumentException(
                    "Cursor requires both lastCreatedAt and lastAssetId to be present, or both to be null."
            );
        }
    }

    public static FindActiveMediaAssetsKeysetQuery firstPage(
            String clientTag,
            Instant createdBeforeUpperBound,
            int pageSize
    ) {
        return new FindActiveMediaAssetsKeysetQuery(
                clientTag,
                createdBeforeUpperBound,
                null,
                null,
                pageSize
        );
    }

    public static FindActiveMediaAssetsKeysetQuery nextPage(
            String clientTag,
            Instant createdBeforeUpperBound,
            Instant lastCreatedAt,
            UUID lastAssetId,
            int pageSize
    ) {
        return new FindActiveMediaAssetsKeysetQuery(
                clientTag,
                createdBeforeUpperBound,
                lastCreatedAt,
                lastAssetId,
                pageSize
        );
    }
}
