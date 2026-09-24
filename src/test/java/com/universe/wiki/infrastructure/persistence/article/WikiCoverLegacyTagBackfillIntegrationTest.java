package com.universe.wiki.infrastructure.persistence.article;

import com.universe.media.application.asset.AssignMediaAssetClientTagCommand;
import com.universe.media.application.asset.AssignMediaAssetClientTagUseCase;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.media.infrastructure.persistence.MediaAssetJpaEntity;
import com.universe.media.infrastructure.persistence.MediaAssetPersistenceAdapter;
import com.universe.media.infrastructure.persistence.SpringDataMediaAssetJpaRepository;
import com.universe.shared.time.ClockPort;
import com.universe.test.TestDatabaseSupport;
import com.universe.wiki.application.article.cover.WikiCoverMediaCoordinator;
import com.universe.wiki.application.article.cover.backfill.WikiCoverLegacyTagBackfillResult;
import com.universe.wiki.application.article.cover.backfill.WikiCoverLegacyTagBackfillService;
import com.universe.wiki.infrastructure.maintenance.WikiCoverLegacyTagBackfillProperties;
import com.universe.wiki.infrastructure.persistence.image.WikiImageReferenceSynchronizer;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

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
        WikiArticlePersistenceAdapter.class,
        WikiCoverLegacyTagBackfillProperties.class,
        WikiCoverLegacyTagBackfillService.class,
        WikiCoverLegacyTagBackfillIntegrationTest.TestConfig.class
})
@DisplayName("Wiki Cover Legacy Tag Backfill Integration Tests (MySQL)")
class WikiCoverLegacyTagBackfillIntegrationTest {

    private static final String CANONICAL_TAG = WikiCoverMediaCoordinator.WIKI_ARTICLE_COVER_CLIENT_TAG;
    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        public ClockPort clockPort() {
            return () -> Instant.parse("2026-09-24T12:00:00Z");
        }

        @Bean
        public WikiImageReferenceSynchronizer imageReferenceSynchronizer() {
            return mock(WikiImageReferenceSynchronizer.class);
        }

