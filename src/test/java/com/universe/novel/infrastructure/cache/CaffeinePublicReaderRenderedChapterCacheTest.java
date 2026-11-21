package com.universe.novel.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.universe.novel.application.ports.PublicReaderRenderedChapterLoadResult;
import com.universe.novel.contracts.dto.reader.ReaderChapterRenderedSnapshotDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaffeinePublicReaderRenderedChapterCacheTest {

    private CaffeinePublicReaderRenderedChapterCache cache;
    private static final String SLUG = "test-slug";

    @BeforeEach
    void setUp() {
        cache = new CaffeinePublicReaderRenderedChapterCache();
    }

    private ReaderChapterRenderedSnapshotDTO createSnapshot(String title, UUID narrationSegmentId) {
        String html = "<p data-narration-id=\"" + narrationSegmentId + "\">" + title + " content</p>";
        return new ReaderChapterRenderedSnapshotDTO(
                UUID.randomUUID(), 1, title, SLUG, html, 1L, null
        );
    }

    @Test
    void warmHit_bypassesLoader() {
        AtomicInteger calls = new AtomicInteger();
        UUID narrationId = UUID.randomUUID();
        Supplier<PublicReaderRenderedChapterLoadResult> loader = () -> {
            calls.incrementAndGet();
            return new PublicReaderRenderedChapterLoadResult(createSnapshot("First", narrationId), true);
        };

        ReaderChapterRenderedSnapshotDTO first = cache.getOrLoad(SLUG, loader);
        ReaderChapterRenderedSnapshotDTO second = cache.getOrLoad(SLUG, loader);

        assertThat(first.title()).isEqualTo("First");
        assertThat(second.title()).isEqualTo("First");
        assertThat(first.contentHtml()).contains(narrationId.toString());
        assertThat(first).isSameAs(second);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void cacheableFalse_returnsSnapshotButDoesNotCache() {
        AtomicInteger calls = new AtomicInteger();
        Supplier<PublicReaderRenderedChapterLoadResult> loader = () -> {
            calls.incrementAndGet();
            return new PublicReaderRenderedChapterLoadResult(createSnapshot("Title", UUID.randomUUID()), false);
        };

        ReaderChapterRenderedSnapshotDTO first = cache.getOrLoad(SLUG, loader);
        ReaderChapterRenderedSnapshotDTO second = cache.getOrLoad(SLUG, loader);

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void differentSlugs_remainIsolated() {
        AtomicInteger calls1 = new AtomicInteger();
        AtomicInteger calls2 = new AtomicInteger();

        ReaderChapterRenderedSnapshotDTO r1 = cache.getOrLoad("slug1", () -> {
            calls1.incrementAndGet();
            return new PublicReaderRenderedChapterLoadResult(createSnapshot("One", UUID.randomUUID()), true);
        });

        ReaderChapterRenderedSnapshotDTO r2 = cache.getOrLoad("slug2", () -> {
            calls2.incrementAndGet();
            return new PublicReaderRenderedChapterLoadResult(createSnapshot("Two", UUID.randomUUID()), true);
        });

        assertThat(calls1.get()).isEqualTo(1);
        assertThat(calls2.get()).isEqualTo(1);

        cache.getOrLoad("slug1", () -> {
            calls1.incrementAndGet();
            return new PublicReaderRenderedChapterLoadResult(createSnapshot("One", UUID.randomUUID()), true);
        });
        assertThat(calls1.get()).isEqualTo(1);
    }

    @Test
    void invalidatingA_forcesReloadOfAButBRemainsWarm() {
        AtomicInteger callsA = new AtomicInteger();
        AtomicInteger callsB = new AtomicInteger();

        cache.getOrLoad("slug-a", () -> {
            callsA.incrementAndGet();
            return new PublicReaderRenderedChapterLoadResult(createSnapshot("Title A", UUID.randomUUID()), true);
        });
        cache.getOrLoad("slug-b", () -> {
            callsB.incrementAndGet();
            return new PublicReaderRenderedChapterLoadResult(createSnapshot("Title B", UUID.randomUUID()), true);
        });
        assertThat(callsA.get()).isEqualTo(1);
        assertThat(callsB.get()).isEqualTo(1);

        cache.invalidate("slug-a");

        cache.getOrLoad("slug-b", () -> {
            callsB.incrementAndGet();
            return new PublicReaderRenderedChapterLoadResult(createSnapshot("Title B", UUID.randomUUID()), true);
        });
        assertThat(callsB.get()).isEqualTo(1); // Still 1, warm hit

        cache.getOrLoad("slug-a", () -> {
            callsA.incrementAndGet();
            return new PublicReaderRenderedChapterLoadResult(createSnapshot("Title A", UUID.randomUUID()), true);
        });
        assertThat(callsA.get()).isEqualTo(2); // Miss for A, reloaded
    }

    @Test
    void loaderFailure_isNotCached() {
        Supplier<PublicReaderRenderedChapterLoadResult> failingLoader = () -> {
            throw new RuntimeException("Loader failed");
        };

        assertThatThrownBy(() -> cache.getOrLoad(SLUG, failingLoader))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Loader failed");

        AtomicInteger successCalls = new AtomicInteger();
        Supplier<PublicReaderRenderedChapterLoadResult> successLoader = () -> {
            successCalls.incrementAndGet();
            return new PublicReaderRenderedChapterLoadResult(createSnapshot("Success", UUID.randomUUID()), true);
        };

        ReaderChapterRenderedSnapshotDTO result = cache.getOrLoad(SLUG, successLoader);
        assertThat(result.title()).isEqualTo("Success");
        assertThat(successCalls.get()).isEqualTo(1);
    }

    @Test
    void sameKeyConcurrentMisses_coalesceDeterministically() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        int threads = 3;
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch inLoaderLatch = new CountDownLatch(1);
        CountDownLatch releaseLoaderLatch = new CountDownLatch(1);

        ReaderChapterRenderedSnapshotDTO snapshot = createSnapshot("Concurrent", UUID.randomUUID());

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<CompletableFuture<ReaderChapterRenderedSnapshotDTO>> futures = new ArrayList<>();

            for (int i = 0; i < threads; i++) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    readyLatch.countDown();
                    try {
                        assertThat(startGate.await(5, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted while waiting for start gate", e);
                    }
                    return cache.getOrLoad(SLUG, () -> {
                        calls.incrementAndGet();
                        inLoaderLatch.countDown();
                        try {
                            assertThat(releaseLoaderLatch.await(5, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Interrupted while holding loader", e);
                        }
                        return new PublicReaderRenderedChapterLoadResult(snapshot, true);
                    });
                }, executor).orTimeout(5, TimeUnit.SECONDS));
            }

            assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
            startGate.countDown();
            assertThat(inLoaderLatch.await(5, TimeUnit.SECONDS)).isTrue();
            releaseLoaderLatch.countDown();

            for (CompletableFuture<ReaderChapterRenderedSnapshotDTO> f : futures) {
                ReaderChapterRenderedSnapshotDTO res = f.join();
                assertThat(res).isSameAs(snapshot);
            }

            assertThat(calls).hasValue(1);
        } finally {
            shutdownAndAwait(executor);
        }
    }

    @Test
    void staleOldInFlightLoad_cannotSurviveInvalidateAndNewLoadRace() throws InterruptedException {
        UUID oldNarrationId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID newNarrationId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        String oldHtml = "<p data-narration-id=\"" + oldNarrationId + "\">Old content</p>";
        String newHtml = "<p data-narration-id=\"" + newNarrationId + "\">New content</p>";

        ReaderChapterRenderedSnapshotDTO oldSnapshot = new ReaderChapterRenderedSnapshotDTO(
                UUID.randomUUID(), 1, "Old Title", SLUG, oldHtml, 1L, null
        );
        ReaderChapterRenderedSnapshotDTO newSnapshot = new ReaderChapterRenderedSnapshotDTO(
                UUID.randomUUID(), 1, "New Title", SLUG, newHtml, 1L, null
        );

        CountDownLatch oldInLoader = new CountDownLatch(1);
        CountDownLatch releaseOldLoader = new CountDownLatch(1);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<ReaderChapterRenderedSnapshotDTO> oldFuture = CompletableFuture.supplyAsync(() ->
                    cache.getOrLoad(SLUG, () -> {
                        oldInLoader.countDown();
                        try {
                            assertThat(releaseOldLoader.await(5, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Interrupted while holding old loader", e);
                        }
                        return new PublicReaderRenderedChapterLoadResult(oldSnapshot, true);
                    }), executor
            ).orTimeout(5, TimeUnit.SECONDS);

            assertThat(oldInLoader.await(5, TimeUnit.SECONDS)).isTrue();

            // Old load is in flight. We invalidate.
            cache.invalidate(SLUG);

            // NEW load executes and completes
            ReaderChapterRenderedSnapshotDTO newResult = cache.getOrLoad(SLUG, () ->
                    new PublicReaderRenderedChapterLoadResult(newSnapshot, true)
            );
            assertThat(newResult).isSameAs(newSnapshot);
            assertThat(newResult.contentHtml()).isEqualTo(newHtml);

            // Release old load
            releaseOldLoader.countDown();
            ReaderChapterRenderedSnapshotDTO oldResult = oldFuture.join();
            assertThat(oldResult).isSameAs(oldSnapshot);

            // Final warm read returns NEW snapshot without invoking loader
            ReaderChapterRenderedSnapshotDTO finalWarmResult = cache.getOrLoad(SLUG, () -> {
                throw new AssertionError("Loader must not be invoked on warm cache hit");
            });
            assertThat(finalWarmResult).isSameAs(newSnapshot);
            assertThat(finalWarmResult.contentHtml()).isEqualTo(newHtml);
        } finally {
            shutdownAndAwait(executor);
        }
    }

    @Test
    void rejectsNullOrBlankSlugKeys() {
        assertThatThrownBy(() -> cache.getOrLoad(null, () -> new PublicReaderRenderedChapterLoadResult(createSnapshot("T", UUID.randomUUID()), true)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cache.getOrLoad("", () -> new PublicReaderRenderedChapterLoadResult(createSnapshot("T", UUID.randomUUID()), true)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cache.getOrLoad("   ", () -> new PublicReaderRenderedChapterLoadResult(createSnapshot("T", UUID.randomUUID()), true)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> cache.invalidate(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cache.invalidate(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cache.invalidate("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void coordinationStateIsBounded() throws Exception {
        for (int i = 0; i < 2500; i++) {
            String slug = "slug-" + i;
            cache.getOrLoad(slug, () -> new PublicReaderRenderedChapterLoadResult(createSnapshot("Title " + slug, UUID.randomUUID()), true));
        }

        Field stateCacheField = CaffeinePublicReaderRenderedChapterCache.class.getDeclaredField("stateCache");
        stateCacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Cache<String, ?> stateCache = (Cache<String, ?>) stateCacheField.get(cache);
        stateCache.cleanUp();

        assertThat(stateCache.estimatedSize()).isLessThanOrEqualTo(2000L);
    }

    @Test
    void repeatedConcurrentMissBursts_neverInvokeLoaderMoreThanOncePerSlug() throws InterruptedException {
        int rounds = 50;
        int threads = 6;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < rounds; round++) {
                String roundSlug = SLUG + "-burst-" + round;
                AtomicInteger calls = new AtomicInteger();
                CountDownLatch readyLatch = new CountDownLatch(threads);
                CountDownLatch startGate = new CountDownLatch(1);
                ReaderChapterRenderedSnapshotDTO snapshot = createSnapshot("Burst " + round, UUID.randomUUID());

                List<CompletableFuture<ReaderChapterRenderedSnapshotDTO>> futures = new ArrayList<>(threads);
                for (int t = 0; t < threads; t++) {
                    futures.add(CompletableFuture.supplyAsync(() -> {
                        readyLatch.countDown();
                        try {
                            assertThat(startGate.await(5, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Interrupted at burst start gate", e);
                        }
                        return cache.getOrLoad(roundSlug, () -> {
                            calls.incrementAndGet();
                            return new PublicReaderRenderedChapterLoadResult(snapshot, true);
                        });
                    }, executor).orTimeout(5, TimeUnit.SECONDS));
                }

                assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
                startGate.countDown();

                for (CompletableFuture<ReaderChapterRenderedSnapshotDTO> f : futures) {
                    assertThat(f.join()).isSameAs(snapshot);
                }

                assertThat(calls).hasValue(1);
                ReaderChapterRenderedSnapshotDTO warm = cache.getOrLoad(roundSlug, () -> {
                    throw new AssertionError("Warm cache hit after burst must not invoke loader");
                });
                assertThat(warm).isSameAs(snapshot);
            }
        } finally {
            shutdownAndAwait(executor);
        }
    }

    @Test
    void concurrentFailure_propagatesAndAllowsCleanRetry() throws InterruptedException {
        int threads = 3;
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch failureEntered = new CountDownLatch(1);
        CountDownLatch releaseFailure = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<CompletableFuture<ReaderChapterRenderedSnapshotDTO>> futures = new ArrayList<>(threads);
            for (int t = 0; t < threads; t++) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    readyLatch.countDown();
                    try {
                        assertThat(startGate.await(5, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted at failure start gate", e);
                    }
                    return cache.getOrLoad(SLUG, () -> {
                        calls.incrementAndGet();
                        failureEntered.countDown();
                        try {
                            assertThat(releaseFailure.await(5, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Interrupted while failing loader", e);
                        }
                        throw new IllegalStateException("Simulated loader boom");
                    });
                }, executor).orTimeout(5, TimeUnit.SECONDS));
            }

            assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
            startGate.countDown();
            assertThat(failureEntered.await(5, TimeUnit.SECONDS)).isTrue();
            releaseFailure.countDown();

            for (CompletableFuture<ReaderChapterRenderedSnapshotDTO> f : futures) {
                assertThatThrownBy(f::join)
                        .hasRootCauseInstanceOf(IllegalStateException.class)
                        .hasRootCauseMessage("Simulated loader boom");
            }
            int callsAfterFailure = calls.get();
            assertThat(callsAfterFailure).isBetween(1, threads);

            ReaderChapterRenderedSnapshotDTO recovered = createSnapshot("Recovered", UUID.randomUUID());
            ReaderChapterRenderedSnapshotDTO retryResult = cache.getOrLoad(SLUG, () -> {
                calls.incrementAndGet();
                return new PublicReaderRenderedChapterLoadResult(recovered, true);
            });
            assertThat(retryResult).isSameAs(recovered);
            assertThat(calls).hasValue(callsAfterFailure + 1);

            ReaderChapterRenderedSnapshotDTO warm = cache.getOrLoad(SLUG, () -> {
                throw new AssertionError("Warm cache hit after retry must not invoke loader");
            });
            assertThat(warm).isSameAs(recovered);
        } finally {
            shutdownAndAwait(executor);
        }
    }

    private static void shutdownAndAwait(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
