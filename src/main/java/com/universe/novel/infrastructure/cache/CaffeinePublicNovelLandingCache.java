package com.universe.novel.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.universe.novel.application.ports.PublicNovelLandingCachePort;
import com.universe.novel.contracts.dto.reader.ReaderNovelLandingDTO;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@Component
public class CaffeinePublicNovelLandingCache implements PublicNovelLandingCachePort {

    private static final String CANONICAL_KEY = "NOVEL_LANDING";
    private static final long INITIAL_GENERATION = 0L;
    private static final Duration EXPIRATION = Duration.ofMinutes(10);

    private final AtomicLong generation = new AtomicLong(INITIAL_GENERATION);
    private final AtomicReference<InFlightLoad> inFlightLoad = new AtomicReference<>();
    private final Object populationLock = new Object();

    private final Cache<String, VersionedLanding> dataCache;

    public CaffeinePublicNovelLandingCache() {
        this.dataCache = Caffeine.newBuilder()
                .maximumSize(1)
                .expireAfterWrite(EXPIRATION)
                .build();
    }

    @Override
    public ReaderNovelLandingDTO getOrLoad(Supplier<ReaderNovelLandingDTO> loader) {
        Objects.requireNonNull(loader, "loader must not be null");

        while (true) {
            long requestedGeneration = generation.get();
            
            VersionedLanding cached = dataCache.getIfPresent(CANONICAL_KEY);
            if (cached != null && cached.generation() == requestedGeneration) {
                return cached.landing();
            }

            InFlightLoad observedLoad = inFlightLoad.get();
            if (observedLoad != null && observedLoad.generation() == requestedGeneration) {
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
                ReaderNovelLandingDTO rawLoaded = Objects.requireNonNull(
                        loader.get(),
                        "landing loader result must not be null"
                );
                
                ReaderNovelLandingDTO immutableLoaded = new ReaderNovelLandingDTO(
                        rawLoaded.novel(),
                        List.copyOf(rawLoaded.volumes()),
                        rawLoaded.firstChapter()
                );

                newLoad.result().complete(immutableLoaded);

                synchronized (populationLock) {
                    if (generation.get() == requestedGeneration) {
                        dataCache.put(
                                CANONICAL_KEY,
                                new VersionedLanding(requestedGeneration, immutableLoaded)
                        );
                    }
                }
                
                return immutableLoaded;
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
            dataCache.invalidate(CANONICAL_KEY);
        }
    }

    private ReaderNovelLandingDTO await(CompletableFuture<ReaderNovelLandingDTO> result) {
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

    private record VersionedLanding(
            long generation,
            ReaderNovelLandingDTO landing
    ) {
        VersionedLanding {
            Objects.requireNonNull(landing, "landing must not be null");
        }
    }

    private record InFlightLoad(
            long generation,
            CompletableFuture<ReaderNovelLandingDTO> result
    ) {}
}
