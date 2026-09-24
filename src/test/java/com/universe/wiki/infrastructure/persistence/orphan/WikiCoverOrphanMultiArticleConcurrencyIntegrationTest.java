package com.universe.wiki.infrastructure.persistence.orphan;

import com.universe.shared.id.UuidGeneratorAdapter;
import com.universe.shared.time.ClockPort;
import com.universe.test.TestDatabaseSupport;
import com.universe.wiki.application.article.create.CreateWikiArticleCommand;
import com.universe.wiki.application.article.create.CreateWikiArticleUseCase;
import com.universe.wiki.application.article.update.draft.UpdateDraftWikiArticleCommand;
import com.universe.wiki.application.article.update.draft.UpdateDraftWikiArticleUseCase;
import com.universe.wiki.application.ports.WikiArticleRepositoryPort;
import com.universe.wiki.application.ports.WikiCoverOrphanRecord;
import com.universe.wiki.contracts.dto.WikiArticleDTO;
import com.universe.wiki.domain.article.ArticleType;
import com.universe.wiki.domain.orphan.WikiCoverOrphanStatus;
import com.universe.wiki.infrastructure.markdown.CommonMarkWikiMarkdownImageExtractor;
import com.universe.wiki.infrastructure.persistence.article.WikiArticlePersistenceAdapter;
import com.universe.wiki.infrastructure.persistence.image.WikiImageReferenceSynchronizer;
import com.universe.wiki.infrastructure.persistence.revision.WikiArticleRevisionPersistenceAdapter;
import com.universe.wiki.infrastructure.slug.DefaultSlugGeneratorAdapter;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Import({
        WikiArticlePersistenceAdapter.class,
        WikiArticleRevisionPersistenceAdapter.class,
        WikiCoverOrphanPersistenceAdapter.class,
        CommonMarkWikiMarkdownImageExtractor.class,
        WikiImageReferenceSynchronizer.class,
        DefaultSlugGeneratorAdapter.class,
        UuidGeneratorAdapter.class,
        CreateWikiArticleUseCase.class,
        UpdateDraftWikiArticleUseCase.class,
        WikiCoverOrphanMultiArticleConcurrencyIntegrationTest.TestConfig.class
})
@DisplayName("Wiki Cover Orphan Multi-Article Concurrency Integration Tests (MySQL)")
class WikiCoverOrphanMultiArticleConcurrencyIntegrationTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        public ClockPort clockPort() {
            return Instant::now;
        }
    }

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    private static final UUID ACTOR_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WikiArticleRepositoryPort articleRepositoryPort;

    @Autowired
    private WikiCoverOrphanPersistenceAdapter orphanAdapter;

    @Autowired
    private CreateWikiArticleUseCase createWikiArticleUseCase;

    @Autowired
    private UpdateDraftWikiArticleUseCase updateDraftWikiArticleUseCase;

    private final List<UUID> trackedArticleIds = new ArrayList<>();
    private final List<UUID> trackedAssetIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (UUID id : trackedArticleIds) {
            jdbcTemplate.update("DELETE FROM wiki_revision_image_references WHERE revision_id IN (SELECT id FROM wiki_article_revisions WHERE article_id = ?)", id.toString());
            jdbcTemplate.update("DELETE FROM wiki_article_revisions WHERE article_id = ?", id.toString());
            jdbcTemplate.update("DELETE FROM wiki_article_image_references WHERE article_id = ?", id.toString());
            jdbcTemplate.update("DELETE FROM wiki_articles WHERE id = ?", id.toString());
        }
        for (UUID assetId : trackedAssetIds) {
            jdbcTemplate.update("DELETE FROM wiki_cover_orphans WHERE media_asset_id = ?", assetId.toString());
        }
        trackedArticleIds.clear();
        trackedAssetIds.clear();
    }

    private UUID createTrackedAssetId() {
        UUID id = UUID.randomUUID();
        trackedAssetIds.add(id);
        return id;
    }

    private WikiArticleDTO createDraftArticle(String title, UUID coverMediaAssetId) {
        CreateWikiArticleCommand command = new CreateWikiArticleCommand(
                title,
                ArticleType.CHARACTER,
                "Bản tóm tắt cho " + title,
                "# " + title + "\n\nNội dung chi tiết bài viết.",
                "Tạo bản nháp kiểm thử đồng thời",
                ACTOR_ID,
                coverMediaAssetId,
                50,
                50
        );
        WikiArticleDTO created = createWikiArticleUseCase.execute(command);
        trackedArticleIds.add(created.id());
        return created;
    }

    @Test
    @DisplayName("Concurrent detaches of last two referencing articles converge to exactly one orphan row with no errors")
    void shouldConvergeToSingleOrphanRowWhenLastTwoReferencingArticlesConcurrentlyDetachCover() throws Exception {
        UUID assetA = createTrackedAssetId();

        WikiArticleDTO article1 = createDraftArticle("Concurrent Article 1", assetA);
        WikiArticleDTO article2 = createDraftArticle("Concurrent Article 2", assetA);

        // Pre-condition: both articles reference assetA, orphan row does NOT exist
        assertThat(articleRepositoryPort.findById(article1.id()).orElseThrow().getCoverMediaAssetId()).isEqualTo(assetA);
        assertThat(articleRepositoryPort.findById(article2.id()).orElseThrow().getCoverMediaAssetId()).isEqualTo(assetA);
        assertThat(orphanAdapter.findByMediaAssetId(assetA)).isEmpty();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch readyLatch = new CountDownLatch(2);
            CountDownLatch startLatch = new CountDownLatch(1);

            Future<?> future1 = executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                updateDraftWikiArticleUseCase.execute(new UpdateDraftWikiArticleCommand(
                        article1.id(),
                        "Concurrent Article 1",
                        ArticleType.CHARACTER,
                        "Tóm tắt 1",
                        "# Nội dung 1",
                        "Gỡ bìa đồng thời 1",
                        ACTOR_ID,
                        null,
                        50,
                        50,
                        true
                ));
                return null;
            });

            Future<?> future2 = executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                updateDraftWikiArticleUseCase.execute(new UpdateDraftWikiArticleCommand(
                        article2.id(),
                        "Concurrent Article 2",
                        ArticleType.CHARACTER,
                        "Tóm tắt 2",
                        "# Nội dung 2",
                        "Gỡ bìa đồng thời 2",
                        ACTOR_ID,
                        null,
                        50,
                        50,
                        true
                ));
                return null;
            });

            readyLatch.await(5, TimeUnit.SECONDS);
            startLatch.countDown();

            future1.get(15, TimeUnit.SECONDS);
            future2.get(15, TimeUnit.SECONDS);

            // Both articles successfully detached cover
            assertThat(articleRepositoryPort.findById(article1.id()).orElseThrow().getCoverMediaAssetId()).isNull();
            assertThat(articleRepositoryPort.findById(article2.id()).orElseThrow().getCoverMediaAssetId()).isNull();

            // Exactly one orphan row exists for assetA in PENDING status
            Optional<WikiCoverOrphanRecord> orphanRecord = orphanAdapter.findByMediaAssetId(assetA);
            assertThat(orphanRecord).isPresent();
            assertThat(orphanRecord.get().mediaAssetId()).isEqualTo(assetA);
            assertThat(orphanRecord.get().status()).isEqualTo(WikiCoverOrphanStatus.PENDING);
            assertThat(orphanRecord.get().claimToken()).isNull();
            assertThat(orphanRecord.get().retryCount()).isZero();
            assertThat(orphanRecord.get().firstSeenOrphanAt()).isNotNull();

            // Verify count in DB directly: exactly 1 row
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM wiki_cover_orphans WHERE media_asset_id = ?",
                    Integer.class,
                    assetA.toString()
            );
            assertThat(count).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Concurrent Attach and Detach of same asset across articles completes without deadlock or false orphan")
    void shouldHandleConcurrentAttachAndDetachSafelyWithoutDeadlockOrFalseOrphan() throws Exception {
        UUID assetA = createTrackedAssetId();

        WikiArticleDTO articleX = createDraftArticle("Article X", assetA);
        WikiArticleDTO articleY = createDraftArticle("Article Y", null);

        // Pre-condition: X has assetA, Y has null, no orphan row
        assertThat(articleRepositoryPort.findById(articleX.id()).orElseThrow().getCoverMediaAssetId()).isEqualTo(assetA);
        assertThat(articleRepositoryPort.findById(articleY.id()).orElseThrow().getCoverMediaAssetId()).isNull();
        assertThat(orphanAdapter.findByMediaAssetId(assetA)).isEmpty();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch readyLatch = new CountDownLatch(2);
            CountDownLatch startLatch = new CountDownLatch(1);

            // Tx1: Detach assetA from articleX
            Future<?> future1 = executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                updateDraftWikiArticleUseCase.execute(new UpdateDraftWikiArticleCommand(
                        articleX.id(),
                        "Article X",
                        ArticleType.CHARACTER,
                        "Tóm tắt X",
                        "# Nội dung X",
                        "Gỡ bìa A",
                        ACTOR_ID,
                        null,
                        50,
                        50,
                        true
                ));
                return null;
            });

            // Tx2: Attach assetA to articleY
            Future<?> future2 = executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                updateDraftWikiArticleUseCase.execute(new UpdateDraftWikiArticleCommand(
                        articleY.id(),
                        "Article Y",
                        ArticleType.CHARACTER,
                        "Tóm tắt Y",
                        "# Nội dung Y",
                        "Gán bìa A",
                        ACTOR_ID,
                        assetA,
                        50,
                        50,
                        true
                ));
                return null;
            });

            readyLatch.await(5, TimeUnit.SECONDS);
            startLatch.countDown();

            future1.get(15, TimeUnit.SECONDS);
            future2.get(15, TimeUnit.SECONDS);

            // Final state: articleX has no cover, articleY has assetA
            assertThat(articleRepositoryPort.findById(articleX.id()).orElseThrow().getCoverMediaAssetId()).isNull();
            assertThat(articleRepositoryPort.findById(articleY.id()).orElseThrow().getCoverMediaAssetId()).isEqualTo(assetA);

            // assetA is actively referenced by articleY -> MUST NOT be an orphan
            assertThat(orphanAdapter.findByMediaAssetId(assetA)).isEmpty();
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM wiki_cover_orphans WHERE media_asset_id = ?",
                    Integer.class,
                    assetA.toString()
            );
            assertThat(count).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Concurrent Cross-Swap (X: A->B, Y: B->A) completes safely without deadlock due to deterministic lock ordering")
    void shouldHandleConcurrentCrossSwapBetweenArticlesWithoutDeadlockOrFalseOrphan() throws Exception {
        UUID assetA = createTrackedAssetId();
        UUID assetB = createTrackedAssetId();

        WikiArticleDTO articleX = createDraftArticle("Article X Cross", assetA);
        WikiArticleDTO articleY = createDraftArticle("Article Y Cross", assetB);

        // Pre-condition: X has A, Y has B, neither is orphan
        assertThat(articleRepositoryPort.findById(articleX.id()).orElseThrow().getCoverMediaAssetId()).isEqualTo(assetA);
        assertThat(articleRepositoryPort.findById(articleY.id()).orElseThrow().getCoverMediaAssetId()).isEqualTo(assetB);
        assertThat(orphanAdapter.findByMediaAssetId(assetA)).isEmpty();
        assertThat(orphanAdapter.findByMediaAssetId(assetB)).isEmpty();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch readyLatch = new CountDownLatch(2);
            CountDownLatch startLatch = new CountDownLatch(1);

            // Tx1: X transitions A -> B
            Future<?> future1 = executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                updateDraftWikiArticleUseCase.execute(new UpdateDraftWikiArticleCommand(
                        articleX.id(),
                        "Article X Cross",
                        ArticleType.CHARACTER,
                        "Tóm tắt X2",
                        "# Nội dung X2",
                        "Đổi bìa A sang B",
                        ACTOR_ID,
                        assetB,
                        50,
                        50,
                        true
                ));
                return null;
            });

            // Tx2: Y transitions B -> A
            Future<?> future2 = executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                updateDraftWikiArticleUseCase.execute(new UpdateDraftWikiArticleCommand(
                        articleY.id(),
                        "Article Y Cross",
                        ArticleType.CHARACTER,
                        "Tóm tắt Y2",
                        "# Nội dung Y2",
                        "Đổi bìa B sang A",
                        ACTOR_ID,
                        assetA,
                        50,
                        50,
                        true
                ));
                return null;
            });

            readyLatch.await(5, TimeUnit.SECONDS);
            startLatch.countDown();

            future1.get(15, TimeUnit.SECONDS);
            future2.get(15, TimeUnit.SECONDS);

            // Final state: articleX has B, articleY has A
            assertThat(articleRepositoryPort.findById(articleX.id()).orElseThrow().getCoverMediaAssetId()).isEqualTo(assetB);
            assertThat(articleRepositoryPort.findById(articleY.id()).orElseThrow().getCoverMediaAssetId()).isEqualTo(assetA);

            // Neither asset is orphaned because both are actively referenced
            assertThat(orphanAdapter.findByMediaAssetId(assetA)).isEmpty();
            assertThat(orphanAdapter.findByMediaAssetId(assetB)).isEmpty();

            Integer countA = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM wiki_cover_orphans WHERE media_asset_id = ?",
                    Integer.class,
                    assetA.toString()
            );
            Integer countB = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM wiki_cover_orphans WHERE media_asset_id = ?",
                    Integer.class,
                    assetB.toString()
            );
            assertThat(countA).isZero();
            assertThat(countB).isZero();
        } finally {
            executor.shutdownNow();
        }
    }
}
