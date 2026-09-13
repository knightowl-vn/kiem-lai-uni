package com.universe.novel.application.narration;

import com.universe.novel.application.ports.PublicManagedVoiceCatalogCachePort;
import com.universe.novel.contracts.dto.narration.PublicManagedVoiceCatalogDTO;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class GetPublicManagedVoiceCatalogUseCase {

    private final PublicManagedVoiceCatalogCachePort catalogCache;
    private final LoadPublicManagedVoiceCatalogUseCase catalogLoader;

    public GetPublicManagedVoiceCatalogUseCase(
            PublicManagedVoiceCatalogCachePort catalogCache,
            LoadPublicManagedVoiceCatalogUseCase catalogLoader
    ) {
        this.catalogCache = Objects.requireNonNull(catalogCache, "catalogCache must not be null");
        this.catalogLoader = Objects.requireNonNull(catalogLoader, "catalogLoader must not be null");
    }

    public PublicManagedVoiceCatalogDTO execute(GetPublicManagedVoiceCatalogQuery query) {
        Objects.requireNonNull(query, "query must not be null");

        return catalogCache.getOrLoad(
                () -> catalogLoader.execute(query)
        );
    }
}
