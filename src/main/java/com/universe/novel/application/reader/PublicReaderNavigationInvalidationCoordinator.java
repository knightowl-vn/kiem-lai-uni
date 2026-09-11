package com.universe.novel.application.reader;

import com.universe.novel.application.ports.PublicReaderNavigationIndexCachePort;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

/**
 * Registers public reader navigation index cache invalidation against the enclosing transaction commit.
 */
@Component
public class PublicReaderNavigationInvalidationCoordinator {

    private final PublicReaderNavigationIndexCachePort cache;

    public PublicReaderNavigationInvalidationCoordinator(
            PublicReaderNavigationIndexCachePort cache
    ) {
        this.cache = Objects.requireNonNull(cache, "cache must not be null");
    }

    public void invalidateAfterCommit() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException(
                    "Public Reader navigation index invalidation requires an active synchronized transaction."
            );
        }

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        cache.invalidate();
                    }
                }
        );
    }
}
