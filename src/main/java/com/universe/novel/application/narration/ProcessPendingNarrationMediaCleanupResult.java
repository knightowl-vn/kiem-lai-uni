package com.universe.novel.application.narration;

import java.time.Instant;

/**
 * Passive result record containing execution counters for a narration media cleanup batch run (MS-04.9H.8D1B).
 */
public record ProcessPendingNarrationMediaCleanupResult(
        int candidates,
        int cleaned,
        int failed,
        Instant executedAt
) {
}
