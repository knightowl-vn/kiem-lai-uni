package com.universe.community.infrastructure.persistence;

import com.universe.community.contracts.dto.CommunityPostPublicDTO;
import com.universe.community.contracts.dto.CommunityPostRankingCandidateDTO;
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
        CommunityPostQueryAdapter.class,
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
    private CommunityPostQueryAdapter queryAdapter;

    @Autowired
    private CommunityPostPersistenceAdapter persistenceAdapter;

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
        persistenceAdapter.save(post);

        Optional<CommunityPostPublicDTO> dtoOpt = queryAdapter.findPublicPostById(postId);
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
        assertThat(queryAdapter.findPublicPostById(UUID.randomUUID())).isEmpty();
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
        persistenceAdapter.save(post);

        // Initially no revisions
        assertThat(queryAdapter.findPublicRevisionHistory(postId)).isEmpty();

        // Add 2 revisions
        UUID rev1Id = UUID.randomUUID();
        UUID rev2Id = UUID.randomUUID();
        revisionAdapter.save(new CommunityPostRevision(rev1Id, postId, 1, authorId, "v0", "v1", t1));
        revisionAdapter.save(new CommunityPostRevision(rev2Id, postId, 2, authorId, "v1", "v2", t2));

        List<CommunityPostRevisionPublicDTO> history = queryAdapter.findPublicRevisionHistory(postId);
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

    @Test
    @DisplayName("Should query newest posts using keyset pagination with canonical DB id DESC tie-break on same createdAt")
    void shouldQueryNewestPostsKeysetWithTieBreak() {
        UUID authorId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        Instant t3 = now.minus(1, ChronoUnit.HOURS);
        Instant t2 = now.minus(2, ChronoUnit.HOURS); // Shared timestamp
        Instant t1 = now.minus(3, ChronoUnit.HOURS);
        Instant t0 = now.minus(4, ChronoUnit.HOURS);

        UUID id1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID id2B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"); // shared t2, higher id
        UUID id2A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"); // shared t2, lower id
        UUID id4 = UUID.fromString("44444444-4444-4444-4444-444444444444");
        UUID id5 = UUID.fromString("55555555-5555-5555-5555-555555555555");

        persistenceAdapter.save(CommunityPost.create(id1, authorId, "Post 1 (t3)", null, t3));
        persistenceAdapter.save(CommunityPost.create(id2B, authorId, "Post 2B (t2, id=b...)", null, t2));
        persistenceAdapter.save(CommunityPost.create(id2A, authorId, "Post 2A (t2, id=a...)", null, t2));
        persistenceAdapter.save(CommunityPost.create(id4, authorId, "Post 4 (t1)", null, t1));
        persistenceAdapter.save(CommunityPost.create(id5, authorId, "Post 5 (t0)", null, t0));

        // --- Page 1 (limit 2) ---
        List<CommunityPostPublicDTO> page1 = queryAdapter.findNewestPostsKeyset(null, null, 2);
        assertThat(page1).hasSize(2);
        assertThat(page1.get(0).id()).isEqualTo(id1);
        assertThat(page1.get(1).id()).isEqualTo(id2B); // id2B comes before id2A due to id DESC

        // --- Page 2 (limit 2, cursor = last item of page 1: t2, id2B) ---
        CommunityPostPublicDTO lastOfPage1 = page1.get(1);
        List<CommunityPostPublicDTO> page2 = queryAdapter.findNewestPostsKeyset(lastOfPage1.createdAt(), lastOfPage1.id(), 2);
        assertThat(page2).hasSize(2);
        assertThat(page2.get(0).id()).isEqualTo(id2A); // id2A with same t2 comes strictly after id2B
        assertThat(page2.get(1).id()).isEqualTo(id4);

        // --- Page 3 (limit 2, cursor = last item of page 2: t1, id4) ---
        CommunityPostPublicDTO lastOfPage2 = page2.get(1);
        List<CommunityPostPublicDTO> page3 = queryAdapter.findNewestPostsKeyset(lastOfPage2.createdAt(), lastOfPage2.id(), 2);
        assertThat(page3).hasSize(1);
        assertThat(page3.get(0).id()).isEqualTo(id5);

        // --- Page 4 (limit 2, cursor = last item of page 3: t0, id5) ---
        CommunityPostPublicDTO lastOfPage3 = page3.get(0);
        List<CommunityPostPublicDTO> page4 = queryAdapter.findNewestPostsKeyset(lastOfPage3.createdAt(), lastOfPage3.id(), 2);
        assertThat(page4).isEmpty();

        // Verify total traversal: exactly 5 items, no duplicates, no omissions
        List<UUID> fullSequence = List.of(
                page1.get(0).id(), page1.get(1).id(),
                page2.get(0).id(), page2.get(1).id(),
                page3.get(0).id()
        );
        assertThat(fullSequence).containsExactly(id1, id2B, id2A, id4, id5);
    }

    @Test
    @DisplayName("Should find all lightweight ranking candidates from MySQL")
    void shouldFindAllRankingCandidates() {
        UUID authorId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant t1 = now.minus(10, ChronoUnit.MINUTES);
        Instant t2 = now.minus(5, ChronoUnit.MINUTES);

        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        persistenceAdapter.save(CommunityPost.create(id1, authorId, "Post 1", null, t1));
        persistenceAdapter.save(CommunityPost.create(id2, authorId, "Post 2", null, t2));

        List<CommunityPostRankingCandidateDTO> candidates = queryAdapter.findAllRankingCandidates();
        assertThat(candidates).hasSize(2);
        assertThat(candidates).extracting(CommunityPostRankingCandidateDTO::postId)
                .containsExactlyInAnyOrder(id1, id2);
        assertThat(candidates).extracting(CommunityPostRankingCandidateDTO::createdAt)
                .containsExactlyInAnyOrder(t1, t2);
    }

    @Test
    @DisplayName("Should find public posts by IDs in bulk and omit nonexistent IDs")
    void shouldFindPublicPostsByIds() {
        UUID authorId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID missingId = UUID.randomUUID();

        persistenceAdapter.save(CommunityPost.create(id1, authorId, "Post 1", null, now));
        persistenceAdapter.save(CommunityPost.create(id2, authorId, "Post 2", null, now));

        List<CommunityPostPublicDTO> list = queryAdapter.findPublicPostsByIds(List.of(id1, id2, missingId));
        assertThat(list).hasSize(2);
        assertThat(list).extracting(CommunityPostPublicDTO::id).containsExactlyInAnyOrder(id1, id2);
        assertThat(list).extracting(CommunityPostPublicDTO::caption).containsExactlyInAnyOrder("Post 1", "Post 2");

        // Empty input test
        assertThat(queryAdapter.findPublicPostsByIds(List.of())).isEmpty();
    }
}
