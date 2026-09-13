package com.universe.novel.infrastructure.cache;

import com.universe.novel.contracts.dto.reader.ReaderNovelLandingDTO;
import com.universe.novel.contracts.dto.reader.ReaderNovelOverviewDTO;
import com.universe.novel.contracts.dto.reader.ReaderVolumeListItemDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaffeinePublicNovelLandingCacheTest {

    private CaffeinePublicNovelLandingCache cache;

    @BeforeEach
    void setUp() {
        cache = new CaffeinePublicNovelLandingCache();
    }

    @Test
    void coldMiss_invokesLoaderOnce() {
        AtomicInteger calls = new AtomicInteger();
        ReaderNovelLandingDTO expected = createLanding(new ArrayList<>());

        ReaderNovelLandingDTO result = cache.getOrLoad(() -> {
            calls.incrementAndGet();
            return expected;
        });

        assertThat(result.novel()).isEqualTo(expected.novel());
        assertThat(calls).hasValue(1);
    }

    @Test
    void warmHit_bypassesLoader() {
        AtomicInteger calls = new AtomicInteger();
        ReaderNovelLandingDTO expected = createLanding(new ArrayList<>());

        cache.getOrLoad(() -> {
            calls.incrementAndGet();
            return expected;
        });

        ReaderNovelLandingDTO hitResult = cache.getOrLoad(() -> {
            calls.incrementAndGet();
            return expected;
        });

        assertThat(hitResult.novel()).isEqualTo(expected.novel());
        assertThat(calls).hasValue(1); // Still 1
    }

    @Test
    void loaderFailure_isNotCached() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> cache.getOrLoad(() -> {
            calls.incrementAndGet();
            throw new RuntimeException("Loader failed");
        })).isInstanceOf(RuntimeException.class).hasMessage("Loader failed");

        ReaderNovelLandingDTO expected = createLanding(new ArrayList<>());
        ReaderNovelLandingDTO successResult = cache.getOrLoad(() -> {
            calls.incrementAndGet();
            return expected;
        });

        assertThat(successResult.novel()).isEqualTo(expected.novel());
        assertThat(calls).hasValue(2);
    }

    @Test
    void concurrentMisses_areCoalesced() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch inLoaderLatch = new CountDownLatch(1);
        CountDownLatch releaseLoaderLatch = new CountDownLatch(1);

        ReaderNovelLandingDTO expected = createLanding(new ArrayList<>());

        ExecutorService executor = Executors.newFixedThreadPool(3);
        List<CompletableFuture<ReaderNovelLandingDTO>> futures = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            futures.add(CompletableFuture.supplyAsync(() -> cache.getOrLoad(() -> {
                calls.incrementAndGet();
                inLoaderLatch.countDown();
                try {
                    releaseLoaderLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return expected;
            }), executor));
        }

        inLoaderLatch.await();
        releaseLoaderLatch.countDown();

        for (CompletableFuture<ReaderNovelLandingDTO> f : futures) {
            ReaderNovelLandingDTO res = f.join();
            assertThat(res.novel()).isEqualTo(expected.novel());
        }

        assertThat(calls).hasValue(1); // coalesced to 1 call
        executor.shutdown();
    }

    @Test
    void oldInFlightResult_cannotRepopulateAfterInvalidation() throws InterruptedException {
        CountDownLatch inLoaderLatch = new CountDownLatch(1);
        CountDownLatch releaseLoaderLatch = new CountDownLatch(1);

        ReaderNovelLandingDTO oldData = createLanding("OLD title", List.of());
        ReaderNovelLandingDTO newData = createLanding("NEW title", List.of());

        CompletableFuture<ReaderNovelLandingDTO> oldFuture = CompletableFuture
                .supplyAsync(() -> cache.getOrLoad(() -> {
                    inLoaderLatch.countDown();
                    try {
                        releaseLoaderLatch.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return oldData;
                }));

        inLoaderLatch.await();
        // Now an old load is in flight. We invalidate.
        cache.invalidate();

        // We run a new request
        ReaderNovelLandingDTO newResult = cache.getOrLoad(() -> newData);
        assertThat(newResult.novel().title()).isEqualTo("NEW title");

        // Now we release the old load
        releaseLoaderLatch.countDown();
        ReaderNovelLandingDTO oldResult = oldFuture.join();
        assertThat(oldResult.novel().title()).isEqualTo("OLD title");

        // Next warm hit should get NEW data, never OLD data
        ReaderNovelLandingDTO finalResult = cache.getOrLoad(() -> {
            throw new AssertionError("Warm hit must bypass loader");
        });
        assertThat(finalResult.novel().title()).isEqualTo("NEW title");
        assertThat(finalResult.novel().title()).isNotEqualTo("OLD title");
    }

    @Test
    void returnedListIsImmutable() {
        ReaderNovelLandingDTO expected = createLanding("T", new ArrayList<>());
        ReaderNovelLandingDTO result = cache.getOrLoad(() -> expected);

        assertThatThrownBy(() -> result.volumes().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private ReaderNovelLandingDTO createLanding(List<ReaderVolumeListItemDTO> volumes) {
        return createLanding("T", volumes);
    }

    private ReaderNovelLandingDTO createLanding(String title, List<ReaderVolumeListItemDTO> volumes) {
        ReaderNovelOverviewDTO novel = new ReaderNovelOverviewDTO(title, "slug", "Author", "Desc", null, "ONGOING");
        return new ReaderNovelLandingDTO(novel, volumes, null);
    }
}
