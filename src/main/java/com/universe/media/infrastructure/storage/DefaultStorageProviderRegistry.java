package com.universe.media.infrastructure.storage;

import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.application.ports.storage.StorageProviderResolverPort;
import com.universe.media.domain.StorageProviderId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Default infrastructure implementation of {@link StorageProviderResolverPort} that collects
 * all registered {@link BinaryStoragePort} Spring beans and resolves them fail-closed.
 */
@Component
public class DefaultStorageProviderRegistry implements StorageProviderResolverPort {

    private final Map<StorageProviderId, BinaryStoragePort> providers;

    @Autowired
    public DefaultStorageProviderRegistry(List<BinaryStoragePort> providerList) {
        Objects.requireNonNull(providerList, "Provider list cannot be null.");

        Map<StorageProviderId, BinaryStoragePort> map = new HashMap<>();
        for (BinaryStoragePort provider : providerList) {
            StorageProviderId id = provider.providerId();
            if (map.containsKey(id)) {
                throw new IllegalStateException(
                        "Duplicate BinaryStoragePort registration detected for provider ID: " + id.value()
                );
            }
            map.put(id, provider);
        }
        this.providers = Collections.unmodifiableMap(map);
    }

    @Override
    public BinaryStoragePort resolve(StorageProviderId providerId) {
        if (providerId == null) {
            throw new StorageException("StorageProviderId cannot be null.");
        }

        BinaryStoragePort port = providers.get(providerId);
        if (port == null) {
            throw new StorageException("Unsupported or unconfigured storage provider: " + providerId.value());
        }

        return port;
    }

    /**
     * Returns an unmodifiable view of all currently registered provider IDs.
     */
    public Map<StorageProviderId, BinaryStoragePort> registeredProviders() {
        return providers;
    }
}
