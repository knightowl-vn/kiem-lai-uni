package com.universe.community.infrastructure.persistence;

import com.universe.community.application.command.ApproveCommunityPostCommand;
import com.universe.community.application.command.EditCommunityPostCaptionCommand;
import com.universe.community.application.port.out.CommunityPostRevisionRepositoryPort;
import com.universe.community.application.usecase.ApproveCommunityPostUseCase;
import com.universe.community.application.usecase.EditCommunityPostCaptionUseCase;
import com.universe.community.domain.CommunityPost;
import com.universe.community.domain.CommunityPostStatus;
import com.universe.community.domain.CommunityPostRevision;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.id.UuidGeneratorAdapter;
import com.universe.shared.time.ClockPort;
import com.universe.shared.time.SystemClockAdapter;
import com.universe.test.TestDatabaseSupport;
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
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Import({
        CommunitySettingsPersistenceAdapter.class,
        CommunityPostPersistenceAdapter.class,
        CommunityPostRevisionPersistenceAdapter.class,
        CommunityPostModerationEventPersistenceAdapter.class,
        CommunityPostPersistenceMapper.class,
        CommunityPostRevisionPersistenceMapper.class,
        CommunityPostModerationEventPersistenceMapper.class,
        UuidGeneratorAdapter.class,
        SystemClockAdapter.class,
        EditCommunityPostCaptionUseCase.class,
        ApproveCommunityPostUseCase.class,
        CommunityPostEditTransactionAtomicityIntegrationTest.TestConfig.class
})
@DisplayName("CommunityPost Edit Transaction Atomicity Integration Tests")
class CommunityPostEditTransactionAtomicityIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.resetTestDatabase("kiemlai_test");
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        TestFailingRevisionRepositoryPort testFailingRevisionRepositoryPort(
                CommunityPostRevisionPersistenceAdapter delegate
        ) {
            return new TestFailingRevisionRepositoryPort(delegate);
        }
    }

    static class TestFailingRevisionRepositoryPort implements CommunityPostRevisionRepositoryPort {
        private final CommunityPostRevisionPersistenceAdapter delegate;
        private final AtomicBoolean failOnSave = new AtomicBoolean(false);
        private final AtomicBoolean postSavedBeforeRevisionFailure = new AtomicBoolean(false);

        public TestFailingRevisionRepositoryPort(CommunityPostRevisionPersistenceAdapter delegate) {
            this.delegate = delegate;
        }

        public void setFailOnSave(boolean fail) {
            this.failOnSave.set(fail);
        }

        public void reset() {
            this.failOnSave.set(false);
            this.postSavedBeforeRevisionFailure.set(false);
        }

        public boolean wasPostSavedBeforeFailure() {
            return this.postSavedBeforeRevisionFailure.get();
        }

        @Override
        public CommunityPostRevision save(CommunityPostRevision revision) {
            if (failOnSave.get()) {
                this.postSavedBeforeRevisionFailure.set(true);
                throw new RuntimeException("Simulated revision save database failure during transaction");
            }
            return delegate.save(revision);
        }

        @Override
        public List<CommunityPostRevision> findByPostIdOrderByRevisionNumberAsc(UUID postId) {
            return delegate.findByPostIdOrderByRevisionNumberAsc(postId);
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CommunityPostPersistenceAdapter postAdapter;

    @Autowired
    private TestFailingRevisionRepositoryPort failingRevisionPort;

    @Autowired
    private EditCommunityPostCaptionUseCase useCase;

    @Autowired
    private ApproveCommunityPostUseCase approveUseCase;

    @BeforeEach
    void setUp() {
        failingRevisionPort.reset();
        cleanData();
    }

    @AfterEach
    void tearDown() {
        failingRevisionPort.reset();
        cleanData();
    }

    private void cleanData() {
        jdbcTemplate.execute("DELETE FROM community_post_moderation_events");
        jdbcTemplate.execute("DELETE FROM community_post_revisions");
        jdbcTemplate.execute("DELETE FROM community_posts");
    }

    @Test
    @DisplayName("Should commit post update and revision creation atomically under Spring @Transactional proxy")
    void shouldCommitAtomicallyOnSuccess() {
        failingRevisionPort.setFailOnSave(false);

        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant createdAt = Instant.now().minus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);

        CommunityPost post = CommunityPost.create(postId, authorId, "Original caption", null, CommunityPostStatus.PUBLISHED, createdAt, createdAt, null);
        postAdapter.save(post);

        EditCommunityPostCaptionCommand command = new EditCommunityPostCaptionCommand(
                postId,
                authorId,
                "Brand new caption"
        );

        // Invokes the real Spring-managed proxied use case bean
        CommunityPost updated = useCase.execute(command);

        assertThat(updated.getCaption()).isEqualTo("Brand new caption");
        assertThat(updated.getContentVersion()).isEqualTo(1);

        // Verify DB state outside transaction
        Map<String, Object> postRow = jdbcTemplate.queryForMap(
                "SELECT caption, content_version FROM community_posts WHERE id = ?",
                postId.toString()
        );
        assertThat(postRow.get("caption")).isEqualTo("Brand new caption");
        assertThat(((Number) postRow.get("content_version")).intValue()).isEqualTo(1);

        int revCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_post_revisions WHERE post_id = ?",
                Integer.class,
                postId.toString()
        );
        assertThat(revCount).isEqualTo(1);

        Map<String, Object> revRow = jdbcTemplate.queryForMap(
                "SELECT previous_caption, caption, revision_number FROM community_post_revisions WHERE post_id = ?",
                postId.toString()
        );
        assertThat(revRow.get("previous_caption")).isEqualTo("Original caption");
        assertThat(revRow.get("caption")).isEqualTo("Brand new caption");
        assertThat(((Number) revRow.get("revision_number")).intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should rollback post update when revision persistence fails under Spring @Transactional proxy")
    void shouldRollbackPostUpdateWhenRevisionPersistenceFailsUnderTransactionalProxy() {
        failingRevisionPort.setFailOnSave(true);

        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant createdAt = Instant.now().minus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);

        CommunityPost post = CommunityPost.create(postId, authorId, "Original caption", null, CommunityPostStatus.PUBLISHED, createdAt, createdAt, null);
        postAdapter.save(post);

        EditCommunityPostCaptionCommand command = new EditCommunityPostCaptionCommand(
                postId,
                authorId,
                "Failing new caption"
        );

        // Invokes the real Spring-managed proxied use case bean directly (no TransactionTemplate)
        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated revision save database failure during transaction");

        // Verify post save occurred before revision failure
        assertThat(failingRevisionPort.wasPostSavedBeforeFailure()).isTrue();

        // Verify DB state outside transaction: post rolled back to original state, zero revisions committed
        Map<String, Object> postRow = jdbcTemplate.queryForMap(
                "SELECT caption, content_version, updated_at FROM community_posts WHERE id = ?",
                postId.toString()
        );
        assertThat(postRow.get("caption")).isEqualTo("Original caption");
        assertThat(((Number) postRow.get("content_version")).intValue()).isEqualTo(0);
        Object rawUpdatedAt = postRow.get("updated_at");
        Instant dbUpdatedAt;
        if (rawUpdatedAt instanceof Timestamp ts) {
            dbUpdatedAt = ts.toInstant();
        } else if (rawUpdatedAt instanceof LocalDateTime ldt) {
            dbUpdatedAt = ldt.toInstant(ZoneOffset.UTC);
        } else {
            throw new IllegalStateException("Unexpected updated_at type: " + (rawUpdatedAt != null ? rawUpdatedAt.getClass() : "null"));
        }
        assertThat(dbUpdatedAt).isEqualTo(createdAt);

        int revCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_post_revisions WHERE post_id = ?",
                Integer.class,
                postId.toString()
        );
        assertThat(revCount).isEqualTo(0);
    }

    @Test
    @DisplayName("Should commit pending caption approval, promotion, and revision creation atomically under Spring @Transactional proxy")
    void shouldCommitPendingCaptionApprovalAtomicallyOnSuccess() {
        failingRevisionPort.setFailOnSave(false);

        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID moderatorId = UUID.randomUUID();
        Instant createdAt = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        Instant editAt = Instant.now().minus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);

        CommunityPost post = CommunityPost.rehydrate(
                postId, authorId, "Original approved caption", "Approved candidate caption", null,
                CommunityPostStatus.PUBLISHED, 0, createdAt, editAt, createdAt, editAt
        );
        postAdapter.save(post);

        approveUseCase.execute(new ApproveCommunityPostCommand(postId, moderatorId, "Looks good"));

        // Verify DB state outside transaction
        Map<String, Object> postRow = jdbcTemplate.queryForMap(
                "SELECT caption, pending_caption, review_requested_at, content_version, published_at FROM community_posts WHERE id = ?",
                postId.toString()
        );
        assertThat(postRow.get("caption")).isEqualTo("Approved candidate caption");
        assertThat(postRow.get("pending_caption")).isNull();
        assertThat(postRow.get("review_requested_at")).isNull();
        assertThat(((Number) postRow.get("content_version")).intValue()).isEqualTo(1);

        int revCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_post_revisions WHERE post_id = ?",
                Integer.class,
                postId.toString()
        );
        assertThat(revCount).isEqualTo(1);

        Map<String, Object> revRow = jdbcTemplate.queryForMap(
                "SELECT previous_caption, caption, revision_number FROM community_post_revisions WHERE post_id = ?",
                postId.toString()
        );
        assertThat(revRow.get("previous_caption")).isEqualTo("Original approved caption");
        assertThat(revRow.get("caption")).isEqualTo("Approved candidate caption");
        assertThat(((Number) revRow.get("revision_number")).intValue()).isEqualTo(1);

        int eventCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_post_moderation_events WHERE post_id = ?",
                Integer.class,
                postId.toString()
        );
        assertThat(eventCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Should rollback approval when revision persistence fails under Spring @Transactional proxy")
    void shouldRollbackApprovalWhenRevisionPersistenceFailsUnderTransactionalProxy() {
        failingRevisionPort.setFailOnSave(true);

        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID moderatorId = UUID.randomUUID();
        Instant createdAt = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        Instant editAt = Instant.now().minus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);

        CommunityPost post = CommunityPost.rehydrate(
                postId, authorId, "Original approved caption", "Approved candidate caption", null,
                CommunityPostStatus.PUBLISHED, 0, createdAt, editAt, createdAt, editAt
        );
        postAdapter.save(post);

        assertThatThrownBy(() -> approveUseCase.execute(new ApproveCommunityPostCommand(postId, moderatorId, "Failing approve")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated revision save database failure during transaction");

        // Verify DB state outside transaction: post rolled back completely to initial pending edit state
        Map<String, Object> postRow = jdbcTemplate.queryForMap(
                "SELECT caption, pending_caption, review_requested_at, content_version FROM community_posts WHERE id = ?",
                postId.toString()
        );
        assertThat(postRow.get("caption")).isEqualTo("Original approved caption");
        assertThat(postRow.get("pending_caption")).isEqualTo("Approved candidate caption");
        assertThat(postRow.get("review_requested_at")).isNotNull();
        assertThat(((Number) postRow.get("content_version")).intValue()).isEqualTo(0);

        int revCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_post_revisions WHERE post_id = ?",
                Integer.class,
                postId.toString()
        );
        assertThat(revCount).isEqualTo(0);

        int eventCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_post_moderation_events WHERE post_id = ?",
                Integer.class,
                postId.toString()
        );
        assertThat(eventCount).isEqualTo(0);
    }
}
