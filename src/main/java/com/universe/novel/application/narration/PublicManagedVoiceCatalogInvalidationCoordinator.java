package com.universe.novel.application.narration;

import com.universe.novel.application.ports.PublicManagedVoiceCatalogCachePort;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

/**
 * Registers public voice catalog invalidation against the enclosing transaction commit.
 */
@Component
public class PublicManagedVoiceCatalogInvalidationCoordinator {

    private final PublicManagedVoiceCatalogCachePort cache;

    public PublicManagedVoiceCatalogInvalidationCoordinator(
            PublicManagedVoiceCatalogCachePort cache
    ) {
        this.cache = Objects.requireNonNull(cache, "cache must not be null");
    }

    public void invalidateAfterCommit() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException(
                    "Public Managed voice catalog invalidation requires an active synchronized transaction."
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
