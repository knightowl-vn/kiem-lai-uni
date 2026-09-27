package com.universe.wiki.infrastructure.persistence.orphan;

import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.shared.id.UuidGeneratorAdapter;
import com.universe.shared.time.ClockPort;
import com.universe.test.TestDatabaseSupport;
import com.universe.wiki.application.article.cover.WikiCoverIntent;
import com.universe.wiki.application.article.cover.WikiCoverMediaCoordinator;
import com.universe.wiki.application.article.create.CreateWikiArticleCommand;
import com.universe.wiki.application.article.create.CreateWikiArticleUseCase;
import com.universe.wiki.application.article.update.draft.UpdateDraftWikiArticleCommand;
import com.universe.wiki.application.article.update.draft.UpdateDraftWikiArticleUseCase;
import com.universe.wiki.application.exceptions.WikiCoverMediaAssetDeletingException;
import com.universe.wiki.application.ports.WikiArticleRepositoryPort;
import com.universe.wiki.application.ports.WikiCoverOrphanRecord;
import com.universe.wiki.application.ports.WikiCoverOrphanRepositoryPort;
import com.universe.wiki.contracts.dto.WikiArticleDTO;
import com.universe.wiki.domain.article.ArticleType;
import com.universe.wiki.domain.article.WikiArticle;
import com.universe.wiki.domain.orphan.WikiCoverOrphanStatus;
import com.universe.wiki.infrastructure.markdown.CommonMarkWikiMarkdownImageExtractor;
import com.universe.wiki.infrastructure.persistence.article.WikiArticlePersistenceAdapter;
import com.universe.wiki.infrastructure.persistence.article.WikiArticleQueryAdapter;
import com.universe.wiki.infrastructure.persistence.image.WikiImageReferenceSynchronizer;
import com.universe.wiki.infrastructure.persistence.revision.WikiArticleRevisionPersistenceAdapter;
import com.universe.wiki.infrastructure.persistence.revision.WikiArticleRevisionQueryAdapter;
import com.universe.wiki.infrastructure.slug.DefaultSlugGeneratorAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Import({
        WikiArticlePersistenceAdapter.class,
        WikiArticleQueryAdapter.class,
        WikiArticleRevisionPersistenceAdapter.class,
        WikiArticleRevisionQueryAdapter.class,
        WikiCoverOrphanPersistenceAdapter.class,
        CommonMarkWikiMarkdownImageExtractor.class,
        WikiImageReferenceSynchronizer.class,
        DefaultSlugGeneratorAdapter.class,
        UuidGeneratorAdapter.class,
        CreateWikiArticleUseCase.class,
        UpdateDraftWikiArticleUseCase.class,
        WikiCoverOrphanAttachVsFenceConcurrencyIntegrationTest.TestConfig.class
})
@DisplayName("Wiki Cover Orphan Attach vs Fence Concurrency Integration Tests (MySQL)")
class WikiCoverOrphanAttachVsFenceConcurrencyIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        public MediaContract mediaContract() {
            return mock(MediaContract.class);
        }

        @Bean
        public WikiCoverMediaCoordinator mediaCoordinator() {
            return mock(WikiCoverMediaCoordinator.class);
        }

        @Bean
        public ClockPort clockPort() {
            return Instant::now;
        }
    }

    @Autowired
    private SpringDataWikiCoverOrphanJpaRepository jpaRepository;

    @Autowired
    private WikiCoverOrphanPersistenceAdapter orphanAdapter;

    @Autowired
    private WikiArticleRepositoryPort articleRepositoryPort;

    @Autowired
    private CreateWikiArticleUseCase createWikiArticleUseCase;

    @Autowired
    private UpdateDraftWikiArticleUseCase updateDraftWikiArticleUseCase;

    @Autowired
    private MediaContract mediaContract;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TransactionTemplate transactionTemplate;

    private final List<UUID> createdArticleIds = new ArrayList<>();
    private final List<UUID> createdAssetIds = new ArrayList<>();
    private final Instant baseNow = Instant.parse("2026-09-24T12:00:00Z");

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void tearDown() {
        for (UUID articleId : createdArticleIds) {
            jdbcTemplate.update("DELETE FROM wiki_article_revisions WHERE article_id = ?", articleId.toString());
            jdbcTemplate.update("DELETE FROM wiki_articles WHERE id = ?", articleId.toString());
        }
        for (UUID id : createdAssetIds) {
            jpaRepository.deleteById(id.toString());
        }
        createdArticleIds.clear();
        createdAssetIds.clear();
    }

    @Test
    @DisplayName("Real Race — Attach Wins: Concurrent attach coordinates orphan deletion, forcing prepareDeletionFence to abort with LOST_OWNERSHIP")
    void shouldLetAttachWinAndAbortFenceWhenAttachAcquiresSerializationPointFirst() throws Exception {
        UUID assetId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        createdAssetIds.add(assetId);

        // 1. Initial State: Article exists with NO cover
        WikiArticleDTO created = createWikiArticleUseCase.execute(new CreateWikiArticleCommand(
                "Bài viết ban đầu chưa có cover",
                ArticleType.CHARACTER,
                "Tóm tắt bài viết",
                "Nội dung bài viết",
                "Khởi tạo",
                actorId,
                null,
                50,
                50
        ));
        UUID articleId = created.id();
        createdArticleIds.add(articleId);

        // 2. Initial State: Asset A is in PROCESSING with token X (unreferenced)
        Instant firstSeen = baseNow.minus(Duration.ofDays(35));
        orphanAdapter.recordOrphanObservation(assetId, firstSeen);

        UUID tokenX = UUID.randomUUID();
        boolean claimed = orphanAdapter.claimIfEligible(assetId, baseNow, tokenX, baseNow);
        assertThat(claimed).isTrue();

        Optional<WikiCoverOrphanRecord> initialRecord = orphanAdapter.findByMediaAssetId(assetId);
        assertThat(initialRecord).isPresent();
        assertThat(initialRecord.get().status()).isEqualTo(WikiCoverOrphanStatus.PROCESSING);

        // Coordination latches:
        CountDownLatch attachAcquiredSerializationLatch = new CountDownLatch(1);
        CountDownLatch resumeAttachCommitLatch = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // Thread 1: Real Attach Transaction
            Future<Boolean> attachFuture = executor.submit(() ->
                    transactionTemplate.execute(status -> {
                        // 1. Enter attachment path: coordinateCoverAttachment acquires row lock & deletes PROCESSING orphan
                        orphanAdapter.coordinateCoverAttachment(assetId);

                        // 2. Lock article and update cover to assetId
                        WikiArticle article = articleRepositoryPort.findById(articleId).orElseThrow();
                        article.updateDraft(
                                article.getTitle(),
                                article.getSlug(),
                                article.getArticleType(),
                                article.getSummary(),
                                article.getContent(),
                                assetId,
                                50,
                                50,
                                actorId,
                                baseNow
                        );
                        articleRepositoryPort.save(article);
                        articleRepositoryPort.flush();

                        // 3. Signal that attach has acquired the serialization point
                        attachAcquiredSerializationLatch.countDown();

                        // 4. Wait for fence transaction to attempt prepareDeletionFence
                        try {
                            boolean unblocked = resumeAttachCommitLatch.await(10, TimeUnit.SECONDS);
                            if (!unblocked) {
                                throw new RuntimeException("Attach transaction timeout waiting for fence attempt");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                        return true;
                    })
            );

            // Thread 2: Concurrent Fence Worker Transaction
            Future<WikiCoverOrphanRepositoryPort.PrepareDeletionFenceResult> fenceFuture = executor.submit(() -> {
                // Wait until Thread 1 has acquired the serialization point and holds the row lock
                boolean started = attachAcquiredSerializationLatch.await(10, TimeUnit.SECONDS);
                if (!started) {
                    throw new RuntimeException("Fence worker timeout waiting for attach to start");
                }

                // In MySQL InnoDB, Thread 2's SELECT FOR UPDATE will wait for Thread 1's transaction to resolve
                WikiCoverOrphanRepositoryPort.PrepareDeletionFenceResult res =
                        orphanAdapter.prepareDeletionFence(assetId, tokenX, baseNow);

                // If fence succeeded, worker would proceed to delete Media
                if (res == WikiCoverOrphanRepositoryPort.PrepareDeletionFenceResult.FENCED_FOR_DELETION) {
                    mediaContract.delete(assetId);
                    orphanAdapter.deleteClaimedDeletingEpoch(assetId, tokenX);
                }
                return res;
            });

            // Signal Thread 1 to proceed with commit
            resumeAttachCommitLatch.countDown();

            // Both threads terminate within bounded timeout (no deadlock, no timeout)
            Boolean attachSuccess = attachFuture.get(10, TimeUnit.SECONDS);
            WikiCoverOrphanRepositoryPort.PrepareDeletionFenceResult fenceResult = fenceFuture.get(10, TimeUnit.SECONDS);

            assertThat(attachSuccess).isTrue();
            // Fence worker detects lost ownership because row was deleted by attach
            assertThat(fenceResult).isEqualTo(WikiCoverOrphanRepositoryPort.PrepareDeletionFenceResult.LOST_OWNERSHIP);

            // Verify final DB state:
            Optional<WikiArticle> articleAfter = articleRepositoryPort.findById(articleId);
            assertThat(articleAfter).isPresent();
            assertThat(articleAfter.get().getCoverMediaAssetId()).isEqualTo(assetId);

            Optional<WikiCoverOrphanRecord> orphanAfter = orphanAdapter.findByMediaAssetId(assetId);
            assertThat(orphanAfter).isEmpty();

            // Media delete was NEVER invoked
            verify(mediaContract, never()).delete(assetId);

            // Explicit assertion of IMPOSSIBLE combined outcome:
            boolean articleReferencesA = assetId.equals(articleAfter.get().getCoverMediaAssetId());
            boolean workerAuthorizedToDeleteA = (fenceResult == WikiCoverOrphanRepositoryPort.PrepareDeletionFenceResult.FENCED_FOR_DELETION);
            assertThat(articleReferencesA && workerAuthorizedToDeleteA).isFalse();

        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Real Race — Fence Wins: Committed DELETING fence blocks concurrent production attachment with WikiCoverMediaAssetDeletingException")
    void shouldLetFenceWinAndBlockAttachmentWhenFenceCommitsFirst() throws Exception {
        UUID assetId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        createdAssetIds.add(assetId);

        // 1. Initial State: Article exists with NO cover
        WikiArticleDTO created = createWikiArticleUseCase.execute(new CreateWikiArticleCommand(
                "Bài viết ban đầu chưa có cover",
                ArticleType.CHARACTER,
                "Tóm tắt bài viết",
                "Nội dung bài viết",
                "Khởi tạo",
                actorId,
                null,
                50,
                50
        ));
        UUID articleId = created.id();
        createdArticleIds.add(articleId);

        // 2. Initial State: Asset A is in PROCESSING with token X (zero Wiki references)
        Instant firstSeen = baseNow.minus(Duration.ofDays(35));
        orphanAdapter.recordOrphanObservation(assetId, firstSeen);

        UUID tokenX = UUID.randomUUID();
        boolean claimed = orphanAdapter.claimIfEligible(assetId, baseNow, tokenX, baseNow);
        assertThat(claimed).isTrue();

        CountDownLatch fenceCommittedLatch = new CountDownLatch(1);
        CountDownLatch resumeWorkerDeleteLatch = new CountDownLatch(1);
        AtomicBoolean mediaDeleteInvoked = new AtomicBoolean(false);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // Thread 1: Fence Worker Transaction
            Future<WikiCoverOrphanRepositoryPort.PrepareDeletionFenceResult> fenceFuture = executor.submit(() -> {
                // 1. Establish and commit DELETING fence in DB
                WikiCoverOrphanRepositoryPort.PrepareDeletionFenceResult res =
                        orphanAdapter.prepareDeletionFence(assetId, tokenX, baseNow);

                // 2. Signal that DELETING fence is durably committed in DB
                fenceCommittedLatch.countDown();

                // 3. Pause before executing Media deletion
                boolean unblocked = resumeWorkerDeleteLatch.await(10, TimeUnit.SECONDS);
                if (!unblocked) {
                    throw new RuntimeException("Worker timeout waiting for attach attempt");
                }

                // 4. Worker executes media deletion
                if (res == WikiCoverOrphanRepositoryPort.PrepareDeletionFenceResult.FENCED_FOR_DELETION) {
                    mediaContract.delete(assetId);
                    mediaDeleteInvoked.set(true);
                    orphanAdapter.deleteClaimedDeletingEpoch(assetId, tokenX);
                }
                return res;
            });

            // Thread 2: Concurrent Production Attachment Path
            Future<?> attachFuture = executor.submit(() -> {
                // Wait until fence transaction has committed DELETING
                boolean fenceDone = fenceCommittedLatch.await(10, TimeUnit.SECONDS);
                if (!fenceDone) {
                    throw new RuntimeException("Attach timeout waiting for fence commit");
                }

                // Attempt production attachment to asset A using UpdateDraftWikiArticleUseCase
                UpdateDraftWikiArticleCommand cmd = new UpdateDraftWikiArticleCommand(
                        articleId,
                        "Bài viết đã tạo",
                        ArticleType.CHARACTER,
                        "Tóm tắt",
                        "Nội dung",
                        "Attach cover A",
                        actorId,
                        assetId,
                        50,
                        50,
                        true,
                        WikiCoverIntent.ATTACH_NEW_ASSET,
                        null
                );
                updateDraftWikiArticleUseCase.execute(cmd);
                return null;
            });

            // Thread 2 MUST fail with WikiCoverMediaAssetDeletingException
            assertThatThrownBy(() -> attachFuture.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(WikiCoverMediaAssetDeletingException.class);

            // Assert that article does NOT reference A before Media deletion is allowed
            Optional<WikiArticle> articleMid = articleRepositoryPort.findById(articleId);
            assertThat(articleMid).isPresent();
            assertThat(articleMid.get().getCoverMediaAssetId()).isNull();

            // Verify orphan remains DELETING in DB
            Optional<WikiCoverOrphanRecord> orphanMid = orphanAdapter.findByMediaAssetId(assetId);
            assertThat(orphanMid).isPresent();
            assertThat(orphanMid.get().status()).isEqualTo(WikiCoverOrphanStatus.DELETING);

            // Now unblock worker to proceed with Media deletion
            resumeWorkerDeleteLatch.countDown();

            WikiCoverOrphanRepositoryPort.PrepareDeletionFenceResult fenceResult = fenceFuture.get(10, TimeUnit.SECONDS);
            assertThat(fenceResult).isEqualTo(WikiCoverOrphanRepositoryPort.PrepareDeletionFenceResult.FENCED_FOR_DELETION);
            assertThat(mediaDeleteInvoked.get()).isTrue();
            verify(mediaContract).delete(assetId);

            // Final state verification: Article cover remains null
            Optional<WikiArticle> articleFinal = articleRepositoryPort.findById(articleId);
            assertThat(articleFinal).isPresent();
            assertThat(articleFinal.get().getCoverMediaAssetId()).isNull();

            // Explicit assertion of IMPOSSIBLE combined outcome:
            boolean articleReferencesA = assetId.equals(articleFinal.get().getCoverMediaAssetId());
            boolean mediaDeleted = mediaDeleteInvoked.get();
            assertThat(articleReferencesA && mediaDeleted).isFalse();

        } finally {
            executor.shutdownNow();
        }
    }
}
