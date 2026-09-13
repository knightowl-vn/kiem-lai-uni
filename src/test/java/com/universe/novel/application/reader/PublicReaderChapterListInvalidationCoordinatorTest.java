package com.universe.novel.application.reader;

import com.universe.novel.application.ports.PublicReaderChapterListCachePort;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class PublicReaderChapterListInvalidationCoordinatorTest {

    @Mock
    private PublicReaderChapterListCachePort cache;

    @Test
    void invalidatesOnlyAfterSuccessfulCommit() {
        PublicReaderChapterListInvalidationCoordinator coordinator =
                new PublicReaderChapterListInvalidationCoordinator(cache);
        TransactionTemplate transaction = new TransactionTemplate(
                new TestTransactionManager()
        );

        UUID volumeId = UUID.randomUUID();

        transaction.executeWithoutResult(status -> {
            coordinator.invalidateAfterCommit(volumeId);
            verify(cache, never()).invalidate(any());
        });

        verify(cache).invalidate(volumeId);
    }

    @Test
    void rollbackDoesNotInvalidate() {
        PublicReaderChapterListInvalidationCoordinator coordinator =
                new PublicReaderChapterListInvalidationCoordinator(cache);
        TransactionTemplate transaction = new TransactionTemplate(
                new TestTransactionManager()
        );

        UUID volumeId = UUID.randomUUID();

        transaction.executeWithoutResult(status -> {
            coordinator.invalidateAfterCommit(volumeId);
            status.setRollbackOnly();
        });

        verify(cache, never()).invalidate(any());
    }

    @Test
    void refusesToFallBackToPreCommitOrNonTransactionalInvalidation() {
        PublicReaderChapterListInvalidationCoordinator coordinator =
                new PublicReaderChapterListInvalidationCoordinator(cache);

        UUID volumeId = UUID.randomUUID();

        assertThatThrownBy(() -> coordinator.invalidateAfterCommit(volumeId))
                .isInstanceOf(IllegalStateException.class);
        verify(cache, never()).invalidate(any());
    }

    private static final class TestTransactionManager
            extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(
                Object transaction,
                TransactionDefinition definition
        ) {
        }

        @Override
        protected void doCommit(
                DefaultTransactionStatus status
        ) {
        }

        @Override
        protected void doRollback(
                DefaultTransactionStatus status
        ) {
        }
    }
}
