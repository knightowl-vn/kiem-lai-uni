package com.universe.screen.application.ports;

import java.util.UUID;

/**
 * Screen-owned outbound port for validating Media asset references.
 *
 * <p>Preserves bounded-context boundary by relying solely on standard Java scalar types
 * without leaking Media module internal types, storage adapters, or persistence models.
 */
public interface ScreenMediaValidationPort {

    /**
     * Validates that the specified media asset exists and is a valid image asset.
     *
     * <p>If {@code mediaAssetId} is null, validation succeeds as a no-op since poster
     * and backdrop references are optional.
     *
     * @param mediaAssetId the scalar UUID of the media asset to validate
     */
    void validateImageMediaAsset(UUID mediaAssetId);

    /**
     * Validates that the specified media asset exists and is a valid video asset.
     *
     * <p>If {@code mediaAssetId} is null, validation succeeds as a no-op since video
     * attachment references are optional until attached.
     *
     * @param mediaAssetId the scalar UUID of the media asset to validate
     */
    void validateVideoMediaAsset(UUID mediaAssetId);
}
