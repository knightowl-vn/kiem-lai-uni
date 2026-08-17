package com.universe.wiki.application.image;

public record WikiImageReferenceBackfillResult(
        long articlesScanned,
        long revisionsScanned
) {
}