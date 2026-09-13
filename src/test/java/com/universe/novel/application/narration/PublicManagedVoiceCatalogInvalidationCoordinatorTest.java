package com.universe.novel.application.narration;

import com.universe.novel.application.ports.PublicManagedVoiceCatalogCachePort;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PublicManagedVoiceCatalogInvalidationCoordinatorTest {

    @Mock
    private PublicManagedVoiceCatalogCachePort cache;

    @Test
    void invalidatesOnlyAfterSuccessfulCommit() {
        PublicManagedVoiceCatalogInvalidationCoordinator coordinator =
                new PublicManagedVoiceCatalogInvalidationCoordinator(cache);
        TransactionTemplate transaction = new TransactionTemplate(
                new TestTransactionManager()
        );

        transaction.executeWithoutResult(status -> {
            coordinator.invalidateAfterCommit();
            verify(cache, never()).invalidate();
        });

        verify(cache).invalidate();
    }

    @Test
    void rollbackDoesNotInvalidate() {
        PublicManagedVoiceCatalogInvalidationCoordinator coordinator =
                new PublicManagedVoiceCatalogInvalidationCoordinator(cache);
        TransactionTemplate transaction = new TransactionTemplate(
                new TestTransactionManager()
        );

        transaction.executeWithoutResult(status -> {
            coordinator.invalidateAfterCommit();
            status.setRollbackOnly();
        });

        verify(cache, never()).invalidate();
    }

    @Test
    void refusesToFallBackToPreCommitOrNonTransactionalInvalidation() {
        PublicManagedVoiceCatalogInvalidationCoordinator coordinator =
                new PublicManagedVoiceCatalogInvalidationCoordinator(cache);

        assertThatThrownBy(coordinator::invalidateAfterCommit)
                .isInstanceOf(IllegalStateException.class);
        verify(cache, never()).invalidate();
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
