package com.universe.novel.application.reader;

import com.universe.novel.application.ports.PublicReaderChapterListCachePort;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.UUID;

/**
 * Registers public reader chapter list invalidation against the enclosing
 * transaction commit.
 */
@Component
public class PublicReaderChapterListInvalidationCoordinator {

	private final PublicReaderChapterListCachePort cache;

	public PublicReaderChapterListInvalidationCoordinator(PublicReaderChapterListCachePort cache) {
		this.cache = Objects.requireNonNull(cache, "cache must not be null");
	}

	public void invalidateAfterCommit(UUID volumeId) {
		Objects.requireNonNull(volumeId, "volumeId must not be null");

		if (!TransactionSynchronizationManager.isActualTransactionActive()
				|| !TransactionSynchronizationManager.isSynchronizationActive()) {
			throw new IllegalStateException(
					"Public Reader chapter list invalidation requires an active synchronized transaction.");
		}

		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				cache.invalidate(volumeId);
			}
		});
	}
}
