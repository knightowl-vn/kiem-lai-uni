package com.universe.wiki.infrastructure.persistence.orphan;

import com.universe.shared.id.UuidGeneratorAdapter;
import com.universe.shared.time.ClockPort;
import com.universe.test.TestDatabaseSupport;
import com.universe.wiki.application.article.create.CreateWikiArticleCommand;
import com.universe.wiki.application.article.create.CreateWikiArticleUseCase;
import com.universe.wiki.application.article.delete.DeleteWikiArticleCommand;
import com.universe.wiki.application.article.delete.DeleteWikiArticleUseCase;
import com.universe.wiki.application.article.update.draft.UpdateDraftWikiArticleCommand;
import com.universe.wiki.application.article.update.draft.UpdateDraftWikiArticleUseCase;
import com.universe.wiki.application.ports.WikiArticleRepositoryPort;
import com.universe.wiki.application.ports.WikiArticleRevisionRepositoryPort;
import com.universe.wiki.application.ports.WikiCoverOrphanRecord;
import com.universe.wiki.contracts.dto.WikiArticleDTO;
import com.universe.wiki.domain.article.ArticleType;
import com.universe.wiki.domain.article.WikiArticle;
import com.universe.wiki.domain.orphan.WikiCoverOrphanStatus;
import com.universe.wiki.infrastructure.markdown.CommonMarkWikiMarkdownImageExtractor;
import com.universe.wiki.infrastructure.persistence.article.WikiArticlePersistenceAdapter;
import com.universe.wiki.infrastructure.persistence.image.WikiImageReferenceSynchronizer;
import com.universe.wiki.infrastructure.persistence.revision.WikiArticleRevisionPersistenceAdapter;
import com.universe.wiki.infrastructure.slug.DefaultSlugGeneratorAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

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
        DeleteWikiArticleUseCase.class,
        WikiCoverLifecycleTransactionalIntegrationTest.TestConfig.class
})
@DisplayName("Wiki Cover Lifecycle Transactional Integration Tests (MySQL)")
class WikiCoverLifecycleTransactionalIntegrationTest {

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

    private static final UUID ACTOR_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WikiArticleRepositoryPort articleRepositoryPort;

    @Autowired
    private WikiArticleRevisionRepositoryPort revisionRepositoryPort;

    @SpyBean
    private WikiCoverOrphanPersistenceAdapter orphanAdapter;

    @Autowired
    private CreateWikiArticleUseCase createWikiArticleUseCase;

    @Autowired
    private UpdateDraftWikiArticleUseCase updateDraftWikiArticleUseCase;

    @Autowired
    private DeleteWikiArticleUseCase deleteWikiArticleUseCase;

