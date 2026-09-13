package com.universe.novel.infrastructure.cache;

import com.universe.novel.contracts.dto.narration.PublicManagedVoiceCatalogDTO;
import com.universe.novel.contracts.dto.narration.PublicNarrationVoiceDTO;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaffeinePublicManagedVoiceCatalogCacheTest {

    @Test
    void coldMissLoadsAndWarmHitReusesCatalog() {
        CaffeinePublicManagedVoiceCatalogCache cache =
                new CaffeinePublicManagedVoiceCatalogCache();
        AtomicInteger loads = new AtomicInteger();
        PublicManagedVoiceCatalogDTO catalog = catalog("voice-a");

        assertThat(cache.getOrLoad(() -> {
            loads.incrementAndGet();
            return catalog;
        })).isSameAs(catalog);
        assertThat(cache.getOrLoad(() -> {
            loads.incrementAndGet();
            return catalog("unexpected");
        })).isSameAs(catalog);

        assertThat(loads).hasValue(1);
    }

    @Test
    void emptySuccessfulCatalogIsCached() {
        CaffeinePublicManagedVoiceCatalogCache cache =
                new CaffeinePublicManagedVoiceCatalogCache();
        AtomicInteger loads = new AtomicInteger();
        PublicManagedVoiceCatalogDTO empty = new PublicManagedVoiceCatalogDTO(List.of());

        assertThat(cache.getOrLoad(() -> {
            loads.incrementAndGet();
            return empty;
        }).voices()).isEmpty();
        assertThat(cache.getOrLoad(() -> {
            loads.incrementAndGet();
            return catalog("unexpected");
        }).voices()).isEmpty();

        assertThat(loads).hasValue(1);
    }

    @Test
    void failedLoadIsNotCachedAndNextRequestCanRetry() {
        CaffeinePublicManagedVoiceCatalogCache cache =
                new CaffeinePublicManagedVoiceCatalogCache();
        AtomicInteger loads = new AtomicInteger();

        assertThatThrownBy(() -> cache.getOrLoad(() -> {
            loads.incrementAndGet();
            throw new IllegalStateException("catalog unavailable");
        })).isInstanceOf(IllegalStateException.class);

        PublicManagedVoiceCatalogDTO recovered = cache.getOrLoad(() -> {
            loads.incrementAndGet();
            return catalog("recovered");
        });

        assertThat(recovered.voices()).extracting(PublicNarrationVoiceDTO::voiceKey)
                .containsExactly("recovered");
        assertThat(loads).hasValue(2);
    }

    @Test
    void concurrentMissesForTheSameGenerationShareOneLoad() throws Exception {
        CaffeinePublicManagedVoiceCatalogCache cache =
                new CaffeinePublicManagedVoiceCatalogCache();
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch callersReady = new CountDownLatch(6);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(6);

        try {
            List<Future<PublicManagedVoiceCatalogDTO>> results = new ArrayList<>();
            for (int index = 0; index < 6; index++) {
                results.add(executor.submit(() -> {
                    callersReady.countDown();
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    return cache.getOrLoad(() -> {
                        loads.incrementAndGet();
                        loaderStarted.countDown();
                        await(releaseLoader);
                        return catalog("shared");
                    });
                }));
            }

            assertThat(callersReady.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(loaderStarted.await(5, TimeUnit.SECONDS)).isTrue();
            releaseLoader.countDown();

            for (Future<PublicManagedVoiceCatalogDTO> result : results) {
                assertThat(result.get(5, TimeUnit.SECONDS).voices())
                        .extracting(PublicNarrationVoiceDTO::voiceKey)
                        .containsExactly("shared");
            }
            assertThat(loads).hasValue(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void invalidationPreventsOlderInFlightLoadFromRepopulatingCache() throws Exception {
        CaffeinePublicManagedVoiceCatalogCache cache =
                new CaffeinePublicManagedVoiceCatalogCache();
        CountDownLatch oldLoadStarted = new CountDownLatch(1);
        CountDownLatch releaseOldLoad = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<PublicManagedVoiceCatalogDTO> oldResult = executor.submit(
                    () -> cache.getOrLoad(() -> {
                        oldLoadStarted.countDown();
                        await(releaseOldLoad);
                        return catalog("old");
                    })
            );

            assertThat(oldLoadStarted.await(5, TimeUnit.SECONDS)).isTrue();
            cache.invalidate();

            AtomicInteger freshLoads = new AtomicInteger();
            PublicManagedVoiceCatalogDTO fresh = cache.getOrLoad(() -> {
                freshLoads.incrementAndGet();
                return catalog("fresh");
            });
            releaseOldLoad.countDown();

            assertThat(oldResult.get(5, TimeUnit.SECONDS).voices())
                    .extracting(PublicNarrationVoiceDTO::voiceKey)
                    .containsExactly("old");
            assertThat(fresh.voices())
                    .extracting(PublicNarrationVoiceDTO::voiceKey)
                    .containsExactly("fresh");
            assertThat(cache.getOrLoad(() -> {
                freshLoads.incrementAndGet();
                return catalog("unexpected");
            })).isSameAs(fresh);
            assertThat(freshLoads).hasValue(1);
        } finally {
            releaseOldLoad.countDown();
            executor.shutdownNow();
        }
    }

    private static PublicManagedVoiceCatalogDTO catalog(String voiceKey) {
        return new PublicManagedVoiceCatalogDTO(List.of(
                new PublicNarrationVoiceDTO(voiceKey, voiceKey, false)
        ));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test coordination.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for test coordination.", exception);
        }
    }
}
