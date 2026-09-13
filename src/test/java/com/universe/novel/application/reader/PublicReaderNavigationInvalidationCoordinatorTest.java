package com.universe.novel.application.reader;

import com.universe.novel.application.ports.PublicReaderNavigationIndexCachePort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PublicReaderNavigationInvalidationCoordinatorTest {

    private PublicReaderNavigationIndexCachePort cache;
    private PublicReaderNavigationInvalidationCoordinator coordinator;

    @BeforeEach
    void setUp() {
        cache = mock(PublicReaderNavigationIndexCachePort.class);
        coordinator = new PublicReaderNavigationInvalidationCoordinator(cache);
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void invalidateAfterCommit_shouldThrowIfNotInTransaction() {
        assertThatThrownBy(() -> coordinator.invalidateAfterCommit())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active synchronized transaction");

        verifyNoInteractions(cache);
    }

    @Test
    void invalidateAfterCommit_shouldRegisterSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        coordinator.invalidateAfterCommit();

        verifyNoInteractions(cache);

        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());

        verify(cache).invalidate();
    }

    @Test
    void invalidateAfterCommit_shouldNotInvalidateOnRollback() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        coordinator.invalidateAfterCommit();

        verifyNoInteractions(cache);

        TransactionSynchronizationManager.getSynchronizations().forEach(s -> 
            s.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK)
        );

        verifyNoInteractions(cache);
    }
}