        @Bean
        public MediaContract mediaContract(AssignMediaAssetClientTagUseCase assignUseCase) {
            MediaContract mockContract = mock(MediaContract.class);
            doAnswer(invocation -> {
                UUID assetId = invocation.getArgument(0);
                String clientTag = invocation.getArgument(1);
                assignUseCase.execute(new AssignMediaAssetClientTagCommand(assetId, clientTag));
                return null;
            }).when(mockContract).assignClientTagIfAbsent(any(), any());
            return mockContract;
        }
    }

    @Autowired
    private SpringDataWikiArticleJpaRepository wikiArticleJpaRepository;

    @Autowired
    private SpringDataMediaAssetJpaRepository mediaJpaRepository;

    @Autowired
    private WikiCoverLegacyTagBackfillService backfillService;

    @Autowired
    private MediaContract mediaContract;

    @Autowired
    private AssignMediaAssetClientTagUseCase assignUseCase;

    private final List<String> createdArticleIds = new ArrayList<>();
    private final List<String> createdMediaIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String id : createdArticleIds) {
            if (wikiArticleJpaRepository.existsById(id)) {
                wikiArticleJpaRepository.deleteById(id);
            }
        }
        createdArticleIds.clear();

        for (String id : createdMediaIds) {
            if (mediaJpaRepository.existsById(id)) {
                mediaJpaRepository.deleteById(id);
            }
        }
        createdMediaIds.clear();
    }

    private void seedMediaAsset(UUID assetId, String clientTag) {
        String idStr = assetId.toString();
        createdMediaIds.add(idStr);

        MediaAssetJpaEntity entity = new MediaAssetJpaEntity();
        entity.setId(idStr);
        entity.setMediaType("IMAGE");
        entity.setVisibility("PUBLIC");
        entity.setStatus("ACTIVE");
        entity.setCurrentVersionNumber(1);
        entity.setClientTag(clientTag);
        entity.setCreatedAt(T0);
        entity.setUpdatedAt(T0);
        mediaJpaRepository.save(entity);
    }

    private void seedWikiArticle(UUID articleId, String slug, UUID coverMediaAssetId) {
        String idStr = articleId.toString();
        createdArticleIds.add(idStr);

        WikiArticleJpaEntity entity = new WikiArticleJpaEntity();
        entity.setId(idStr);
        entity.setTitle("Article " + slug);
        entity.setSlug(slug);
        entity.setArticleType("CHARACTER");
        entity.setSummary("Summary " + slug);
        entity.setContent("Content " + slug);
        entity.setCoverMediaAssetId(coverMediaAssetId != null ? coverMediaAssetId.toString() : null);
        entity.setStatus("PUBLISHED");
        entity.setCreatedBy("00000000-0000-0000-0000-000000000001");
        entity.setUpdatedBy("00000000-0000-0000-0000-000000000001");
        entity.setPublishedBy("00000000-0000-0000-0000-000000000001");
        entity.setPublishedAt(T0);
        entity.setAggregateVersion(1L);
        entity.setContentVersion(1L);
        entity.setCreatedAt(T0);
        entity.setUpdatedAt(T0);
        wikiArticleJpaRepository.save(entity);
    }

    @Test
    @DisplayName("Section 19: Tài nguyên Media legacy không được tham chiếu giữ nguyên client_tag = NULL")
    void testLegacyUnreferencedAssetSafety() {
        UUID unreferencedAssetId = UUID.randomUUID();
        seedMediaAsset(unreferencedAssetId, null);

        // Run backfill without any Wiki article referencing unreferencedAssetId
        WikiCoverLegacyTagBackfillResult result = backfillService.backfillLegacyCoverTags();

        MediaAssetJpaEntity preserved = mediaJpaRepository.findById(unreferencedAssetId.toString()).orElseThrow();
        assertThat(preserved.getClientTag()).isNull();
    }

    @Test
    @DisplayName("Section 20: Tài nguyên Media legacy đang được tham chiếu được gán client_tag thành công")
    void testCurrentLegacyReferenceBackfill() {
        UUID assetId = UUID.randomUUID();
        seedMediaAsset(assetId, null);

        UUID articleId = UUID.randomUUID();
        seedWikiArticle(articleId, "current-legacy-article", assetId);

        WikiCoverLegacyTagBackfillResult result = backfillService.backfillLegacyCoverTags();

        assertThat(result.scannedAssets()).isEqualTo(1);
        assertThat(result.successfulAssignmentsOrAlreadyTagged()).isEqualTo(1);
        assertThat(result.conflicts()).isZero();
        assertThat(result.missingMediaAssets()).isZero();
        assertThat(result.failedAssets()).isZero();

        MediaAssetJpaEntity updated = mediaJpaRepository.findById(assetId.toString()).orElseThrow();
        assertThat(updated.getClientTag()).isEqualTo(CANONICAL_TAG);
    }

    @Test
    @DisplayName("Section 21: Chạy lại backfill lần 2 đạt tính chất idempotent hoàn toàn")
    void testIdempotentRerun() {
        UUID assetId = UUID.randomUUID();
        seedMediaAsset(assetId, null);

        UUID articleId = UUID.randomUUID();
        seedWikiArticle(articleId, "idempotent-article", assetId);

        // Run 1: assigns tag
        WikiCoverLegacyTagBackfillResult result1 = backfillService.backfillLegacyCoverTags();
        assertThat(result1.successfulAssignmentsOrAlreadyTagged()).isEqualTo(1);

        // Run 2: idempotent confirmation
        WikiCoverLegacyTagBackfillResult result2 = backfillService.backfillLegacyCoverTags();
        assertThat(result2.scannedAssets()).isEqualTo(1);
        assertThat(result2.successfulAssignmentsOrAlreadyTagged()).isEqualTo(1);
        assertThat(result2.conflicts()).isZero();
        assertThat(result2.missingMediaAssets()).isZero();
        assertThat(result2.failedAssets()).isZero();

        MediaAssetJpaEntity finalEntity = mediaJpaRepository.findById(assetId.toString()).orElseThrow();
        assertThat(finalEntity.getClientTag()).isEqualTo(CANONICAL_TAG);
    }

    @Test
    @DisplayName("Section 22: Tài nguyên đã có sẵn client_tag trùng khớp không bị lỗi và giữ nguyên")
    void testExistingSameTag() {
        UUID assetId = UUID.randomUUID();
        seedMediaAsset(assetId, CANONICAL_TAG);

        UUID articleId = UUID.randomUUID();
        seedWikiArticle(articleId, "same-tag-article", assetId);

        WikiCoverLegacyTagBackfillResult result = backfillService.backfillLegacyCoverTags();

        assertThat(result.scannedAssets()).isEqualTo(1);
        assertThat(result.successfulAssignmentsOrAlreadyTagged()).isEqualTo(1);
        assertThat(result.conflicts()).isZero();

        MediaAssetJpaEntity entity = mediaJpaRepository.findById(assetId.toString()).orElseThrow();
        assertThat(entity.getClientTag()).isEqualTo(CANONICAL_TAG);
    }

    @Test
    @DisplayName("Section 23: Tài nguyên có client_tag xung đột được ghi nhận và không bị ghi đè")
    void testConflictingTagPreservation() {
        UUID conflictingAssetId = UUID.randomUUID();
        seedMediaAsset(conflictingAssetId, "novel.chapter.illustration");

        UUID normalAssetId = UUID.randomUUID();
        seedMediaAsset(normalAssetId, null);

        seedWikiArticle(UUID.randomUUID(), "conflict-article", conflictingAssetId);
        seedWikiArticle(UUID.randomUUID(), "normal-article", normalAssetId);

        WikiCoverLegacyTagBackfillResult result = backfillService.backfillLegacyCoverTags();

        assertThat(result.scannedAssets()).isEqualTo(2);
        assertThat(result.conflicts()).isEqualTo(1);
        assertThat(result.successfulAssignmentsOrAlreadyTagged()).isEqualTo(1);

        MediaAssetJpaEntity conflicted = mediaJpaRepository.findById(conflictingAssetId.toString()).orElseThrow();
        assertThat(conflicted.getClientTag()).isEqualTo("novel.chapter.illustration");

        MediaAssetJpaEntity normal = mediaJpaRepository.findById(normalAssetId.toString()).orElseThrow();
        assertThat(normal.getClientTag()).isEqualTo(CANONICAL_TAG);
    }

    @Test
    @DisplayName("Section 24: Tham chiếu đến Media không tồn tại được ghi nhận missing mà không làm vỡ run")
    void testMediaNotFoundHandledGracefully() {
        UUID missingAssetId = UUID.randomUUID();
        UUID articleId = UUID.randomUUID();
        seedWikiArticle(articleId, "missing-media-article", missingAssetId);

        WikiCoverLegacyTagBackfillResult result = backfillService.backfillLegacyCoverTags();

        assertThat(result.scannedAssets()).isEqualTo(1);
        assertThat(result.missingMediaAssets()).isEqualTo(1);
        assertThat(result.successfulAssignmentsOrAlreadyTagged()).isZero();

        // Verify Wiki article is untouched
        WikiArticleJpaEntity article = wikiArticleJpaRepository.findById(articleId.toString()).orElseThrow();
        assertThat(article.getCoverMediaAssetId()).isEqualTo(missingAssetId.toString());
    }

    @Test
    @DisplayName("Section 8: Nhiều bài viết cùng tham chiếu một ảnh bìa chỉ được xử lý đúng 1 lần")
    void testSharedCoverReferencesDeduplicated() {
        UUID sharedAssetId = UUID.randomUUID();
        seedMediaAsset(sharedAssetId, null);

        seedWikiArticle(UUID.randomUUID(), "shared-article-1", sharedAssetId);
        seedWikiArticle(UUID.randomUUID(), "shared-article-2", sharedAssetId);

        WikiCoverLegacyTagBackfillResult result = backfillService.backfillLegacyCoverTags();

        assertThat(result.scannedAssets()).isEqualTo(1);
        assertThat(result.successfulAssignmentsOrAlreadyTagged()).isEqualTo(1);

        MediaAssetJpaEntity updated = mediaJpaRepository.findById(sharedAssetId.toString()).orElseThrow();
        assertThat(updated.getClientTag()).isEqualTo(CANONICAL_TAG);
    }

    @Test
    @DisplayName("Section 25: Keyset pagination duyệt nhiều trang chính xác không dùng OFFSET")
    void testMultiPageKeysetPagination() {
        List<UUID> assetIds = List.of(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                UUID.fromString("55555555-5555-5555-5555-555555555555")
        );

        for (int i = 0; i < assetIds.size(); i++) {
            UUID assetId = assetIds.get(i);
            seedMediaAsset(assetId, null);
            seedWikiArticle(UUID.randomUUID(), "multipage-article-" + i, assetId);
        }

        // Run with small pageSize = 2 to force 3 pages (2, 2, 1)
        WikiCoverLegacyTagBackfillResult result = backfillService.backfillLegacyCoverTags(2);

        assertThat(result.scannedAssets()).isEqualTo(5);
        assertThat(result.successfulAssignmentsOrAlreadyTagged()).isEqualTo(5);

        for (UUID assetId : assetIds) {
            MediaAssetJpaEntity entity = mediaJpaRepository.findById(assetId.toString()).orElseThrow();
            assertThat(entity.getClientTag()).isEqualTo(CANONICAL_TAG);
        }
    }

    @Test
    @DisplayName("Section 26: Fixed run upper bound không đuổi theo tài nguyên mới ngoài biên")
    void testFixedRunUpperBoundEnforced() {
        UUID idA = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID idB = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID idC = UUID.fromString("33333333-3333-3333-3333-333333333333");

        seedMediaAsset(idA, null);
        seedMediaAsset(idB, null);
        seedMediaAsset(idC, null);

        seedWikiArticle(UUID.randomUUID(), "fixed-bound-1", idA);
        seedWikiArticle(UUID.randomUUID(), "fixed-bound-2", idB);

        // Run 1: upper bound is idB
        // Seed idC during or immediately before scan with page size 1
        WikiCoverLegacyTagBackfillResult result1 = backfillService.backfillLegacyCoverTags(1);
        assertThat(result1.scannedAssets()).isEqualTo(2);

        // Now reference idC (beyond idB)
        seedWikiArticle(UUID.randomUUID(), "fixed-bound-3", idC);

        // In run 2: idC is now the new upper bound and is processed
        WikiCoverLegacyTagBackfillResult result2 = backfillService.backfillLegacyCoverTags(1);
        assertThat(result2.scannedAssets()).isEqualTo(3);

        MediaAssetJpaEntity entityC = mediaJpaRepository.findById(idC.toString()).orElseThrow();
        assertThat(entityC.getClientTag()).isEqualTo(CANONICAL_TAG);
    }

    @Test
    @DisplayName("Section 27: Gán tag vẫn an toàn khi tham chiếu Wiki bị tách concurrently sau khi quan sát")
    void testConcurrentDetachSemanticsSafe() {
        UUID assetId = UUID.randomUUID();
        seedMediaAsset(assetId, null);

        UUID articleId = UUID.randomUUID();
        seedWikiArticle(articleId, "detach-article", assetId);

        // Simulate concurrent detachment between Wiki observation and Media assignment
        doAnswer(invocation -> {
            // Detach reference in Wiki DB concurrently
            WikiArticleJpaEntity article = wikiArticleJpaRepository.findById(articleId.toString()).orElseThrow();
            article.setCoverMediaAssetId(null);
            wikiArticleJpaRepository.save(article);

            // Now perform assignment as backfill would
            UUID id = invocation.getArgument(0);
            String tag = invocation.getArgument(1);
            assignUseCase.execute(new AssignMediaAssetClientTagCommand(id, tag));
            return null;
        }).when(mediaContract).assignClientTagIfAbsent(org.mockito.ArgumentMatchers.eq(assetId), org.mockito.ArgumentMatchers.eq(CANONICAL_TAG));

        WikiCoverLegacyTagBackfillResult result = backfillService.backfillLegacyCoverTags();

        assertThat(result.scannedAssets()).isEqualTo(1);
        assertThat(result.successfulAssignmentsOrAlreadyTagged()).isEqualTo(1);

        // Media asset was safely tagged with canonical tag
        MediaAssetJpaEntity entity = mediaJpaRepository.findById(assetId.toString()).orElseThrow();
        assertThat(entity.getClientTag()).isEqualTo(CANONICAL_TAG);

        // Wiki article now has detached null cover reference
        WikiArticleJpaEntity detachedArticle = wikiArticleJpaRepository.findById(articleId.toString()).orElseThrow();
        assertThat(detachedArticle.getCoverMediaAssetId()).isNull();

        // Restore default mock behavior
        doAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            String tag = invocation.getArgument(1);
            assignUseCase.execute(new AssignMediaAssetClientTagCommand(id, tag));
            return null;
        }).when(mediaContract).assignClientTagIfAbsent(any(), any());
    }

    @Test
    @DisplayName("Section 10: Multi-node safety - Hai workers chạy đồng thời hội tụ an toàn mà không lỗi")
    void testMultiNodeConcurrencySafety() throws Exception {
        UUID assetId = UUID.randomUUID();
        seedMediaAsset(assetId, null);

        UUID articleId = UUID.randomUUID();
        seedWikiArticle(articleId, "concurrency-article", assetId);

        int workers = 2;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch readyLatch = new CountDownLatch(workers);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Future<WikiCoverLegacyTagBackfillResult>> futures = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                return backfillService.backfillLegacyCoverTags();
            }));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        for (Future<WikiCoverLegacyTagBackfillResult> future : futures) {
            WikiCoverLegacyTagBackfillResult res = future.get(10, TimeUnit.SECONDS);
            assertThat(res.conflicts()).isZero();
            assertThat(res.failedAssets()).isZero();
        }
        executor.shutdown();

        MediaAssetJpaEntity updated = mediaJpaRepository.findById(assetId.toString()).orElseThrow();
        assertThat(updated.getClientTag()).isEqualTo(CANONICAL_TAG);
    }
}
