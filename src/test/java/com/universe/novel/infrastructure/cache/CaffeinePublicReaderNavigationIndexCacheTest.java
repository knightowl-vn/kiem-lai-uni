package com.universe.novel.infrastructure.cache;

import com.universe.novel.contracts.dto.reader.ReaderChapterTocItemDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaffeinePublicReaderNavigationIndexCacheTest {

    private CaffeinePublicReaderNavigationIndexCache cache;

    @BeforeEach
    void setUp() {
        cache = new CaffeinePublicReaderNavigationIndexCache();
    }

    @Test
    void getOrLoad_shouldLoadOnceOnColdMiss() {
        AtomicInteger loadCount = new AtomicInteger(0);
        Supplier<List<ReaderChapterTocItemDTO>> loader = () -> {
            loadCount.incrementAndGet();
            return List.of(new ReaderChapterTocItemDTO(1, "Title", "slug"));
        };

        List<ReaderChapterTocItemDTO> r1 = cache.getOrLoad(loader);
        List<ReaderChapterTocItemDTO> r2 = cache.getOrLoad(loader);

        assertThat(loadCount.get()).isEqualTo(1);
        assertThat(r1).isEqualTo(r2);
    }

    @Test
    void getOrLoad_shouldNotCacheFailures() {
        AtomicInteger loadCount = new AtomicInteger(0);
        Supplier<List<ReaderChapterTocItemDTO>> loader = () -> {
            loadCount.incrementAndGet();
            throw new RuntimeException("DB Error");
        };

        assertThatThrownBy(() -> cache.getOrLoad(loader))
                .isInstanceOf(RuntimeException.class);

        assertThatThrownBy(() -> cache.getOrLoad(loader))
                .isInstanceOf(RuntimeException.class);

        assertThat(loadCount.get()).isEqualTo(2);
    }

    @Test
    void getOrLoad_shouldNotDurablyCacheEmptyList() {
        AtomicInteger loadCount = new AtomicInteger(0);
        Supplier<List<ReaderChapterTocItemDTO>> loader = () -> {
            loadCount.incrementAndGet();
            return List.of();
        };

        List<ReaderChapterTocItemDTO> r1 = cache.getOrLoad(loader);
        List<ReaderChapterTocItemDTO> r2 = cache.getOrLoad(loader);

        assertThat(r1).isEmpty();
        assertThat(r2).isEmpty();
        assertThat(loadCount.get()).isEqualTo(2);
    }

    @Test
    void getOrLoad_shouldReturnImmutableList() {
        List<ReaderChapterTocItemDTO> original = new ArrayList<>();
        original.add(new ReaderChapterTocItemDTO(1, "T1", "s1"));

        Supplier<List<ReaderChapterTocItemDTO>> loader = () -> original;
        List<ReaderChapterTocItemDTO> cached = cache.getOrLoad(loader);

        assertThatThrownBy(() -> cached.add(new ReaderChapterTocItemDTO(2, "T2", "s2")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void getOrLoad_shouldCoalesceConcurrentMisses() throws InterruptedException {
        AtomicInteger loadCount = new AtomicInteger(0);
        CountDownLatch lock = new CountDownLatch(1);
        CountDownLatch inLoader = new CountDownLatch(1);

        Supplier<List<ReaderChapterTocItemDTO>> loader = () -> {
            inLoader.countDown();
            try {
                lock.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            loadCount.incrementAndGet();
            return List.of(new ReaderChapterTocItemDTO(1, "Title", "slug"));
        };

        CompletableFuture<List<ReaderChapterTocItemDTO>> f1 = CompletableFuture.supplyAsync(() -> cache.getOrLoad(loader));
        
        inLoader.await();

        CompletableFuture<List<ReaderChapterTocItemDTO>> f2 = CompletableFuture.supplyAsync(() -> cache.getOrLoad(loader));

        lock.countDown();

        List<ReaderChapterTocItemDTO> r1 = f1.join();
        List<ReaderChapterTocItemDTO> r2 = f2.join();

        assertThat(loadCount.get()).isEqualTo(1);
        assertThat(r1).isSameAs(r2);
    }

    @Test
    void invalidate_shouldAdvanceGenerationAndPreventStaleRepopulation() throws InterruptedException {
        CountDownLatch lock = new CountDownLatch(1);
        CountDownLatch inLoader = new CountDownLatch(1);

        List<ReaderChapterTocItemDTO> oldData = List.of(new ReaderChapterTocItemDTO(1, "OLD Title", "slug-1"));
        Supplier<List<ReaderChapterTocItemDTO>> slowLoader = () -> {
            inLoader.countDown();
            try {
                lock.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return oldData;
        };

        CompletableFuture<List<ReaderChapterTocItemDTO>> slowLoadFuture = CompletableFuture.supplyAsync(() -> cache.getOrLoad(slowLoader));

        inLoader.await();

        cache.invalidate();

        List<ReaderChapterTocItemDTO> newData = List.of(new ReaderChapterTocItemDTO(1, "NEW Title", "slug-1"));
        Supplier<List<ReaderChapterTocItemDTO>> fastLoader = () -> newData;
        List<ReaderChapterTocItemDTO> freshResult = cache.getOrLoad(fastLoader);

        lock.countDown();
        List<ReaderChapterTocItemDTO> slowLoadResult = slowLoadFuture.join();

        assertThat(slowLoadResult).isEqualTo(oldData);
        assertThat(freshResult).isEqualTo(newData);

        List<ReaderChapterTocItemDTO> finalState = cache.getOrLoad(() -> {
            throw new AssertionError("Should hit cache");
        });
        assertThat(finalState).isEqualTo(newData);
    }

    @Test
    void invalidate_shouldClearCacheAndForceNewLoad() {
        AtomicInteger loadCount = new AtomicInteger(0);
        List<ReaderChapterTocItemDTO> oldData = List.of(new ReaderChapterTocItemDTO(1, "OLD Title", "slug-1"));
        List<ReaderChapterTocItemDTO> newData = List.of(new ReaderChapterTocItemDTO(2, "NEW Title", "slug-2"));

        Supplier<List<ReaderChapterTocItemDTO>> loader = () -> {
            int count = loadCount.incrementAndGet();
            return count == 1 ? oldData : newData;
        };

        // Prime OLD cached TOC
        List<ReaderChapterTocItemDTO> r1 = cache.getOrLoad(loader);
        assertThat(r1).isEqualTo(oldData);
        assertThat(loadCount.get()).isEqualTo(1);

        // Invalidate
        cache.invalidate();

        // Next getOrLoad invokes loader, NEW TOC becomes the warm cached value
        List<ReaderChapterTocItemDTO> r2 = cache.getOrLoad(loader);
        assertThat(r2).isEqualTo(newData);
        assertThat(loadCount.get()).isEqualTo(2);
        
        // Next read is warm cache hit
        List<ReaderChapterTocItemDTO> r3 = cache.getOrLoad(loader);
        assertThat(r3).isEqualTo(newData);
        assertThat(loadCount.get()).isEqualTo(2);
    }
}
