package com.universe.wiki.application.article.cover.backfill;

import java.util.Objects;

/**
 * Keyset query parameters for bounded, deterministic enumeration of distinct non-null
 * {@code cover_media_asset_id} values referenced by Wiki articles (MS-05G8C6).
 *
 * <p>Enforces deterministic database ordering by {@code cover_media_asset_id ASC} up to a
 * fixed run upper bound, completely eliminating OFFSET pagination and starvation.
 */
public record WikiReferencedCoverKeysetQuery(
        String lastAssetId,
        String runUpperBound,
        int pageSize
) {

    public static final int MAX_PAGE_SIZE = 100;

    public WikiReferencedCoverKeysetQuery {
        Objects.requireNonNull(runUpperBound, "runUpperBound cannot be null.");
        if (runUpperBound.isBlank()) {
            throw new IllegalArgumentException("runUpperBound cannot be blank.");
        }
        if (pageSize <= 0 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "pageSize must be between 1 and " + MAX_PAGE_SIZE + ", but found: " + pageSize
            );
        }
        if (lastAssetId != null && lastAssetId.isBlank()) {
            throw new IllegalArgumentException("lastAssetId cannot be blank when provided.");
        }
    }

    public static WikiReferencedCoverKeysetQuery firstPage(String runUpperBound, int pageSize) {
        return new WikiReferencedCoverKeysetQuery(null, runUpperBound, pageSize);
    }

    public static WikiReferencedCoverKeysetQuery nextPage(String lastAssetId, String runUpperBound, int pageSize) {
        Objects.requireNonNull(lastAssetId, "lastAssetId cannot be null for subsequent page.");
        return new WikiReferencedCoverKeysetQuery(lastAssetId, runUpperBound, pageSize);
    }
}
