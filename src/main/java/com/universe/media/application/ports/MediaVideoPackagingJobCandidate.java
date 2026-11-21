package com.universe.media.application.ports;

import java.util.UUID;

public record MediaVideoPackagingJobCandidate(
        UUID jobId,
        UUID assetId
) {
}
