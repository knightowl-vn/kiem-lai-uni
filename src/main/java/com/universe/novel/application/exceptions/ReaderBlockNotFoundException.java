package com.universe.novel.application.exceptions;

import java.util.UUID;

/**
 * Exception thrown when a requested canonical Reader blockKey does not exist in the
 * current chapter content snapshot.
 */
public class ReaderBlockNotFoundException extends RuntimeException {

    private final UUID chapterId;
    private final String blockKey;

    public ReaderBlockNotFoundException(UUID chapterId, String blockKey) {
        super("Canonical block '" + blockKey + "' not found in chapter " + chapterId);
        this.chapterId = chapterId;
        this.blockKey = blockKey;
    }

    public UUID getChapterId() {
        return chapterId;
    }

    public String getBlockKey() {
        return blockKey;
    }
}
