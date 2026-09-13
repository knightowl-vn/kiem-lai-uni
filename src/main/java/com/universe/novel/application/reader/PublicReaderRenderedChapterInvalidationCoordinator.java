package com.universe.novel.application.reader;

import com.universe.novel.application.ports.PublicReaderRenderedChapterCachePort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

/**
 * Registers public reader rendered chapter cache invalidation against the enclosing transaction commit.
 */
@Component
public class PublicReaderRenderedChapterInvalidationCoordinator {

    private final PublicReaderRenderedChapterCachePort cachePort;

    public PublicReaderRenderedChapterInvalidationCoordinator(PublicReaderRenderedChapterCachePort cachePort) {
        this.cachePort = Objects.requireNonNull(cachePort, "cachePort must not be null");
    }

    public void invalidateAfterCommit(String normalizedSlug) {
        if (normalizedSlug == null || normalizedSlug.isBlank()) {
            throw new IllegalArgumentException("normalizedSlug must not be null or blank");
        }

        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException(
                    "Public Reader rendered chapter invalidation requires an active synchronized transaction."
            );
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cachePort.invalidate(normalizedSlug);
            }
        });
    }
}

