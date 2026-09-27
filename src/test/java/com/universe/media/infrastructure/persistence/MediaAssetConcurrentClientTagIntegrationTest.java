package com.universe.media.infrastructure.persistence;

import com.universe.media.application.asset.AssignMediaAssetClientTagCommand;
import com.universe.media.application.asset.AssignMediaAssetClientTagUseCase;
import com.universe.media.domain.ClientTagConflictException;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;
import com.universe.shared.time.ClockPort;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Import({
        MediaAssetPersistenceAdapter.class,
        AssignMediaAssetClientTagUseCase.class,
        MediaAssetConcurrentClientTagIntegrationTest.TestConfig.class
})
class MediaAssetConcurrentClientTagIntegrationTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        public ClockPort clockPort() {
            return () -> Instant.parse("2026-09-01T12:00:00Z");
        }
    }

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private SpringDataMediaAssetJpaRepository jpaRepository;

    @Autowired
    private MediaAssetPersistenceAdapter persistenceAdapter;

    @Autowired
    private AssignMediaAssetClientTagUseCase assignMediaAssetClientTagUseCase;

    private final List<String> createdAssetIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String id : createdAssetIds) {
            if (jpaRepository.existsById(id)) {
                jpaRepository.deleteById(id);
            }
        }
        createdAssetIds.clear();
    }

    @Test
    @DisplayName("Concurrent tag assignment with identical tag succeeds idempotently on all threads")
    void shouldHandleConcurrentIdenticalTagAssignment() throws Exception {
        UUID assetId = UUID.randomUUID();
        createdAssetIds.add(assetId.toString());

        MediaAsset asset = MediaAsset.registerInitial(
                assetId,
                MediaType.IMAGE,
                MediaVisibility.PUBLIC,
                Instant.parse("2026-09-01T10:00:00Z"),
                null
        );
        persistenceAdapter.save(asset);

        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                assignMediaAssetClientTagUseCase.execute(
                        new AssignMediaAssetClientTagCommand(assetId, "wiki.article.cover")
                );
                return null;
            }));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        for (Future<Void> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        MediaAsset updated = persistenceAdapter.findById(assetId).orElseThrow();
        assertThat(updated.getClientTag()).isEqualTo("wiki.article.cover");
    }

    @Test
    @DisplayName("Concurrent tag assignment with conflicting tags results in one winner and one ClientTagConflictException")
    void shouldEnforceConflictWhenConcurrentTagsDiffer() throws Exception {
        UUID assetId = UUID.randomUUID();
        createdAssetIds.add(assetId.toString());

        MediaAsset asset = MediaAsset.registerInitial(
                assetId,
                MediaType.IMAGE,
                MediaVisibility.PUBLIC,
                Instant.parse("2026-09-01T10:00:00Z"),
                null
        );
        persistenceAdapter.save(asset);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Future<Void> futureA = executor.submit(() -> {
            readyLatch.countDown();
            startLatch.await();
            assignMediaAssetClientTagUseCase.execute(
                    new AssignMediaAssetClientTagCommand(assetId, "wiki.article.cover")
            );
            return null;
        });

        Future<Void> futureB = executor.submit(() -> {
            readyLatch.countDown();
            startLatch.await();
            assignMediaAssetClientTagUseCase.execute(
                    new AssignMediaAssetClientTagCommand(assetId, "novel.chapter.illustration")
            );
            return null;
        });

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        for (Future<Void> future : List.of(futureA, futureB)) {
            try {
                future.get(10, TimeUnit.SECONDS);
                successCount.incrementAndGet();
            } catch (ExecutionException e) {
                if (e.getCause() instanceof ClientTagConflictException) {
                    conflictCount.incrementAndGet();
                } else {
                    throw e;
                }
            }
        }
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);

        MediaAsset updated = persistenceAdapter.findById(assetId).orElseThrow();
        assertThat(updated.getClientTag()).isIn("wiki.article.cover", "novel.chapter.illustration");
    }
}
