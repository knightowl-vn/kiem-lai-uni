package com.universe.wiki.infrastructure.persistence.orphan;

import com.universe.media.contracts.dto.GenerateImageVariantRequestDTO;
import com.universe.media.contracts.dto.MediaVersionUploadOutcome;
import com.universe.media.contracts.dto.UploadMediaAssetVersionConditionalResponseDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.shared.id.UuidGeneratorAdapter;
import com.universe.shared.time.ClockPort;
import com.universe.test.TestDatabaseSupport;
import com.universe.wiki.application.article.cover.WikiArticleCoverOrchestrator;
import com.universe.wiki.application.article.cover.WikiCoverMediaCoordinator;
import com.universe.wiki.application.article.cover.WikiCoverUpload;
import com.universe.wiki.application.article.create.CreateAndPublishWikiArticleUseCase;
import com.universe.wiki.application.article.create.CreateWikiArticleCommand;
import com.universe.wiki.application.article.create.CreateWikiArticleUseCase;
import com.universe.wiki.application.article.delete.DeleteWikiArticleUseCase;
import com.universe.wiki.application.article.query.detail.GetWikiArticleDetailUseCase;
import com.universe.wiki.application.article.update.draft.UpdateDraftAndPublishWikiArticleUseCase;
import com.universe.wiki.application.article.update.draft.UpdateDraftWikiArticleCommand;
import com.universe.wiki.application.article.update.draft.UpdateDraftWikiArticleUseCase;
import com.universe.wiki.application.article.update.published.UpdatePublishedWikiArticleUseCase;
import com.universe.wiki.application.exceptions.WikiCoverStaleMutationException;
import com.universe.wiki.application.ports.WikiArticleRepositoryPort;
import com.universe.wiki.contracts.dto.WikiArticleDTO;
import com.universe.wiki.domain.article.ArticleType;
import com.universe.wiki.domain.article.WikiArticle;
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
import org.mockito.Mockito;
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

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        CreateAndPublishWikiArticleUseCase.class,
        UpdateDraftWikiArticleUseCase.class,
        UpdateDraftAndPublishWikiArticleUseCase.class,
        UpdatePublishedWikiArticleUseCase.class,
        DeleteWikiArticleUseCase.class,
        GetWikiArticleDetailUseCase.class,
        WikiCoverMediaCoordinator.class,
        WikiArticleCoverOrchestrator.class,
        WikiCoverDuplicateBinaryReplacementIntegrationTest.TestConfig.class
})
@DisplayName("Wiki Cover Duplicate-Binary Replacement Optimization Integration Tests (MS-05G9)")
class WikiCoverDuplicateBinaryReplacementIntegrationTest {

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
        public ClockPort clockPort() {
            return Instant::now;
        }

        @Bean
        public com.universe.wiki.application.ports.WikiContributionRepositoryPort wikiContributionRepositoryPort() {
            return mock(com.universe.wiki.application.ports.WikiContributionRepositoryPort.class);
        }

