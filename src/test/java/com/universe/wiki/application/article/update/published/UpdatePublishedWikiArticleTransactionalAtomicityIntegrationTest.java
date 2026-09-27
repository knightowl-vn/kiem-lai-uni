package com.universe.wiki.application.article.update.published;

import com.universe.shared.id.UuidGeneratorAdapter;
import com.universe.shared.time.ClockPort;
import com.universe.test.TestDatabaseSupport;
import com.universe.wiki.domain.article.ArticleType;
import com.universe.wiki.domain.article.Slug;
import com.universe.wiki.domain.article.WikiArticle;
import com.universe.wiki.domain.contribution.WikiContribution;
import com.universe.wiki.domain.contribution.WikiContributionType;
import com.universe.wiki.infrastructure.persistence.article.WikiArticlePersistenceAdapter;
import com.universe.wiki.infrastructure.persistence.contribution.WikiContributionPersistenceAdapter;
import com.universe.wiki.infrastructure.persistence.contribution.WikiContributionWorkflowEventPersistenceAdapter;
import com.universe.wiki.infrastructure.persistence.image.WikiImageReferenceSynchronizer;
import com.universe.wiki.infrastructure.persistence.orphan.WikiCoverOrphanPersistenceAdapter;
import com.universe.wiki.infrastructure.persistence.revision.WikiArticleRevisionPersistenceAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import java.time.temporal.ChronoUnit;
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
        "spring.flyway.enabled=true",
        "spring.flyway.out-of-order=true"
})
@Import({
        WikiArticlePersistenceAdapter.class,
        WikiArticleRevisionPersistenceAdapter.class,
        WikiCoverOrphanPersistenceAdapter.class,
        WikiContributionPersistenceAdapter.class,
        WikiContributionWorkflowEventPersistenceAdapter.class,
        UpdatePublishedWikiArticleUseCase.class,
        UuidGeneratorAdapter.class,
        UpdatePublishedWikiArticleTransactionalAtomicityIntegrationTest.TestConfig.class
})
@DisplayName("UpdatePublishedWikiArticle Transactional Atomicity & Rollback Integration Tests")
class UpdatePublishedWikiArticleTransactionalAtomicityIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        public ClockPort clockPort() {
            return () -> Instant.now().truncatedTo(ChronoUnit.MICROS);
        }
    }

    @MockBean
    private WikiImageReferenceSynchronizer imageReferenceSynchronizer;

    @Autowired
    private WikiArticlePersistenceAdapter articlePersistenceAdapter;

    @Autowired
    private WikiContributionPersistenceAdapter contributionPersistenceAdapter;

    @SpyBean
    private WikiContributionWorkflowEventPersistenceAdapter workflowEventPersistenceAdapter;

    @Autowired
    private UpdatePublishedWikiArticleUseCase updatePublishedWikiArticleUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final UUID ARTICLE_ID = UUID.fromString("11111111-aaaa-bbbb-cccc-111111111111");
    private static final UUID CONTRIBUTION_ID = UUID.fromString("22222222-aaaa-bbbb-cccc-222222222222");
    private static final UUID ADMIN_ID = UUID.fromString("33333333-aaaa-bbbb-cccc-333333333333");
    private static final UUID USER_ID = UUID.fromString("44444444-aaaa-bbbb-cccc-444444444444");

    @BeforeEach
    void setUp() {
        Mockito.reset(workflowEventPersistenceAdapter);
        cleanupDatabase();
        seedBaseData();
    }

    @AfterEach
    void tearDown() {
        Mockito.reset(workflowEventPersistenceAdapter);
        cleanupDatabase();
    }

    private void cleanupDatabase() {
        jdbcTemplate.execute("DELETE FROM wiki_contribution_workflow_events");
        jdbcTemplate.execute("DELETE FROM wiki_contribution_sources");
        jdbcTemplate.execute("DELETE FROM wiki_contributions");
        jdbcTemplate.execute("DELETE FROM wiki_article_revisions");
        jdbcTemplate.execute("DELETE FROM wiki_articles WHERE id = '" + ARTICLE_ID + "'");
    }

    private void seedBaseData() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        // 1. Seed published article
        WikiArticle article = WikiArticle.createDraft(
                ARTICLE_ID,
                "Bài Viết Thẩm Định Gốc",
                new Slug("bai-viet-tham-dinh-goc"),
                ArticleType.CHARACTER,
                "Tóm tắt ban đầu",
                "Nội dung ban đầu của bài viết",
                null,
                50,
                50,
                ADMIN_ID,
                now
        );
        article.publish(ADMIN_ID, now);
        articlePersistenceAdapter.save(article);

        // 2. Seed reviewing contribution with explicit assignee
        WikiContribution contribution = WikiContribution.createGeneral(
                CONTRIBUTION_ID,
                ARTICLE_ID,
                "CHARACTER",
                "Bài Viết Thẩm Định Gốc",
                "bai-viet-tham-dinh-goc",
                1L,
                USER_ID,
                WikiContributionType.MISSING_INFORMATION,
                "Nội dung đề xuất bổ sung thông tin cần thiết",
                now
        );
        contribution.startReview(ADMIN_ID, 1L, now);
        contributionPersistenceAdapter.save(contribution);
    }

    @Test
    @DisplayName("Rollback: Khi lưu workflow event thất bại, transaction phải rollback toàn bộ (không lưu revision, không đổi version bài viết)")
    void shouldRollbackEntireArticleUpdateWhenWorkflowEventPersistenceFails() {
        // Given: Inject failure when saving workflow event
        doThrow(new RuntimeException("Simulated workflow event persistence failure"))
                .when(workflowEventPersistenceAdapter)
                .save(any());

        UpdatePublishedWikiArticleCommand command = new UpdatePublishedWikiArticleCommand(
                ARTICLE_ID,
                "Tóm tắt đã cập nhật mới",
                "Nội dung đã được biên tập theo đóng góp",
                "Áp dụng đóng góp độc giả",
                ADMIN_ID,
                50,
                50,
                CONTRIBUTION_ID
        );

        // When & Then: Use case must throw
        assertThatThrownBy(() -> updatePublishedWikiArticleUseCase.execute(command))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated workflow event persistence failure");

        // Verify Rollback:
        // 1. No workflow events persisted
        Integer eventCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wiki_contribution_workflow_events WHERE contribution_id = ?",
                Integer.class,
                CONTRIBUTION_ID.toString()
        );
        assertThat(eventCount).isZero();

        // 2. No linked revisions persisted
        Integer revisionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wiki_article_revisions WHERE source_contribution_id = ?",
                Integer.class,
                CONTRIBUTION_ID.toString()
        );
        assertThat(revisionCount).isZero();

        // 3. Article state in database must remain at original contentVersion = 1 and original content
        WikiArticle restoredArticle = articlePersistenceAdapter.findById(ARTICLE_ID).orElseThrow();
        assertThat(restoredArticle.getContentVersion()).isEqualTo(1L);
        assertThat(restoredArticle.getSummary()).isEqualTo("Tóm tắt ban đầu");
        assertThat(restoredArticle.getContent()).isEqualTo("Nội dung ban đầu của bài viết");
    }

    @Test
    @DisplayName("Success: Khi lưu thành công, toàn bộ bài viết, revision và workflow event được ghi nhận nguyên tử")
    void shouldPersistArticleRevisionAndEventAtomicallyOnSuccess() {
        UpdatePublishedWikiArticleCommand command = new UpdatePublishedWikiArticleCommand(
                ARTICLE_ID,
                "Tóm tắt đã cập nhật mới thành công",
                "Nội dung đã được biên tập theo đóng góp thành công",
                "Áp dụng đóng góp độc giả thành công",
                ADMIN_ID,
                50,
                50,
                CONTRIBUTION_ID
        );

        updatePublishedWikiArticleUseCase.execute(command);

        // 1. Exactly 1 workflow event persisted with ARTICLE_UPDATE_LINKED and REVIEWING -> REVIEWING
        Integer eventCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wiki_contribution_workflow_events WHERE contribution_id = ?",
                Integer.class,
                CONTRIBUTION_ID.toString()
        );
        assertThat(eventCount).isEqualTo(1);

        String eventType = jdbcTemplate.queryForObject(
                "SELECT event_type FROM wiki_contribution_workflow_events WHERE contribution_id = ?",
                String.class,
                CONTRIBUTION_ID.toString()
        );
        assertThat(eventType).isEqualTo("ARTICLE_UPDATE_LINKED");

        String fromStatus = jdbcTemplate.queryForObject(
                "SELECT from_status FROM wiki_contribution_workflow_events WHERE contribution_id = ?",
                String.class,
                CONTRIBUTION_ID.toString()
        );
        assertThat(fromStatus).isEqualTo("REVIEWING");

        String toStatus = jdbcTemplate.queryForObject(
                "SELECT to_status FROM wiki_contribution_workflow_events WHERE contribution_id = ?",
                String.class,
                CONTRIBUTION_ID.toString()
        );
        assertThat(toStatus).isEqualTo("REVIEWING");

        // 2. Exactly 1 revision linked with content_version = 2
        Integer revisionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wiki_article_revisions WHERE source_contribution_id = ?",
                Integer.class,
                CONTRIBUTION_ID.toString()
        );
        assertThat(revisionCount).isEqualTo(1);

        Long revisionContentVersion = jdbcTemplate.queryForObject(
                "SELECT content_version FROM wiki_article_revisions WHERE source_contribution_id = ?",
                Long.class,
                CONTRIBUTION_ID.toString()
        );
        assertThat(revisionContentVersion).isEqualTo(2L);

        // 3. Article updated to contentVersion = 2
        WikiArticle updatedArticle = articlePersistenceAdapter.findById(ARTICLE_ID).orElseThrow();
        assertThat(updatedArticle.getContentVersion()).isEqualTo(2L);
        assertThat(updatedArticle.getSummary()).isEqualTo("Tóm tắt đã cập nhật mới thành công");
    }
}
