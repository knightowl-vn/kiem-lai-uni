package com.universe.community.infrastructure.persistence;

import com.universe.community.domain.CommunityPost;
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
@DisplayName("CommunityPost JPA Persistence Adapter Integration Tests")
class CommunityPostPersistenceAdapterMySQLTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CommunityPostPersistenceAdapter adapter;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        cleanData();
    }

    @AfterEach
    void tearDown() {
        cleanData();
    }

    private void cleanData() {
        jdbcTemplate.execute("DELETE FROM community_post_revisions");
        jdbcTemplate.execute("DELETE FROM community_posts");
    }

    @Test
    @DisplayName("Should persist and rehydrate CommunityPost with all fields")
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
                now
        );

        CommunityPost saved = adapter.save(post);

        assertThat(saved.getId()).isEqualTo(postId);
        assertThat(saved.getAuthorUserId()).isEqualTo(authorId);
        assertThat(saved.getCaption()).isEqualTo("Caption with media");
        assertThat(saved.getImageMediaAssetId()).isEqualTo(mediaAssetId);
        assertThat(saved.getContentVersion()).isEqualTo(0);
        assertThat(saved.getCreatedAt()).isEqualTo(now);
        assertThat(saved.getUpdatedAt()).isEqualTo(now);

        // Verify DB row directly
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT id, author_user_id, caption, image_media_asset_id, content_version, created_at, updated_at FROM community_posts WHERE id = ?",
                postId.toString()
        );
        assertThat(row.get("id")).isEqualTo(postId.toString());
        assertThat(row.get("author_user_id")).isEqualTo(authorId.toString());
        assertThat(row.get("caption")).isEqualTo("Caption with media");
        assertThat(row.get("image_media_asset_id")).isEqualTo(mediaAssetId.toString());
        assertThat(((Number) row.get("content_version")).intValue()).isEqualTo(0);

        // Find by ID through adapter
        Optional<CommunityPost> foundOpt = adapter.findById(postId);
        assertThat(foundOpt).isPresent();
        CommunityPost found = foundOpt.get();
        assertThat(found.getId()).isEqualTo(postId);
        assertThat(found.getAuthorUserId()).isEqualTo(authorId);
        assertThat(found.getCaption()).isEqualTo("Caption with media");
        assertThat(found.getImageMediaAssetId()).isEqualTo(mediaAssetId);
        assertThat(found.getContentVersion()).isEqualTo(0);
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
                now
        );

        adapter.save(post);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT image_media_asset_id FROM community_posts WHERE id = ?",
                postId.toString()
        );
        assertThat(row.get("image_media_asset_id")).isNull();

        Optional<CommunityPost> foundOpt = adapter.findById(postId);
        assertThat(foundOpt).isPresent();
        assertThat(foundOpt.get().getImageMediaAssetId()).isNull();
    }

    @Test
    @DisplayName("Should update existing post and increment contentVersion")
    void shouldUpdatePost() {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant createdAt = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        Instant editedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        CommunityPost post = CommunityPost.create(postId, authorId, "Initial", null, createdAt);
        adapter.save(post);

        post.editCaption(authorId, "Updated text", editedAt);
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

        CommunityPost post = CommunityPost.create(postId, authorId, "Lock test", null, now);
        adapter.save(post);

        transactionTemplate.executeWithoutResult(status -> {
            Optional<CommunityPost> lockedOpt = adapter.findByIdForUpdate(postId);
            assertThat(lockedOpt).isPresent();
            assertThat(lockedOpt.get().getId()).isEqualTo(postId);
        });
    }

    @Test
    @DisplayName("Should verify existsById and deleteById")
    void shouldVerifyExistsAndDelete() {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        assertThat(adapter.existsById(postId)).isFalse();

        CommunityPost post = CommunityPost.create(postId, authorId, "To be deleted", null, now);
        adapter.save(post);

        assertThat(adapter.existsById(postId)).isTrue();

        transactionTemplate.executeWithoutResult(status -> {
            adapter.deleteById(postId);
        });

        assertThat(adapter.existsById(postId)).isFalse();
        assertThat(adapter.findById(postId)).isEmpty();
    }
}
