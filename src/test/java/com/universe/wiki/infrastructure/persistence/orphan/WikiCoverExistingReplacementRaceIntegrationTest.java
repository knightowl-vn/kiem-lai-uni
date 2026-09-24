package com.universe.wiki.infrastructure.persistence.orphan;

import com.universe.media.contracts.dto.UploadMediaAssetVersionResponseDTO;
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
        WikiCoverExistingReplacementRaceIntegrationTest.TestConfig.class
})
@DisplayName("Wiki Cover Existing Replacement Race Integration Tests (MySQL)")
class WikiCoverExistingReplacementRaceIntegrationTest {

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

    @Test
    @DisplayName("Version Replacement Race: Version replacement on A rejects with WikiCoverStaleMutationException if live cover changed, and does NOT delete stable asset A")
    void shouldRejectVersionReplacementWhenLiveCoverChangedConcurrently() {
        UUID coverA = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        createdAssetIds.add(coverA);

        // 1. Create draft article with cover A
        WikiArticleDTO created = createWikiArticleUseCase.execute(new CreateWikiArticleCommand(
                "Bài viết có cover ban đầu",
                ArticleType.CHARACTER,
                "Tóm tắt ban đầu",
                "Nội dung bài viết",
                "Khởi tạo",
                actorId,
                coverA,
                50,
                50
        ));
        UUID articleId = created.id();
        createdArticleIds.add(articleId);

        // 2. Configure mediaContract.uploadVersion so that when User 1 uploads version 2 for cover A,
        // User 2 concurrently removes the cover on the live article (detaching cover A)
        when(mediaContract.uploadVersion(any())).thenAnswer(invocation -> {
            updateDraftWikiArticleUseCase.execute(new UpdateDraftWikiArticleCommand(
                    articleId,
                    "Bài viết có cover ban đầu",
                    ArticleType.CHARACTER,
                    "Tóm tắt ban đầu",
                    "Nội dung bài viết",
                    "User 2 xóa cover",
                    actorId,
                    null,
                    50,
                    50,
                    true
            ));
            return new UploadMediaAssetVersionResponseDTO(coverA, 2);
        });

        // 3. User 1 submits a version replacement upload for cover A
        WikiCoverUpload upload = new WikiCoverUpload(
                new ByteArrayInputStream("new-image-content".getBytes()),
                17L,
                "image/jpeg",
                "cover-v2.jpg"
        );

        UpdateDraftWikiArticleCommand updateCmd = new UpdateDraftWikiArticleCommand(
                articleId,
                "Bài viết có cover ban đầu",
                ArticleType.CHARACTER,
                "Tóm tắt ban đầu",
                "Nội dung bài viết",
                "User 1 upload version 2",
                actorId,
                50,
                50
        );

        // 4. CRITICAL VERIFICATION: Orchestrator execution throws WikiCoverStaleMutationException
        assertThatThrownBy(() -> orchestrator.updateDraft(updateCmd, upload, false))
                .isInstanceOf(WikiCoverStaleMutationException.class);

        // 5. CRITICAL VERIFICATION: Stable asset A was NEVER compensation-deleted!
        verify(mediaContract, never()).delete(coverA);

        // 6. Live article in database remains cover = null
        Optional<WikiArticle> liveFinal = articleRepositoryPort.findById(articleId);
        assertThat(liveFinal).isPresent();
        assertThat(liveFinal.get().getCoverMediaAssetId()).isNull();
    }
}
