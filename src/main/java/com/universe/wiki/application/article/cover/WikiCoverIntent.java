package com.universe.wiki.application.article.cover;

/**
 * Explicit cover modification intent for Wiki article editorial operations (MS-05G8C5.2).
 */
public enum WikiCoverIntent {
    PRESERVE,
    REMOVE,
    FOCAL_ONLY,
    REPLACE_EXISTING_BINARY,
    ATTACH_NEW_ASSET
}
