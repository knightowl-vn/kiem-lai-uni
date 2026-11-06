package com.universe.screen.application.ports;

import com.universe.screen.application.dto.ScreenPlaybackDeliveryInfo;

import java.util.Optional;
import java.util.UUID;

/**
 * Screen-owned outbound port for resolving public media playback delivery capabilities.
 *
 * <p>Preserves bounded-context isolation: relies exclusively on standard Java types and
 * application DTOs, without leaking Media module internal storage adapters or persistence models.
 */
public interface ScreenVideoDeliveryPort {

    /**
     * Resolves public playback eligibility and canonical delivery reference for a video media asset.
     *
     * <p>Returns {@link ScreenPlaybackDeliveryInfo} only if the referenced asset exists,
     * has authoritative status ACTIVE, visibility PUBLIC, type VIDEO, and an active binary version.
     * If any condition is not met (e.g. missing, deleted, archived, private, or non-video),
     * returns {@link Optional#empty()}.
     *
     * @param videoMediaAssetId scalar UUID of the media asset
     * @return Optional containing delivery info if playable, empty otherwise
     */
    Optional<ScreenPlaybackDeliveryInfo> resolvePlaybackDelivery(UUID videoMediaAssetId);
}
