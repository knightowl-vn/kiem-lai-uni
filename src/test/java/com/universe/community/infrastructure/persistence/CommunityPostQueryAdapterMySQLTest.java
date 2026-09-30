package com.universe.community.infrastructure.persistence;

import com.universe.community.contracts.dto.CommunityPostPublicDTO;
import com.universe.community.contracts.dto.CommunityPostRevisionPublicDTO;
import com.universe.community.domain.CommunityPost;
import com.universe.community.domain.CommunityPostRevision;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
        CommunityPostRevisionPersistenceAdapter.class,
        CommunityPostPersistenceMapper.class,
        CommunityPostRevisionPersistenceMapper.class
})
@DisplayName("CommunityPost Public Read Projection Query Integration Tests")
class CommunityPostQueryAdapterMySQLTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CommunityPostPersistenceAdapter adapter;

    @Autowired
    private CommunityPostRevisionPersistenceAdapter revisionAdapter;

    @BeforeEach
    @AfterEach
    void cleanData() {
        jdbcTemplate.execute("DELETE FROM community_post_revisions");
        jdbcTemplate.execute("DELETE FROM community_posts");
    }

    @Test
    @DisplayName("Should query public post projection DTO by ID")
    void shouldFindPublicPostById() {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID mediaAssetId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        CommunityPost post = CommunityPost.create(postId, authorId, "Public post caption", mediaAssetId, now);
        adapter.save(post);

        Optional<CommunityPostPublicDTO> dtoOpt = adapter.findPublicPostById(postId);
        assertThat(dtoOpt).isPresent();
        CommunityPostPublicDTO dto = dtoOpt.get();
        assertThat(dto.id()).isEqualTo(postId);
        assertThat(dto.authorUserId()).isEqualTo(authorId);
        assertThat(dto.caption()).isEqualTo("Public post caption");
        assertThat(dto.imageMediaAssetId()).isEqualTo(mediaAssetId);
        assertThat(dto.contentVersion()).isEqualTo(0);
        assertThat(dto.createdAt()).isEqualTo(now);
        assertThat(dto.updatedAt()).isEqualTo(now);

        // Non-existent post
        assertThat(adapter.findPublicPostById(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("Should query public revision history ordered oldest-first (ASC)")
    void shouldFindPublicRevisionHistory() {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant t0 = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        Instant t1 = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        Instant t2 = Instant.now().truncatedTo(ChronoUnit.MICROS);

        CommunityPost post = CommunityPost.create(postId, authorId, "v0", null, t0);
        adapter.save(post);

        // Initially no revisions
        assertThat(adapter.findPublicRevisionHistory(postId)).isEmpty();

        // Add 2 revisions
        UUID rev1Id = UUID.randomUUID();
        UUID rev2Id = UUID.randomUUID();
        revisionAdapter.save(new CommunityPostRevision(rev1Id, postId, 1, authorId, "v0", "v1", t1));
        revisionAdapter.save(new CommunityPostRevision(rev2Id, postId, 2, authorId, "v1", "v2", t2));

        List<CommunityPostRevisionPublicDTO> history = adapter.findPublicRevisionHistory(postId);
        assertThat(history).hasSize(2);

        CommunityPostRevisionPublicDTO rev1 = history.get(0);
        assertThat(rev1.id()).isEqualTo(rev1Id);
        assertThat(rev1.postId()).isEqualTo(postId);
        assertThat(rev1.revisionNumber()).isEqualTo(1);
        assertThat(rev1.editorUserId()).isEqualTo(authorId);
        assertThat(rev1.previousCaption()).isEqualTo("v0");
        assertThat(rev1.caption()).isEqualTo("v1");
        assertThat(rev1.editedAt()).isEqualTo(t1);

        CommunityPostRevisionPublicDTO rev2 = history.get(1);
        assertThat(rev2.id()).isEqualTo(rev2Id);
        assertThat(rev2.postId()).isEqualTo(postId);
        assertThat(rev2.revisionNumber()).isEqualTo(2);
        assertThat(rev2.editorUserId()).isEqualTo(authorId);
        assertThat(rev2.previousCaption()).isEqualTo("v1");
        assertThat(rev2.caption()).isEqualTo("v2");
        assertThat(rev2.editedAt()).isEqualTo(t2);
    }
}
