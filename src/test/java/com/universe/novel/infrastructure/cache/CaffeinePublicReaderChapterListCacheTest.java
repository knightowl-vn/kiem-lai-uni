package com.universe.novel.infrastructure.cache;

import com.universe.novel.contracts.dto.reader.ReaderChapterListItemDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaffeinePublicReaderChapterListCacheTest {

	private CaffeinePublicReaderChapterListCache cache;

	@BeforeEach
	void setUp() {
		cache = new CaffeinePublicReaderChapterListCache();
	}

	@Test
	void coldMiss_invokesLoaderOnce() {
		UUID volumeId = UUID.randomUUID();
		AtomicInteger calls = new AtomicInteger();
		List<ReaderChapterListItemDTO> expected = List.of(createItem());

		List<ReaderChapterListItemDTO> result = cache.getOrLoad(volumeId, () -> {
			calls.incrementAndGet();
			return expected;
		});

		assertThat(result).isSameAs(expected);
		assertThat(calls).hasValue(1);
	}

	@Test
	void warmHit_bypassesLoader() {
		UUID volumeId = UUID.randomUUID();
		AtomicInteger calls = new AtomicInteger();
		List<ReaderChapterListItemDTO> expected = List.of(createItem());

		cache.getOrLoad(volumeId, () -> {
			calls.incrementAndGet();
			return expected;
		});

		List<ReaderChapterListItemDTO> hitResult = cache.getOrLoad(volumeId, () -> {
			calls.incrementAndGet();
			return expected;
		});

		assertThat(hitResult).isSameAs(expected);
		assertThat(calls).hasValue(1); // Still 1
	}

	@Test
	void loaderFailure_isNotCached() {
		UUID volumeId = UUID.randomUUID();
		AtomicInteger calls = new AtomicInteger();

		assertThatThrownBy(() -> cache.getOrLoad(volumeId, () -> {
			calls.incrementAndGet();
			throw new RuntimeException("Loader failed");
		})).isInstanceOf(RuntimeException.class).hasMessage("Loader failed");

		List<ReaderChapterListItemDTO> expected = List.of(createItem());
		List<ReaderChapterListItemDTO> successResult = cache.getOrLoad(volumeId, () -> {
			calls.incrementAndGet();
			return expected;
		});

		assertThat(successResult).isSameAs(expected);
		assertThat(calls).hasValue(2);
	}

	@Test
	void concurrentMissesForSameVolume_areCoalesced() throws InterruptedException {
		UUID volumeId = UUID.randomUUID();
		AtomicInteger calls = new AtomicInteger();
		CountDownLatch inLoaderLatch = new CountDownLatch(1);
		CountDownLatch releaseLoaderLatch = new CountDownLatch(1);

		List<ReaderChapterListItemDTO> expected = List.of(createItem());

		ExecutorService executor = Executors.newFixedThreadPool(3);
		List<CompletableFuture<List<ReaderChapterListItemDTO>>> futures = new ArrayList<>();

		for (int i = 0; i < 3; i++) {
			futures.add(CompletableFuture.supplyAsync(() -> cache.getOrLoad(volumeId, () -> {
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

		for (CompletableFuture<List<ReaderChapterListItemDTO>> f : futures) {
			List<ReaderChapterListItemDTO> res = f.join();
			assertThat(res).isSameAs(expected);
		}

		assertThat(calls).hasValue(1); // coalesced to 1 call
		executor.shutdown();
	}

	@Test
	void differentVolumeKeys_remainIndependent() {
		UUID volumeA = UUID.randomUUID();
		UUID volumeB = UUID.randomUUID();
		AtomicInteger callsA = new AtomicInteger();
		AtomicInteger callsB = new AtomicInteger();

		cache.getOrLoad(volumeA, () -> {
			callsA.incrementAndGet();
			return List.of(createItem());
		});

		cache.getOrLoad(volumeB, () -> {
			callsB.incrementAndGet();
			return List.of(createItem());
		});

		assertThat(callsA).hasValue(1);
		assertThat(callsB).hasValue(1);

		cache.getOrLoad(volumeA, () -> {
			callsA.incrementAndGet();
			return List.of();
		});
		assertThat(callsA).hasValue(1); // hit for A
	}

	@Test
	void invalidatingVolumeA_doesNotEvictVolumeB() {
		UUID volumeA = UUID.randomUUID();
		UUID volumeB = UUID.randomUUID();
		AtomicInteger calls = new AtomicInteger();

		cache.getOrLoad(volumeA, () -> {
			calls.incrementAndGet();
			return List.of(createItem());
		});
		cache.getOrLoad(volumeB, () -> {
			calls.incrementAndGet();
			return List.of(createItem());
		});
		assertThat(calls).hasValue(2);

		cache.invalidate(volumeA);

		cache.getOrLoad(volumeB, () -> {
			calls.incrementAndGet();
			return List.of(createItem());
		});
		assertThat(calls).hasValue(2); // Still 2, B was a hit

		cache.getOrLoad(volumeA, () -> {
			calls.incrementAndGet();
			return List.of(createItem());
		});
		assertThat(calls).hasValue(3); // Miss for A
	}

	@Test
	void oldInFlightResult_cannotRepopulateAfterInvalidation() throws InterruptedException {
		UUID volumeId = UUID.randomUUID();
		CountDownLatch inLoaderLatch = new CountDownLatch(1);
		CountDownLatch releaseLoaderLatch = new CountDownLatch(1);

		List<ReaderChapterListItemDTO> oldData = List.of(createItem());
		List<ReaderChapterListItemDTO> newData = List.of(createItem(), createItem());

		CompletableFuture<List<ReaderChapterListItemDTO>> oldFuture = CompletableFuture
				.supplyAsync(() -> cache.getOrLoad(volumeId, () -> {
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
		cache.invalidate(volumeId);

		// We run a new request
		List<ReaderChapterListItemDTO> newResult = cache.getOrLoad(volumeId, () -> newData);
		assertThat(newResult).isSameAs(newData);

		// Now we release the old load
		releaseLoaderLatch.countDown();
		List<ReaderChapterListItemDTO> oldResult = oldFuture.join();
		assertThat(oldResult).isSameAs(oldData);

		// Next hit should get NEW data, not old data
		List<ReaderChapterListItemDTO> finalResult = cache.getOrLoad(volumeId, () -> {
			throw new RuntimeException("Should not be called");
		});
		assertThat(finalResult).isSameAs(newData);
	}

	@Test
	void returnedListIsImmutable() {
		UUID volumeId = UUID.randomUUID();

		List<ReaderChapterListItemDTO> mutableList = new ArrayList<>();
		mutableList.add(createItem());

		List<ReaderChapterListItemDTO> result = cache.getOrLoad(volumeId, () -> mutableList);

		assertThatThrownBy(() -> result.add(createItem())).isInstanceOf(UnsupportedOperationException.class);
	}

	private ReaderChapterListItemDTO createItem() {
		return new ReaderChapterListItemDTO(UUID.randomUUID(), 1, "Title", "slug");
	}
}
