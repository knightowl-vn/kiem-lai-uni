package com.universe.wiki.application.image;

public record WikiImageCleanupResult(
        int candidates,
        int deleted,
        int failed,
        boolean dryRun
) {
}