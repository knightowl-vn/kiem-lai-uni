package com.universe.community.infrastructure.persistence;

import com.universe.community.domain.CommunityPost;
import com.universe.community.domain.CommunityPostStatus;
import com.universe.community.domain.CommunityPublicationMode;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Import({
        CommunityPostPersistenceAdapter.class,
        CommunityPostPersistenceMapper.class,
        CommunityPostRevisionPersistenceMapper.class
})
class CommunityPostPersistenceAdapterMySQLTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private CommunityPostPersistenceAdapter adapter;

    @Autowired
    private SpringDataCommunityPostJpaRepository postRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0;");
        jdbcTemplate.execute("TRUNCATE TABLE community_post_revisions;");
        jdbcTemplate.execute("TRUNCATE TABLE community_posts;");
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1;");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0;");
        jdbcTemplate.execute("TRUNCATE TABLE community_post_revisions;");
        jdbcTemplate.execute("TRUNCATE TABLE community_posts;");
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1;");
    }

    @Test
    @DisplayName("Should persist and rehydrate CommunityPost with image attachment")
    void shouldPersistAndRehydratePostWithImage() {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID mediaAssetId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        CommunityPost post = CommunityPost.create(
                postId,
                authorId,
                "Caption with media",
                mediaAssetId,
                CommunityPostStatus.PUBLISHED,
                now,
                now,
                null
        );

        CommunityPost saved = adapter.save(post);

        assertThat(saved.getId()).isEqualTo(postId);
        assertThat(saved.getAuthorUserId()).isEqualTo(authorId);
        assertThat(saved.getCaption()).isEqualTo("Caption with media");
        assertThat(saved.getImageMediaAssetId()).isEqualTo(mediaAssetId);
        assertThat(saved.getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
        assertThat(saved.getContentVersion()).isEqualTo(0);
        assertThat(saved.getCreatedAt()).isEqualTo(now);
        assertThat(saved.getUpdatedAt()).isEqualTo(now);
        assertThat(saved.getPublishedAt()).isEqualTo(now);
        assertThat(saved.getReviewRequestedAt()).isNull();

        // Verify DB row directly
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT id, author_user_id, caption, image_media_asset_id, status, content_version, created_at, updated_at, published_at, review_requested_at FROM community_posts WHERE id = ?",
                postId.toString()
        );
        assertThat(row.get("id")).isEqualTo(postId.toString());
        assertThat(row.get("author_user_id")).isEqualTo(authorId.toString());
        assertThat(row.get("caption")).isEqualTo("Caption with media");
        assertThat(row.get("image_media_asset_id")).isEqualTo(mediaAssetId.toString());
        assertThat(row.get("status")).isEqualTo("PUBLISHED");
        assertThat(((Number) row.get("content_version")).intValue()).isEqualTo(0);
        assertThat(row.get("published_at")).isNotNull();
        assertThat(row.get("review_requested_at")).isNull();

        // Find by ID through adapter
        Optional<CommunityPost> foundOpt = adapter.findById(postId);
        assertThat(foundOpt).isPresent();
        CommunityPost found = foundOpt.get();
        assertThat(found.getId()).isEqualTo(postId);
        assertThat(found.getAuthorUserId()).isEqualTo(authorId);
        assertThat(found.getCaption()).isEqualTo("Caption with media");
        assertThat(found.getImageMediaAssetId()).isEqualTo(mediaAssetId);
        assertThat(found.getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
        assertThat(found.getContentVersion()).isEqualTo(0);
        assertThat(found.getPublishedAt()).isEqualTo(now);
        assertThat(found.getReviewRequestedAt()).isNull();
    }

    @Test
    @DisplayName("Should persist and rehydrate CommunityPost without image attachment (nullable)")
    void shouldPersistAndRehydratePostWithoutImage() {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        CommunityPost post = CommunityPost.create(
                postId,
                authorId,
                "Text only caption",
                null,
                CommunityPostStatus.PUBLISHED,
                now,
                now,
                null
        );

        adapter.save(post);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT image_media_asset_id, status FROM community_posts WHERE id = ?",
                postId.toString()
        );
        assertThat(row.get("image_media_asset_id")).isNull();
        assertThat(row.get("status")).isEqualTo("PUBLISHED");

        Optional<CommunityPost> foundOpt = adapter.findById(postId);
        assertThat(foundOpt).isPresent();
        assertThat(foundOpt.get().getImageMediaAssetId()).isNull();
        assertThat(foundOpt.get().getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
    }

    @Test
    @DisplayName("Should persist and rehydrate CommunityPost in each canonical status")
    void shouldPersistAndRehydrateInAllStatuses() {
        for (CommunityPostStatus status : CommunityPostStatus.values()) {
            UUID postId = UUID.randomUUID();
            UUID authorId = UUID.randomUUID();
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

            Instant publishedAt = (status == CommunityPostStatus.PUBLISHED || status == CommunityPostStatus.HIDDEN) ? now : null;
            Instant reviewRequestedAt = (status == CommunityPostStatus.PENDING_REVIEW) ? now : null;

            CommunityPost post = CommunityPost.create(
                    postId,
                    authorId,
                    "Post with status " + status,
                    null,
                    status,
                    now,
                    publishedAt,
                    reviewRequestedAt
            );
            adapter.save(post);

            Optional<CommunityPost> foundOpt = adapter.findById(postId);
            assertThat(foundOpt).isPresent();
            assertThat(foundOpt.get().getStatus()).isEqualTo(status);

            String dbStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM community_posts WHERE id = ?",
                    String.class,
                    postId.toString()
            );
            assertThat(dbStatus).isEqualTo(status.name());
        }
    }

    @Test
    @DisplayName("Should update existing post and increment contentVersion")
    void shouldUpdatePost() {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant createdAt = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        Instant editedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        CommunityPost post = CommunityPost.create(postId, authorId, "Initial", null, CommunityPostStatus.PUBLISHED, createdAt, createdAt, null);
        adapter.save(post);

        post.editCaption(authorId, "Updated text", editedAt, CommunityPublicationMode.AUTO_PUBLISH);
        CommunityPost updated = adapter.save(post);

        assertThat(updated.getContentVersion()).isEqualTo(1);
        assertThat(updated.getCaption()).isEqualTo("Updated text");
        assertThat(updated.getUpdatedAt()).isEqualTo(editedAt);

        Optional<CommunityPost> foundOpt = adapter.findById(postId);
        assertThat(foundOpt).isPresent();
        assertThat(foundOpt.get().getContentVersion()).isEqualTo(1);
        assertThat(foundOpt.get().getCaption()).isEqualTo("Updated text");
    }

    @Test
    @DisplayName("Should find post for update with pessimistic lock inside transaction")
    void shouldFindByIdForUpdate() {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        CommunityPost post = CommunityPost.create(postId, authorId, "Lock test", null, CommunityPostStatus.PUBLISHED, now, now, null);
        adapter.save(post);

        transactionTemplate.executeWithoutResult(status -> {
            Optional<CommunityPost> lockedOpt = adapter.findByIdForUpdate(postId);
            assertThat(lockedOpt).isPresent();
            assertThat(lockedOpt.get().getId()).isEqualTo(postId);
        });
    }

    @Test
    @DisplayName("Should throw IllegalTransactionStateException when findByIdForUpdate is called without transaction")
    void shouldThrowWhenFindByIdForUpdateCalledWithoutTransaction() {
        UUID postId = UUID.randomUUID();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.findByIdForUpdate(postId))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class)
                .hasMessageContaining("No existing transaction found for transaction marked with propagation 'mandatory'");
    }

    @Test
    @DisplayName("Should throw IllegalTransactionStateException when lockExistingPostForInteraction is called without transaction")
    void shouldThrowWhenLockExistingPostForInteractionCalledWithoutTransaction() {
        UUID postId = UUID.randomUUID();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.lockExistingPostForInteraction(postId))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class)
                .hasMessageContaining("No existing transaction found for transaction marked with propagation 'mandatory'");
    }

    @Test
    @DisplayName("Should acquire lock via lockExistingPostForInteraction inside active transaction for PUBLISHED post")
    void shouldAcquireLockExistingPostForInteractionInsideTransaction() {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        CommunityPost post = CommunityPost.create(postId, authorId, "Locked view test", null, CommunityPostStatus.PUBLISHED, now, now, null);
        adapter.save(post);

        transactionTemplate.executeWithoutResult(status -> {
            var lockedViewOpt = adapter.lockExistingPostForInteraction(postId);
            assertThat(lockedViewOpt).isPresent();
            assertThat(lockedViewOpt.get().postId()).isEqualTo(postId);
            assertThat(lockedViewOpt.get().authorUserId()).isEqualTo(authorId);
            assertThat(lockedViewOpt.get().caption()).isEqualTo("Locked view test");
        });
    }

    @Test
    @DisplayName("lockExistingPostForInteraction should return empty for non-PUBLISHED posts (PENDING_REVIEW, HIDDEN, REJECTED)")
    void shouldReturnEmptyFromLockExistingPostForInteractionWhenNonPublished() {
        CommunityPostStatus[] nonPublishedStatuses = {
                CommunityPostStatus.PENDING_REVIEW,
                CommunityPostStatus.HIDDEN,
                CommunityPostStatus.REJECTED
        };

        for (CommunityPostStatus status : nonPublishedStatuses) {
            UUID postId = UUID.randomUUID();
            UUID authorId = UUID.randomUUID();
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

            Instant publishedAt = (status == CommunityPostStatus.HIDDEN) ? now : null;
            Instant reviewRequestedAt = (status == CommunityPostStatus.PENDING_REVIEW) ? now : null;

            CommunityPost post = CommunityPost.create(postId, authorId, "Non published: " + status, null, status, now, publishedAt, reviewRequestedAt);
            adapter.save(post);

            transactionTemplate.executeWithoutResult(s -> {
                var lockedViewOpt = adapter.lockExistingPostForInteraction(postId);
                assertThat(lockedViewOpt).as("lockExistingPostForInteraction for status %s", status).isEmpty();
            });
        }
    }

    @Test
    @DisplayName("Should verify existsById and deleteById")
    void shouldVerifyExistsAndDelete() {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        assertThat(adapter.existsById(postId)).isFalse();

        CommunityPost post = CommunityPost.create(postId, authorId, "To be deleted", null, CommunityPostStatus.PUBLISHED, now, now, null);
        adapter.save(post);

        assertThat(adapter.existsById(postId)).isTrue();

        transactionTemplate.executeWithoutResult(status -> {
            adapter.deleteById(postId);
        });

        assertThat(adapter.existsById(postId)).isFalse();
        assertThat(adapter.findById(postId)).isEmpty();
    }
}
