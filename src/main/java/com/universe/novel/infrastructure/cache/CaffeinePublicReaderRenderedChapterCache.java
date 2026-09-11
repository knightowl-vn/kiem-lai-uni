package com.universe.novel.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.universe.novel.application.ports.PublicReaderRenderedChapterCachePort;
import com.universe.novel.application.ports.PublicReaderRenderedChapterLoadResult;
import com.universe.novel.contracts.dto.reader.ReaderChapterRenderedSnapshotDTO;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@Component
public class CaffeinePublicReaderRenderedChapterCache implements PublicReaderRenderedChapterCachePort {

    private static final long INITIAL_GENERATION = 0L;
    private static final Duration EXPIRATION = Duration.ofMinutes(10);
    private static final long MAXIMUM_CHAPTERS = 2000L;

    private final Cache<String, ChapterState> stateCache;
    private final Cache<String, VersionedSnapshot> dataCache;

    public CaffeinePublicReaderRenderedChapterCache() {
        this.stateCache = Caffeine.newBuilder()
                .maximumSize(MAXIMUM_CHAPTERS)
                .expireAfterAccess(EXPIRATION)
                .build();

        this.dataCache = Caffeine.newBuilder()
                .maximumSize(MAXIMUM_CHAPTERS)
                .expireAfterWrite(EXPIRATION)
                .build();
    }

    private void validateKey(String slug) {
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("slug must not be null or blank");
        }
    }

    @Override
    public ReaderChapterRenderedSnapshotDTO getOrLoad(String normalizedSlug, Supplier<PublicReaderRenderedChapterLoadResult> loader) {
        validateKey(normalizedSlug);
        Objects.requireNonNull(loader, "loader must not be null");

        while (true) {
            ChapterState state = stateCache.get(normalizedSlug, k -> new ChapterState());
            long requestedGeneration = state.generation.get();

            VersionedSnapshot cached = dataCache.getIfPresent(normalizedSlug);
            if (cached != null && cached.state() == state && cached.generation() == requestedGeneration) {
                return cached.snapshot();
            }

            InFlightLoad observedLoad = state.inFlightLoad.get();
            if (observedLoad != null && observedLoad.generation() == requestedGeneration) {
                return await(observedLoad.result());
            }

            InFlightLoad newLoad = new InFlightLoad(requestedGeneration, new CompletableFuture<>());
            if (!state.inFlightLoad.compareAndSet(observedLoad, newLoad)) {
                continue;
            }

            try {
                PublicReaderRenderedChapterLoadResult rawLoaded = Objects.requireNonNull(
                        loader.get(),
                        "rendered chapter loader result must not be null"
                );

                ReaderChapterRenderedSnapshotDTO snapshot = Objects.requireNonNull(
                        rawLoaded.snapshot(),
                        "snapshot inside load result must not be null"
                );

                newLoad.result().complete(snapshot);

                if (rawLoaded.cacheable()) {
                    synchronized (state.populationLock) {
                        if (state.generation.get() == requestedGeneration
                                && stateCache.getIfPresent(normalizedSlug) == state) {
                            dataCache.put(
                                    normalizedSlug,
                                    new VersionedSnapshot(state, requestedGeneration, snapshot)
                            );
                        }
                    }
                }

                return snapshot;
            } catch (RuntimeException | Error failure) {
                newLoad.result().completeExceptionally(failure);
                throw failure;
            } finally {
                state.inFlightLoad.compareAndSet(newLoad, null);
            }
        }
    }

    @Override
    public void invalidate(String normalizedSlug) {
        validateKey(normalizedSlug);

        ChapterState state = stateCache.getIfPresent(normalizedSlug);
        if (state != null) {
            synchronized (state.populationLock) {
                state.generation.incrementAndGet();
                dataCache.invalidate(normalizedSlug);
            }
        } else {
            dataCache.invalidate(normalizedSlug);
        }
    }

    private ReaderChapterRenderedSnapshotDTO await(CompletableFuture<ReaderChapterRenderedSnapshotDTO> result) {
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

    private static class ChapterState {
        final AtomicLong generation = new AtomicLong(INITIAL_GENERATION);
        final AtomicReference<InFlightLoad> inFlightLoad = new AtomicReference<>();
        final Object populationLock = new Object();
    }

    private record VersionedSnapshot(
            ChapterState state,
            long generation,
            ReaderChapterRenderedSnapshotDTO snapshot
    ) {
        VersionedSnapshot {
            Objects.requireNonNull(state, "state must not be null");
            Objects.requireNonNull(snapshot, "snapshot must not be null");
        }
    }

    private record InFlightLoad(
            long generation,
            CompletableFuture<ReaderChapterRenderedSnapshotDTO> result
    ) {}
}