    private final List<UUID> trackedArticleIds = new ArrayList<>();
    private final List<UUID> trackedAssetIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Mockito.reset(orphanAdapter);
    }

    @AfterEach
    void tearDown() {
        Mockito.reset(orphanAdapter);
        cleanupDatabase();
    }

    private void cleanupDatabase() {
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
                "Tạo bản nháp kiểm thử",
                ACTOR_ID,
                coverMediaAssetId,
                50,
                50
        );
        WikiArticleDTO created = createWikiArticleUseCase.execute(command);
        trackedArticleIds.add(created.id());
        return created;
    }

    // =========================================================================
    // 1. TRANSACTIONAL ROLLBACK PROOF ON ORPHAN RECORDING FAILURE
    // =========================================================================

    @Test
    @DisplayName("shouldRollbackCoverRemovalWhenOrphanRecordingFails: Article update rolls back completely if orphan recording fails")
    void shouldRollbackCoverRemovalWhenOrphanRecordingFails() {
        UUID assetA = createTrackedAssetId();
        WikiArticleDTO initial = createDraftArticle("Trần Bình An Tx Test 1", assetA);
        UUID articleId = initial.id();

        // Verify initial state in DB
        Optional<WikiArticle> beforeUpdate = articleRepositoryPort.findById(articleId);
        assertThat(beforeUpdate).isPresent();
        assertThat(beforeUpdate.get().getCoverMediaAssetId()).isEqualTo(assetA);
        assertThat(beforeUpdate.get().getAggregateVersion()).isEqualTo(1L);

        // Spy: simulate failure when recording orphan observation
        doThrow(new RuntimeException("Simulated DB failure during orphan recording"))
                .when(orphanAdapter)
                .recordOrphanObservation(any(), any());

        // Attempt to remove cover
        UpdateDraftWikiArticleCommand removeCoverCommand = new UpdateDraftWikiArticleCommand(
                articleId,
                "Trần Bình An Tx Test 1 Updated",
                ArticleType.CHARACTER,
                "Bản tóm tắt cập nhật",
                "# Trần Bình An Tx Test 1 Updated\n\nNội dung mới.",
                "Gỡ bỏ ảnh bìa",
                ACTOR_ID,
                null,
                50,
                50,
                true // updateCover = true with null coverMediaAssetId
        );

        assertThatThrownBy(() -> updateDraftWikiArticleUseCase.execute(removeCoverCommand))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated DB failure during orphan recording");

        // Verify DB state: article update was ROLLED BACK
        Optional<WikiArticle> afterRollback = articleRepositoryPort.findById(articleId);
        assertThat(afterRollback).isPresent();
        assertThat(afterRollback.get().getCoverMediaAssetId()).isEqualTo(assetA);
        assertThat(afterRollback.get().getTitle()).isEqualTo("Trần Bình An Tx Test 1");
        assertThat(afterRollback.get().getAggregateVersion()).isEqualTo(1L);

        // Orphan row must NOT exist
        assertThat(orphanAdapter.findByMediaAssetId(assetA)).isEmpty();
    }

    @Test
    @DisplayName("shouldRollbackArticleDeletionWhenOrphanRecordingFails: Article deletion rolls back completely if orphan recording fails")
    void shouldRollbackArticleDeletionWhenOrphanRecordingFails() {
        UUID assetA = createTrackedAssetId();
        WikiArticleDTO initial = createDraftArticle("Trần Bình An Tx Test 2", assetA);
        UUID articleId = initial.id();

        // Verify initial state
        assertThat(articleRepositoryPort.findById(articleId)).isPresent();
        assertThat(revisionRepositoryPort.findByArticleIdAndRevisionNumber(articleId, 1L)).isPresent();

        // Spy: simulate failure when recording orphan observation
        doThrow(new RuntimeException("Simulated DB failure during orphan recording on delete"))
                .when(orphanAdapter)
                .recordOrphanObservation(any(), any());

        // Attempt article delete
        DeleteWikiArticleCommand deleteCommand = new DeleteWikiArticleCommand(articleId);

        assertThatThrownBy(() -> deleteWikiArticleUseCase.execute(deleteCommand))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated DB failure during orphan recording on delete");

        // Verify DB state: article and its revisions STILL EXIST
        Optional<WikiArticle> afterRollback = articleRepositoryPort.findById(articleId);
        assertThat(afterRollback).isPresent();
        assertThat(afterRollback.get().getCoverMediaAssetId()).isEqualTo(assetA);
        assertThat(revisionRepositoryPort.findByArticleIdAndRevisionNumber(articleId, 1L)).isPresent();

        // Orphan row must NOT exist
        assertThat(orphanAdapter.findByMediaAssetId(assetA)).isEmpty();
    }

    // =========================================================================
    // 2. SUCCESSFUL ORPHAN OBSERVATION RECORDING
    // =========================================================================

    @Test
    @DisplayName("shouldRecordOrphanObservationWhenCoverRemovedSuccessfully: Removing cover atomically creates PENDING orphan record")
    void shouldRecordOrphanObservationWhenCoverRemovedSuccessfully() {
        UUID assetA = createTrackedAssetId();
        WikiArticleDTO initial = createDraftArticle("Trần Bình An Tx Test 3", assetA);
        UUID articleId = initial.id();

        UpdateDraftWikiArticleCommand removeCoverCommand = new UpdateDraftWikiArticleCommand(
                articleId,
                "Trần Bình An Tx Test 3",
                ArticleType.CHARACTER,
                "Tóm tắt sau khi gỡ ảnh bìa",
                "# Nội dung bài viết",
                "Gỡ bỏ ảnh bìa",
                ACTOR_ID,
                null,
                50,
                50,
                true // updateCover = true
        );

        WikiArticleDTO updated = updateDraftWikiArticleUseCase.execute(removeCoverCommand);
        assertThat(updated.coverMediaAssetId()).isNull();

        // Verify article in DB has no cover
        Optional<WikiArticle> dbArticle = articleRepositoryPort.findById(articleId);
        assertThat(dbArticle).isPresent();
        assertThat(dbArticle.get().getCoverMediaAssetId()).isNull();

        // Verify orphan record in DB
        Optional<WikiCoverOrphanRecord> orphanRecord = orphanAdapter.findByMediaAssetId(assetA);
        assertThat(orphanRecord).isPresent();
        assertThat(orphanRecord.get().mediaAssetId()).isEqualTo(assetA);
        assertThat(orphanRecord.get().status()).isEqualTo(WikiCoverOrphanStatus.PENDING);
        assertThat(orphanRecord.get().claimToken()).isNull();
        assertThat(orphanRecord.get().retryCount()).isZero();
    }

    @Test
    @DisplayName("shouldRecordOrphanObservationWhenArticleDeletedSuccessfully: Deleting article atomically creates PENDING orphan record")
    void shouldRecordOrphanObservationWhenArticleDeletedSuccessfully() {
        UUID assetA = createTrackedAssetId();
        WikiArticleDTO initial = createDraftArticle("Trần Bình An Tx Test 4", assetA);
        UUID articleId = initial.id();

        deleteWikiArticleUseCase.execute(new DeleteWikiArticleCommand(articleId));

        // Verify article is deleted from DB
        assertThat(articleRepositoryPort.findById(articleId)).isEmpty();
        assertThat(revisionRepositoryPort.findByArticleIdAndRevisionNumber(articleId, 1L)).isEmpty();

        // Verify orphan record in DB
        Optional<WikiCoverOrphanRecord> orphanRecord = orphanAdapter.findByMediaAssetId(assetA);
        assertThat(orphanRecord).isPresent();
        assertThat(orphanRecord.get().mediaAssetId()).isEqualTo(assetA);
        assertThat(orphanRecord.get().status()).isEqualTo(WikiCoverOrphanStatus.PENDING);
        assertThat(orphanRecord.get().claimToken()).isNull();
        assertThat(orphanRecord.get().retryCount()).isZero();
    }

    // =========================================================================
    // 3. RE-REFERENCE ORPHAN ROW INVALIDATION
    // =========================================================================

    @Test
    @DisplayName("shouldInvalidateOrphanRowWhenCoverIsReReferencedOnCreate: Creating article with orphaned asset atomically deletes orphan record")
    void shouldInvalidateOrphanRowWhenCoverIsReReferencedOnCreate() {
        UUID assetA = createTrackedAssetId();

        // Pre-seed orphan record
        orphanAdapter.recordOrphanObservation(assetA, Instant.now());
        assertThat(orphanAdapter.findByMediaAssetId(assetA)).isPresent();

        // Create article referencing assetA
        WikiArticleDTO created = createDraftArticle("Trần Bình An Tx Test 5", assetA);

        // Verify article has cover
        assertThat(created.coverMediaAssetId()).isEqualTo(assetA);

        // Verify orphan row was atomically removed
        assertThat(orphanAdapter.findByMediaAssetId(assetA)).isEmpty();
    }

    @Test
    @DisplayName("shouldInvalidateOrphanRowWhenCoverIsReReferencedOnUpdate: Updating article with orphaned asset atomically deletes orphan record")
    void shouldInvalidateOrphanRowWhenCoverIsReReferencedOnUpdate() {
        UUID assetA = createTrackedAssetId();
        WikiArticleDTO initial = createDraftArticle("Trần Bình An Tx Test 6", null);
        UUID articleId = initial.id();

        // Pre-seed orphan record for assetA
        orphanAdapter.recordOrphanObservation(assetA, Instant.now());
        assertThat(orphanAdapter.findByMediaAssetId(assetA)).isPresent();

        // Update draft to attach assetA
        UpdateDraftWikiArticleCommand attachCoverCommand = new UpdateDraftWikiArticleCommand(
                articleId,
                "Trần Bình An Tx Test 6",
                ArticleType.CHARACTER,
                "Tóm tắt gắn bìa",
                "# Nội dung bài viết",
                "Gắn ảnh bìa tái sử dụng",
                ACTOR_ID,
                assetA,
                50,
                50,
                true // updateCover = true
        );

        WikiArticleDTO updated = updateDraftWikiArticleUseCase.execute(attachCoverCommand);
        assertThat(updated.coverMediaAssetId()).isEqualTo(assetA);

        // Verify orphan row was atomically removed
        assertThat(orphanAdapter.findByMediaAssetId(assetA)).isEmpty();
    }

    @Test
    @DisplayName("shouldRecordOldCoverAsOrphanAndInvalidateNewCoverOrphanRowWhenReplaced: Replaces cover, records old and invalidates new in single Tx")
    void shouldRecordOldCoverAsOrphanAndInvalidateNewCoverOrphanRowWhenReplaced() {
        UUID assetOld = createTrackedAssetId();
        UUID assetNew = createTrackedAssetId();

        WikiArticleDTO initial = createDraftArticle("Trần Bình An Tx Test 7", assetOld);
        UUID articleId = initial.id();

        // Pre-seed orphan record for assetNew
        orphanAdapter.recordOrphanObservation(assetNew, Instant.now());
        assertThat(orphanAdapter.findByMediaAssetId(assetNew)).isPresent();
        assertThat(orphanAdapter.findByMediaAssetId(assetOld)).isEmpty();

        // Replace assetOld with assetNew
        UpdateDraftWikiArticleCommand replaceCoverCommand = new UpdateDraftWikiArticleCommand(
                articleId,
                "Trần Bình An Tx Test 7",
                ArticleType.CHARACTER,
                "Tóm tắt đổi bìa",
                "# Nội dung bài viết",
                "Đổi ảnh bìa",
                ACTOR_ID,
                assetNew,
                50,
                50,
                true // updateCover = true
        );

        WikiArticleDTO updated = updateDraftWikiArticleUseCase.execute(replaceCoverCommand);
        assertThat(updated.coverMediaAssetId()).isEqualTo(assetNew);

        // Verify: old cover is now an orphan in DB
        Optional<WikiCoverOrphanRecord> oldOrphan = orphanAdapter.findByMediaAssetId(assetOld);
        assertThat(oldOrphan).isPresent();
        assertThat(oldOrphan.get().status()).isEqualTo(WikiCoverOrphanStatus.PENDING);

        // Verify: new cover orphan row was removed from DB
        assertThat(orphanAdapter.findByMediaAssetId(assetNew)).isEmpty();
    }

    // =========================================================================
    // 4. MULTI-ARTICLE ZERO-REFERENCE RECONCILER TESTS
    // =========================================================================

    @Test
    @DisplayName("shouldNotCreateOrphanWhenCoverRemovedFromOneArticleWhileAnotherStillReferencesIt: Removing cover from one article leaves orphan table empty when second article still references asset")
    void shouldNotCreateOrphanWhenCoverRemovedFromOneArticleWhileAnotherStillReferencesIt() {
        UUID assetA = createTrackedAssetId();
        WikiArticleDTO article1 = createDraftArticle("Trần Bình An Multi 1", assetA);
        WikiArticleDTO article2 = createDraftArticle("Trần Bình An Multi 2", assetA);

        // Verify both articles reference assetA
        assertThat(articleRepositoryPort.findById(article1.id()).orElseThrow().getCoverMediaAssetId()).isEqualTo(assetA);
        assertThat(articleRepositoryPort.findById(article2.id()).orElseThrow().getCoverMediaAssetId()).isEqualTo(assetA);

        // Remove cover from article1
        UpdateDraftWikiArticleCommand removeCoverCommand = new UpdateDraftWikiArticleCommand(
                article1.id(),
                "Trần Bình An Multi 1",
                ArticleType.CHARACTER,
                "Tóm tắt sau khi gỡ ảnh bìa",
                "# Nội dung bài viết",
                "Gỡ bỏ ảnh bìa bài 1",
                ACTOR_ID,
                null,
                50,
                50,
                true
        );
        WikiArticleDTO updated1 = updateDraftWikiArticleUseCase.execute(removeCoverCommand);
        assertThat(updated1.coverMediaAssetId()).isNull();

        // Verify article2 still references assetA
        assertThat(articleRepositoryPort.findById(article2.id()).orElseThrow().getCoverMediaAssetId()).isEqualTo(assetA);

        // Orphan row must NOT exist because assetA is still referenced by article2
        assertThat(orphanAdapter.findByMediaAssetId(assetA)).isEmpty();
    }

    @Test
    @DisplayName("shouldCreateFreshOrphanEpochWhenLastReferencingArticleDetachesCover: Orphan epoch is created only when the last referencing article detaches the cover")
    void shouldCreateFreshOrphanEpochWhenLastReferencingArticleDetachesCover() {
        UUID assetA = createTrackedAssetId();
        WikiArticleDTO article1 = createDraftArticle("Trần Bình An Final Ref 1", assetA);
        WikiArticleDTO article2 = createDraftArticle("Trần Bình An Final Ref 2", assetA);

        // Step 1: Detach assetA from article1 -> still referenced by article2 -> no orphan
        updateDraftWikiArticleUseCase.execute(new UpdateDraftWikiArticleCommand(
                article1.id(), "Trần Bình An Final Ref 1", ArticleType.CHARACTER,
                "Tóm tắt 1", "# Nội dung 1", "Gỡ bìa 1", ACTOR_ID, null, 50, 50, true
        ));
        assertThat(orphanAdapter.findByMediaAssetId(assetA)).isEmpty();

        // Step 2: Detach assetA from article2 -> 0 references remaining -> fresh orphan epoch created
        updateDraftWikiArticleUseCase.execute(new UpdateDraftWikiArticleCommand(
                article2.id(), "Trần Bình An Final Ref 2", ArticleType.CHARACTER,
                "Tóm tắt 2", "# Nội dung 2", "Gỡ bìa 2", ACTOR_ID, null, 50, 50, true
        ));

        // Orphan row must now exist in PENDING status
        Optional<WikiCoverOrphanRecord> orphanRecord = orphanAdapter.findByMediaAssetId(assetA);
        assertThat(orphanRecord).isPresent();
        assertThat(orphanRecord.get().mediaAssetId()).isEqualTo(assetA);
        assertThat(orphanRecord.get().status()).isEqualTo(WikiCoverOrphanStatus.PENDING);
        assertThat(orphanRecord.get().claimToken()).isNull();
        assertThat(orphanRecord.get().retryCount()).isZero();
        assertThat(orphanRecord.get().firstSeenOrphanAt()).isNotNull();
    }

    @Test
    @DisplayName("shouldResetOrphanEpochTimestampWhenReReferencedAndDetachedAgain: Re-referencing an orphan asset clears it, and subsequent detach starts a fresh epoch with new timestamp")
    void shouldResetOrphanEpochTimestampWhenReReferencedAndDetachedAgain() throws InterruptedException {
        UUID assetA = createTrackedAssetId();
        WikiArticleDTO article1 = createDraftArticle("Trần Bình An Epoch 1", assetA);

        // Detach from article1 -> creates initial orphan epoch
        updateDraftWikiArticleUseCase.execute(new UpdateDraftWikiArticleCommand(
                article1.id(), "Trần Bình An Epoch 1", ArticleType.CHARACTER,
                "Tóm tắt 1", "# Nội dung 1", "Gỡ bìa", ACTOR_ID, null, 50, 50, true
        ));

        Optional<WikiCoverOrphanRecord> initialOrphan = orphanAdapter.findByMediaAssetId(assetA);
        assertThat(initialOrphan).isPresent();
        Instant firstSeenOrphanAt1 = initialOrphan.get().firstSeenOrphanAt();

        // Re-reference assetA in a new article -> orphan row must be deleted
        WikiArticleDTO article2 = createDraftArticle("Trần Bình An Epoch 2", assetA);
        assertThat(orphanAdapter.findByMediaAssetId(assetA)).isEmpty();

        // Wait to ensure wall clock advances
        Thread.sleep(50);

        // Detach assetA from article2 -> creates fresh orphan epoch with new timestamp
        updateDraftWikiArticleUseCase.execute(new UpdateDraftWikiArticleCommand(
                article2.id(), "Trần Bình An Epoch 2", ArticleType.CHARACTER,
                "Tóm tắt 2", "# Nội dung 2", "Gỡ bìa lần 2", ACTOR_ID, null, 50, 50, true
        ));

        Optional<WikiCoverOrphanRecord> resetOrphan = orphanAdapter.findByMediaAssetId(assetA);
        assertThat(resetOrphan).isPresent();
        assertThat(resetOrphan.get().mediaAssetId()).isEqualTo(assetA);
        assertThat(resetOrphan.get().status()).isEqualTo(WikiCoverOrphanStatus.PENDING);
        Instant firstSeenOrphanAt2 = resetOrphan.get().firstSeenOrphanAt();
        assertThat(firstSeenOrphanAt2).isAfter(firstSeenOrphanAt1);
    }

    @Test
    @DisplayName("shouldNotLeaveOrphanWhenArticleDeletedWhileAnotherArticleStillReferencesCover: Deleting an article whose cover is also used elsewhere leaves orphan table empty")
    void shouldNotLeaveOrphanWhenArticleDeletedWhileAnotherArticleStillReferencesCover() {
        UUID assetA = createTrackedAssetId();
        WikiArticleDTO article1 = createDraftArticle("Trần Bình An Delete Shared 1", assetA);
        WikiArticleDTO article2 = createDraftArticle("Trần Bình An Delete Shared 2", assetA);

        // Delete article1
        deleteWikiArticleUseCase.execute(new DeleteWikiArticleCommand(article1.id()));

        // Article1 is deleted
        assertThat(articleRepositoryPort.findById(article1.id())).isEmpty();
        // Article2 still references assetA
        assertThat(articleRepositoryPort.findById(article2.id()).orElseThrow().getCoverMediaAssetId()).isEqualTo(assetA);

        // Orphan row must NOT exist because article2 still references assetA
        assertThat(orphanAdapter.findByMediaAssetId(assetA)).isEmpty();
    }
}
