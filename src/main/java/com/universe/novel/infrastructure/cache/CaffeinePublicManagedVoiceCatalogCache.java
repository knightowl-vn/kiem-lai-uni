package com.universe.novel.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.universe.novel.application.ports.PublicManagedVoiceCatalogCachePort;
import com.universe.novel.contracts.dto.narration.PublicManagedVoiceCatalogDTO;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Bounded, process-local cache for the single public Managed voice catalog.
 *
 * <p>Each in-flight load captures the current generation. Invalidation advances that
 * generation, and population is allowed only when the captured generation is still
 * current. Readers in the same generation share one in-flight result.</p>
 */
@Component
public class CaffeinePublicManagedVoiceCatalogCache
        implements PublicManagedVoiceCatalogCachePort {

    private static final long INITIAL_GENERATION = 0L;
    private static final Duration EXPIRATION = Duration.ofMinutes(10);

    private static final CatalogKey CATALOG_KEY = new CatalogKey();

    private final Cache<CatalogKey, VersionedCatalog> cache;
    private final AtomicLong generation = new AtomicLong(INITIAL_GENERATION);
    private final AtomicReference<InFlightLoad> inFlightLoad = new AtomicReference<>();
    private final Object populationLock = new Object();

    public CaffeinePublicManagedVoiceCatalogCache() {
        this.cache = Caffeine.newBuilder()
                .maximumSize(1L)
                .expireAfterWrite(EXPIRATION)
                .build();
    }

    @Override
    public PublicManagedVoiceCatalogDTO getOrLoad(
            Supplier<PublicManagedVoiceCatalogDTO> loader
    ) {
        Objects.requireNonNull(loader, "loader must not be null");

        while (true) {
            long requestedGeneration = generation.get();
            VersionedCatalog cached = cache.getIfPresent(CATALOG_KEY);
            if (cached != null && cached.generation() == requestedGeneration) {
                return cached.catalog();
            }

            InFlightLoad observedLoad = inFlightLoad.get();
            if (observedLoad != null
                    && observedLoad.generation() == requestedGeneration) {
                return await(observedLoad.result());
            }

            InFlightLoad newLoad = new InFlightLoad(
                    requestedGeneration,
                    new CompletableFuture<>()
            );
            if (!inFlightLoad.compareAndSet(observedLoad, newLoad)) {
                continue;
            }

            try {
                PublicManagedVoiceCatalogDTO loaded = Objects.requireNonNull(
                        loader.get(),
                        "catalog loader result must not be null"
                );
                newLoad.result().complete(loaded);

                synchronized (populationLock) {
                    if (generation.get() == requestedGeneration) {
                        cache.put(
                                CATALOG_KEY,
                                new VersionedCatalog(requestedGeneration, loaded)
                        );
                    }
                }
                return loaded;
            } catch (RuntimeException | Error failure) {
                newLoad.result().completeExceptionally(failure);
                throw failure;
            } finally {
                inFlightLoad.compareAndSet(newLoad, null);
            }
        }
    }

    @Override
    public void invalidate() {
        synchronized (populationLock) {
            generation.incrementAndGet();
            cache.invalidate(CATALOG_KEY);
        }
    }

    private PublicManagedVoiceCatalogDTO await(
            CompletableFuture<PublicManagedVoiceCatalogDTO> result
    ) {
        try {
            return result.join();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (failure.getCause() instanceof Error error) {
                throw error;
            }
            throw failure;
        }
    }

    static final class CatalogKey {
        private CatalogKey() {
        }
    }

    record VersionedCatalog(
            long generation,
            PublicManagedVoiceCatalogDTO catalog
    ) {
        VersionedCatalog {
            Objects.requireNonNull(catalog, "catalog must not be null");
        }
    }

    private record InFlightLoad(
            long generation,
            CompletableFuture<PublicManagedVoiceCatalogDTO> result
    ) {
    }
}
