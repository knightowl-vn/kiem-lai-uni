package com.universe.media.contracts.dto;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Keyset discovery query for active media assets matching an opaque client tag.
 *
 * <p>Enforces deterministic keyset traversal ordered by {@code (created_at ASC, id ASC)}.
 *
 * <p>Validation rules:
 * <ul>
 *     <li>{@code clientTag}: non-null, non-blank, max 64 characters</li>
 *     <li>{@code createdBeforeUpperBound}: non-null inclusive upper boundary</li>
 *     <li>{@code pageSize}: must be greater than 0 and not exceed {@value #MAX_PAGE_SIZE}</li>
 *     <li>Cursor: either both {@code lastCreatedAt} and {@code lastAssetId} are null (first page)
 *         or both are non-null (subsequent page). Partial cursor state is rejected.</li>
 * </ul>
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
