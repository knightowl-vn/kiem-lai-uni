package com.universe.novel.application.reader;

import com.universe.novel.application.ports.PublicReaderRenderedChapterCachePort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PublicReaderRenderedChapterInvalidationCoordinatorTest {

    @Mock
    private PublicReaderRenderedChapterCachePort cachePort;

    @InjectMocks
    private PublicReaderRenderedChapterInvalidationCoordinator coordinator;

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void activeTransactionAndCommit_invalidatesExactlyInAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        coordinator.invalidateAfterCommit("slug-1");

        verifyNoInteractions(cachePort);

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        verify(cachePort).invalidate("slug-1");
    }

    @Test
    void activeTransactionAndRollback_doesNotInvalidate() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        coordinator.invalidateAfterCommit("slug-1");

        verifyNoInteractions(cachePort);

        TransactionSynchronizationManager.getSynchronizations().forEach(s ->
                s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK)
        );

        verifyNoInteractions(cachePort);
    }

    @Test
    void noSynchronizationOrTransaction_throwsIllegalStateException() {
        assertThatThrownBy(() -> coordinator.invalidateAfterCommit("slug-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active synchronized transaction");

        verifyNoInteractions(cachePort);
    }

    @Test
    void synchronizationActiveButActualTransactionInactive_throwsIllegalStateException() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);

        assertThatThrownBy(() -> coordinator.invalidateAfterCommit("slug-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active synchronized transaction");

        verifyNoInteractions(cachePort);
    }

    @Test
    void nullOrBlankSlug_rejected() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> coordinator.invalidateAfterCommit(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> coordinator.invalidateAfterCommit(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> coordinator.invalidateAfterCommit("   "))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(cachePort);
    }
}
