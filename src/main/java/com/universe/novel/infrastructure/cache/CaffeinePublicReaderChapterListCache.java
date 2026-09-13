package com.universe.novel.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.universe.novel.application.ports.PublicReaderChapterListCachePort;
import com.universe.novel.contracts.dto.reader.ReaderChapterListItemDTO;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@Component
public class CaffeinePublicReaderChapterListCache implements PublicReaderChapterListCachePort {

	private static final long INITIAL_GENERATION = 0L;
	private static final Duration EXPIRATION = Duration.ofMinutes(10);
	private static final long MAXIMUM_VOLUMES = 500L;

	private final Cache<UUID, VolumeState> stateCache;
	private final Cache<UUID, VersionedChapterList> dataCache;

	public CaffeinePublicReaderChapterListCache() {
		this.stateCache = Caffeine.newBuilder().maximumSize(MAXIMUM_VOLUMES).expireAfterAccess(EXPIRATION).build();

		this.dataCache = Caffeine.newBuilder().maximumSize(MAXIMUM_VOLUMES).expireAfterWrite(EXPIRATION).build();
	}

	@Override
	public List<ReaderChapterListItemDTO> getOrLoad(UUID volumeId, Supplier<List<ReaderChapterListItemDTO>> loader) {
		Objects.requireNonNull(volumeId, "volumeId must not be null");
		Objects.requireNonNull(loader, "loader must not be null");

		while (true) {
			VolumeState state = stateCache.get(volumeId, k -> new VolumeState());
			long requestedGeneration = state.generation.get();

			VersionedChapterList cached = dataCache.getIfPresent(volumeId);
			if (cached != null && cached.state() == state && cached.generation() == requestedGeneration) {
				return cached.chapters();
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
				VersionedChapterList cachedAfterCas = dataCache.getIfPresent(volumeId);
				if (cachedAfterCas != null && cachedAfterCas.state() == state
						&& cachedAfterCas.generation() == requestedGeneration) {
					newLoad.result().complete(cachedAfterCas.chapters());
					return cachedAfterCas.chapters();
				}

				List<ReaderChapterListItemDTO> rawLoaded = Objects.requireNonNull(loader.get(),
						"chapter list loader result must not be null");

				List<ReaderChapterListItemDTO> immutableLoaded = List.copyOf(rawLoaded);

				newLoad.result().complete(immutableLoaded);

				if (!immutableLoaded.isEmpty()) {
					synchronized (state.populationLock) {
						if (state.generation.get() == requestedGeneration) {
							dataCache.put(volumeId,
									new VersionedChapterList(state, requestedGeneration, immutableLoaded));
						}
					}
				}

				return immutableLoaded;
			} catch (RuntimeException | Error failure) {
				newLoad.result().completeExceptionally(failure);
				throw failure;
			} finally {
				state.inFlightLoad.compareAndSet(newLoad, null);
			}
		}
	}

	@Override
	public void invalidate(UUID volumeId) {
		Objects.requireNonNull(volumeId, "volumeId must not be null");

		VolumeState state = stateCache.getIfPresent(volumeId);
		if (state != null) {
			synchronized (state.populationLock) {
				state.generation.incrementAndGet();
				dataCache.invalidate(volumeId);
			}
		}
	}

	private List<ReaderChapterListItemDTO> await(CompletableFuture<List<ReaderChapterListItemDTO>> result) {
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

	private static class VolumeState {
		final AtomicLong generation = new AtomicLong(INITIAL_GENERATION);
		final AtomicReference<InFlightLoad> inFlightLoad = new AtomicReference<>();
		final Object populationLock = new Object();
	}

	private record VersionedChapterList(VolumeState state, long generation, List<ReaderChapterListItemDTO> chapters) {
		VersionedChapterList {
			Objects.requireNonNull(state, "state must not be null");
			Objects.requireNonNull(chapters, "chapters must not be null");
		}
	}

	private record InFlightLoad(long generation, CompletableFuture<List<ReaderChapterListItemDTO>> result) {
	}
}
