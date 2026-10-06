package com.universe.screen.domain;

/**
 * Publication lifecycle status of a Screen catalog title.
 *
 * <p>V1 supported publication statuses:
 * <ul>
 *   <li>{@link #DRAFT} — Draft under editing; invisible to public catalog.</li>
 *   <li>{@link #PUBLISHED} — Authoritatively published; visible in public catalog.</li>
 *   <li>{@link #ARCHIVED} — Archived catalog title; withdrawn from active catalog.</li>
 * </ul>
 */
public enum ScreenPublicationStatus {
    /**
     * Draft under editing; invisible to public catalog.
     */
    DRAFT,

    /**
     * Authoritatively published; visible in public catalog.
     */
    PUBLISHED,

    /**
     * Archived catalog title; withdrawn from active catalog.
     */
    ARCHIVED
}