        @Bean
        public com.universe.wiki.application.ports.WikiContributionWorkflowEventRepositoryPort wikiContributionWorkflowEventRepositoryPort() {
            return mock(com.universe.wiki.application.ports.WikiContributionWorkflowEventRepositoryPort.class);
        }
    }

    @Autowired
    private WikiArticleCoverOrchestrator orchestrator;

    @Autowired
    private CreateWikiArticleUseCase createWikiArticleUseCase;

    @Autowired
    private UpdateDraftWikiArticleUseCase updateDraftWikiArticleUseCase;

    @Autowired
    private WikiArticleRepositoryPort articleRepositoryPort;

    @Autowired
    private MediaContract mediaContract;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<UUID> createdArticleIds = new ArrayList<>();
    private final List<UUID> createdAssetIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        reset(mediaContract);
    }

    @AfterEach
    void tearDown() {
        for (UUID articleId : createdArticleIds) {
            jdbcTemplate.update("DELETE FROM wiki_article_revisions WHERE article_id = ?", articleId.toString());
            jdbcTemplate.update("DELETE FROM wiki_articles WHERE id = ?", articleId.toString());
        }
        for (UUID assetId : createdAssetIds) {
            jdbcTemplate.update("DELETE FROM wiki_cover_orphans WHERE media_asset_id = ?", assetId.toString());
        }
        createdArticleIds.clear();
        createdAssetIds.clear();
    }

    private WikiArticleDTO createDraftArticleWithCover(UUID coverId, UUID actorId) {
        createdAssetIds.add(coverId);
        WikiArticleDTO created = createWikiArticleUseCase.execute(new CreateWikiArticleCommand(
                "Tiêu đề bài viết " + UUID.randomUUID(),
                ArticleType.CHARACTER,
                "Tóm tắt ban đầu",
                "Nội dung bài viết ban đầu",
                "Khởi tạo",
                actorId,
                coverId,
                50,
                50
        ));
        createdArticleIds.add(created.id());
        return created;
    }

    @Test
    @DisplayName("Scenario A: Identical replacement binary (UNCHANGED) with no focal/text change leaves versions unchanged and skips variant")
    void shouldHandleIdenticalReplacementWithoutVersionIncrementOrVariantGeneration() {
        UUID coverA = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        WikiArticleDTO initialArticle = createDraftArticleWithCover(coverA, actorId);
        UUID articleId = initialArticle.id();

        long initialAggregateVersion = initialArticle.aggregateVersion();
        long initialContentVersion = initialArticle.contentVersion();

        // Configure MediaContract to report identical binary: UNCHANGED
        when(mediaContract.uploadVersionIfContentChanged(any())).thenReturn(
                UploadMediaAssetVersionConditionalResponseDTO.unchanged(coverA, 1)
        );

        WikiCoverUpload upload = new WikiCoverUpload(
                new ByteArrayInputStream("identical-bytes".getBytes()),
                15L,
                "image/png",
                "cover-same.png"
        );

        UpdateDraftWikiArticleCommand command = new UpdateDraftWikiArticleCommand(
                articleId,
                initialArticle.title(),
                ArticleType.valueOf(initialArticle.articleType()),
                initialArticle.summary(),
                initialArticle.content(),
                "Upload lại ảnh bìa y hệt",
                actorId,
                initialArticle.coverPositionX(),
                initialArticle.coverPositionY()
        );

        WikiArticleDTO updated = orchestrator.updateDraft(command, upload, false);

        // Assertions: cover ID unchanged, version numbers unchanged (+0)
        assertThat(updated.coverMediaAssetId()).isEqualTo(coverA);
        assertThat(updated.aggregateVersion()).isEqualTo(initialAggregateVersion);
        assertThat(updated.contentVersion()).isEqualTo(initialContentVersion);

        // Verify mediaContract.uploadVersionIfContentChanged was called
        verify(mediaContract).uploadVersionIfContentChanged(any());

        // Verify variant generation was NEVER called on UNCHANGED
        verify(mediaContract, never()).generateImageVariant(any());

        // Verify database row
        WikiArticle liveArticle = articleRepositoryPort.findById(articleId).orElseThrow();
        assertThat(liveArticle.getCoverMediaAssetId()).isEqualTo(coverA);
        assertThat(liveArticle.getAggregateVersion()).isEqualTo(initialAggregateVersion);
        assertThat(liveArticle.getContentVersion()).isEqualTo(initialContentVersion);
    }

    @Test
    @DisplayName("Scenario B: Identical replacement binary (UNCHANGED) with focal change increments aggregateVersion exactly once")
    void shouldIncrementAggregateVersionWhenIdenticalBinaryHasFocalChange() {
        UUID coverA = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        WikiArticleDTO initialArticle = createDraftArticleWithCover(coverA, actorId);
        UUID articleId = initialArticle.id();

        long initialAggregateVersion = initialArticle.aggregateVersion();
        long initialContentVersion = initialArticle.contentVersion();

        when(mediaContract.uploadVersionIfContentChanged(any())).thenReturn(
                UploadMediaAssetVersionConditionalResponseDTO.unchanged(coverA, 1)
        );

        WikiCoverUpload upload = new WikiCoverUpload(
                new ByteArrayInputStream("identical-bytes".getBytes()),
                15L,
                "image/png",
                "cover.png"
        );

        // Focal changes to (30, 70)
        UpdateDraftWikiArticleCommand command = new UpdateDraftWikiArticleCommand(
                articleId,
                initialArticle.title(),
                ArticleType.valueOf(initialArticle.articleType()),
                initialArticle.summary(),
                initialArticle.content(),
                "Đổi điểm căn ảnh bìa",
                actorId,
                30,
                70
        );

        WikiArticleDTO updated = orchestrator.updateDraft(command, upload, false);

        assertThat(updated.coverMediaAssetId()).isEqualTo(coverA);
        assertThat(updated.coverPositionX()).isEqualTo(30);
        assertThat(updated.coverPositionY()).isEqualTo(70);
        assertThat(updated.aggregateVersion()).isEqualTo(initialAggregateVersion + 1);
        assertThat(updated.contentVersion()).isEqualTo(initialContentVersion);

        verify(mediaContract, never()).generateImageVariant(any());
    }

    @Test
    @DisplayName("Scenario C: Different replacement binary (VERSION_CREATED) triggers variant generation and preserves stable ID")
    void shouldRequestVariantWhenBinaryIsDifferent() {
        UUID coverA = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        WikiArticleDTO initialArticle = createDraftArticleWithCover(coverA, actorId);
        UUID articleId = initialArticle.id();

        long initialAggregateVersion = initialArticle.aggregateVersion();
        long initialContentVersion = initialArticle.contentVersion();

        when(mediaContract.uploadVersionIfContentChanged(any())).thenReturn(
                UploadMediaAssetVersionConditionalResponseDTO.versionCreated(coverA, 2)
        );

        WikiCoverUpload upload = new WikiCoverUpload(
                new ByteArrayInputStream("new-different-bytes".getBytes()),
                19L,
                "image/jpeg",
                "cover-v2.jpg"
        );

        UpdateDraftWikiArticleCommand command = new UpdateDraftWikiArticleCommand(
                articleId,
                initialArticle.title(),
                ArticleType.valueOf(initialArticle.articleType()),
                initialArticle.summary(),
                initialArticle.content(),
                "Upload ảnh bìa mới khác biệt",
                actorId,
                initialArticle.coverPositionX(),
                initialArticle.coverPositionY()
        );

        WikiArticleDTO updated = orchestrator.updateDraft(command, upload, false);

        assertThat(updated.coverMediaAssetId()).isEqualTo(coverA);
        assertThat(updated.aggregateVersion()).isEqualTo(initialAggregateVersion);
        assertThat(updated.contentVersion()).isEqualTo(initialContentVersion);

        // Variant generation was requested
        verify(mediaContract).generateImageVariant(any(GenerateImageVariantRequestDTO.class));
    }

    @Test
    @DisplayName("Scenario D: Stale duplicate replacement race throws WikiCoverStaleMutationException and preserves null cover")
    void shouldRejectDuplicateReplacementWhenLiveCoverWasConcurrentlyRemoved() {
        UUID coverA = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        WikiArticleDTO initialArticle = createDraftArticleWithCover(coverA, actorId);
        UUID articleId = initialArticle.id();

        // User 1 uploads duplicate bytes.
        // During media upload, User 2 concurrently removes cover A on the live article!
        when(mediaContract.uploadVersionIfContentChanged(any())).thenAnswer(invocation -> {
            updateDraftWikiArticleUseCase.execute(new UpdateDraftWikiArticleCommand(
                    articleId,
                    initialArticle.title(),
                    ArticleType.valueOf(initialArticle.articleType()),
                    initialArticle.summary(),
                    initialArticle.content(),
                    "User 2 đồng thời xóa ảnh bìa",
                    actorId,
                    null,
                    50,
                    50,
                    true
            ));
            return UploadMediaAssetVersionConditionalResponseDTO.unchanged(coverA, 1);
        });

        WikiCoverUpload upload = new WikiCoverUpload(
                new ByteArrayInputStream("identical-bytes".getBytes()),
                15L,
                "image/png",
                "cover.png"
        );

        UpdateDraftWikiArticleCommand user1Command = new UpdateDraftWikiArticleCommand(
                articleId,
                initialArticle.title(),
                ArticleType.valueOf(initialArticle.articleType()),
                initialArticle.summary(),
                initialArticle.content(),
                "User 1 thử thay thế ảnh bìa trùng nội dung",
                actorId,
                50,
                50
        );

        // Expected: WikiCoverStaleMutationException thrown because expectedCoverId != null
        assertThatThrownBy(() -> orchestrator.updateDraft(user1Command, upload, false))
                .isInstanceOf(WikiCoverStaleMutationException.class);

        // Expected: Stable cover A was NEVER compensation-deleted
        verify(mediaContract, never()).delete(coverA);

        // Expected: Live article in database remains cover = null (cover A was NOT resurrected)
        WikiArticle liveArticle = articleRepositoryPort.findById(articleId).orElseThrow();
        assertThat(liveArticle.getCoverMediaAssetId()).isNull();
    }

    @Test
    @DisplayName("Scenario E: Text edit + identical binary uploads UNCHANGED and increments versions per text semantics")
    void shouldIncrementVersionsWhenTextChangesWithIdenticalBinary() {
        UUID coverA = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        WikiArticleDTO initialArticle = createDraftArticleWithCover(coverA, actorId);
        UUID articleId = initialArticle.id();

        long initialAggregateVersion = initialArticle.aggregateVersion();
        long initialContentVersion = initialArticle.contentVersion();

        when(mediaContract.uploadVersionIfContentChanged(any())).thenReturn(
                UploadMediaAssetVersionConditionalResponseDTO.unchanged(coverA, 1)
        );

        WikiCoverUpload upload = new WikiCoverUpload(
                new ByteArrayInputStream("identical-bytes".getBytes()),
                15L,
                "image/png",
                "cover.png"
        );

        // Text content changes
        UpdateDraftWikiArticleCommand command = new UpdateDraftWikiArticleCommand(
                articleId,
                initialArticle.title(),
                ArticleType.valueOf(initialArticle.articleType()),
                initialArticle.summary(),
                "Nội dung bài viết đã được sửa đổi cập nhật mới hoàn toàn.",
                "Cập nhật nội dung bài viết",
                actorId,
                initialArticle.coverPositionX(),
                initialArticle.coverPositionY()
        );

        WikiArticleDTO updated = orchestrator.updateDraft(command, upload, false);

        assertThat(updated.coverMediaAssetId()).isEqualTo(coverA);
        assertThat(updated.aggregateVersion()).isEqualTo(initialAggregateVersion + 1);
        assertThat(updated.contentVersion()).isEqualTo(initialContentVersion + 1);

        verify(mediaContract, never()).generateImageVariant(any());
    }
}
