package com.universe.novel.application.ports;

import com.universe.novel.contracts.dto.narration.PublicManagedVoiceCatalogDTO;

import java.util.function.Supplier;

/**
 * Process-local cache boundary for the singleton public Managed voice catalog.
 */
public interface PublicManagedVoiceCatalogCachePort {

    PublicManagedVoiceCatalogDTO getOrLoad(
            Supplier<PublicManagedVoiceCatalogDTO> loader
    );

    void invalidate();
}
