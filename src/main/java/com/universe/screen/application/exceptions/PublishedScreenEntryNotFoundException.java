package com.universe.screen.application.exceptions;

/**
 * Thrown when a requested Screen entry is not found, is not published (e.g. DRAFT or ARCHIVED),
 * or does not belong to the requested parent title.
 */
public class PublishedScreenEntryNotFoundException extends RuntimeException {

    private final String entrySlug;

    public PublishedScreenEntryNotFoundException(String entrySlug) {
        super("Published Screen entry not found: " + entrySlug);
        this.entrySlug = entrySlug;
    }

    public PublishedScreenEntryNotFoundException(String titleSlug, String entrySlug) {
        super("Published Screen entry not found: " + entrySlug + " for title: " + titleSlug);
        this.entrySlug = entrySlug;
    }

    public String getEntrySlug() {
        return entrySlug;
    }
}
