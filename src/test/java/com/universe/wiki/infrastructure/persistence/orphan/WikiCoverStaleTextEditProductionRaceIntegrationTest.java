package com.universe.wiki.infrastructure.persistence.orphan;

import com.universe.shared.id.UuidGeneratorAdapter;
import com.universe.shared.time.ClockPort;
import com.universe.test.TestDatabaseSupport;
import com.universe.wiki.application.article.cover.WikiArticleCoverOrchestrator;
import com.universe.wiki.application.article.cover.WikiCoverMediaCoordinator;
import com.universe.wiki.application.article.create.CreateAndPublishWikiArticleUseCase;
import com.universe.wiki.application.article.create.CreateWikiArticleCommand;
import com.universe.wiki.application.article.create.CreateWikiArticleUseCase;
import com.universe.wiki.application.article.delete.DeleteWikiArticleUseCase;
import com.universe.wiki.application.article.query.detail.GetWikiArticleDetailQuery;
import com.universe.wiki.application.article.query.detail.GetWikiArticleDetailUseCase;
import com.universe.wiki.application.article.update.draft.UpdateDraftAndPublishWikiArticleUseCase;
import com.universe.wiki.application.article.update.draft.UpdateDraftWikiArticleCommand;
import com.universe.wiki.application.article.update.draft.UpdateDraftWikiArticleUseCase;
import com.universe.wiki.application.article.update.published.UpdatePublishedWikiArticleUseCase;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
        WikiArticleCoverOrchestrator.class,
        WikiCoverStaleTextEditProductionRaceIntegrationTest.TestConfig.class
})
@DisplayName("Wiki Cover Stale Text Edit Production Race Integration Tests (MySQL)")
class WikiCoverStaleTextEditProductionRaceIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        public WikiCoverMediaCoordinator mediaCoordinator() {
            return mock(WikiCoverMediaCoordinator.class);
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
    @DisplayName("Production Race: User 1 text edit does NOT resurrect cover A when User 2 concurrently detached it")
    void shouldNotResurrectCoverOnConcurrentTextEdit() {
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

        // 2. Concurrently User 2 removes the cover (detach A -> null)
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
                true // updateCover = true, coverMediaAssetId = null -> REMOVE
        ));

        // Verify live article now has NO cover
        Optional<WikiArticle> liveAfterUser2 = articleRepositoryPort.findById(articleId);
        assertThat(liveAfterUser2).isPresent();
        assertThat(liveAfterUser2.get().getCoverMediaAssetId()).isNull();

        // 3. User 1 submits a pure text edit (no upload, removeCover = false, no focal change)
        UpdateDraftWikiArticleCommand textEditCommand = new UpdateDraftWikiArticleCommand(
                articleId,
                "Bài viết đã cập nhật text bởi User 1",
                ArticleType.CHARACTER,
                "Tóm tắt mới của User 1",
                "Nội dung mới của User 1",
                "User 1 sửa text",
                actorId,
                null,
                50,
                50
        );

        WikiArticleDTO result = orchestrator.updateDraft(textEditCommand, null, false);

        // 4. CRITICAL VERIFICATION: Cover remains NULL! Asset A was NOT resurrected!
        assertThat(result.coverMediaAssetId()).isNull();

        Optional<WikiArticle> liveFinal = articleRepositoryPort.findById(articleId);
        assertThat(liveFinal).isPresent();
        assertThat(liveFinal.get().getCoverMediaAssetId()).isNull();
        assertThat(liveFinal.get().getTitle()).isEqualTo("Bài viết đã cập nhật text bởi User 1");
    }
}
