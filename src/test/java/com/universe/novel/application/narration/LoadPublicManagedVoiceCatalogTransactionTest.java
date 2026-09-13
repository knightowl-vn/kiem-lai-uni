package com.universe.novel.application.narration;

import com.universe.novel.application.ports.PublicManagedVoiceCatalogQueryPort;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoadPublicManagedVoiceCatalogTransactionTest {

    @Test
    void loaderRunsInsideASeparateReadOnlyTransactionProxy() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(TestConfiguration.class)) {
            PublicManagedVoiceCatalogQueryPort queryPort =
                    context.getBean(PublicManagedVoiceCatalogQueryPort.class);
            AtomicBoolean transactionActive = new AtomicBoolean();
            AtomicBoolean transactionReadOnly = new AtomicBoolean();
            when(queryPort.findSelectableVoices()).thenAnswer(invocation -> {
                transactionActive.set(
                        TransactionSynchronizationManager.isActualTransactionActive()
                );
                transactionReadOnly.set(
                        TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                );
                return List.of();
            });

            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            context.getBean(LoadPublicManagedVoiceCatalogUseCase.class)
                    .execute(new GetPublicManagedVoiceCatalogQuery());

            assertThat(transactionActive).isTrue();
            assertThat(transactionReadOnly).isTrue();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TestConfiguration {

        @Bean
        PublicManagedVoiceCatalogQueryPort catalogQueryPort() {
            return mock(PublicManagedVoiceCatalogQueryPort.class);
        }

        @Bean
        LoadPublicManagedVoiceCatalogUseCase catalogLoader(
                PublicManagedVoiceCatalogQueryPort queryPort
        ) {
            return new LoadPublicManagedVoiceCatalogUseCase(queryPort);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return new TestTransactionManager();
        }
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
