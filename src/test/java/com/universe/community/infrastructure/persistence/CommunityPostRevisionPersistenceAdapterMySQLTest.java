package com.universe.community.infrastructure.persistence;

import com.universe.community.domain.CommunityPost;
import com.universe.community.domain.CommunityPostStatus;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

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
        CommunityPostPersistenceAdapter.class,
        CommunityPostRevisionPersistenceAdapter.class,
        CommunityPostPersistenceMapper.class,
        CommunityPostRevisionPersistenceMapper.class
})
@DisplayName("CommunityPostRevision JPA Persistence Adapter Integration Tests")
class CommunityPostRevisionPersistenceAdapterMySQLTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CommunityPostPersistenceAdapter postAdapter;

    @Autowired
    private CommunityPostRevisionPersistenceAdapter revisionAdapter;

    @Autowired
    private SpringDataCommunityPostRevisionJpaRepository springDataRepository;

    @BeforeEach
    @AfterEach
    void cleanData() {
        jdbcTemplate.execute("DELETE FROM community_post_revisions");
        jdbcTemplate.execute("DELETE FROM community_posts");
    }

    @Test
    @DisplayName("Should persist and retrieve revisions ordered oldest-first (ASC)")
    void shouldPersistAndRetrieveRevisions() {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant t0 = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        Instant t1 = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        Instant t2 = Instant.now().truncatedTo(ChronoUnit.MICROS);

        // 1. Create base post
        CommunityPost post = CommunityPost.create(postId, authorId, "Initial v0", null, CommunityPostStatus.PUBLISHED, t0);
        postAdapter.save(post);

        assertThat(revisionAdapter.findByPostIdOrderByRevisionNumberAsc(postId)).isEmpty();

        // 2. Persist revision 1
        UUID rev1Id = UUID.randomUUID();
        CommunityPostRevision rev1 = new CommunityPostRevision(
                rev1Id,
                postId,
                1,
                authorId,
                "Initial v0",
                "Edited v1",
                t1
        );
        revisionAdapter.save(rev1);

        // 3. Persist revision 2
        UUID rev2Id = UUID.randomUUID();
        CommunityPostRevision rev2 = new CommunityPostRevision(
                rev2Id,
                postId,
                2,
                authorId,
                "Edited v1",
                "Edited v2",
                t2
        );
        revisionAdapter.save(rev2);

        // 4. Query list in ascending order (oldest first: rev 1, then rev 2)
        List<CommunityPostRevision> revisions = revisionAdapter.findByPostIdOrderByRevisionNumberAsc(postId);
        assertThat(revisions).hasSize(2);
        assertThat(revisions.get(0).getRevisionNumber()).isEqualTo(1);
        assertThat(revisions.get(0).getPreviousCaption()).isEqualTo("Initial v0");
        assertThat(revisions.get(0).getCaption()).isEqualTo("Edited v1");
        assertThat(revisions.get(0).getEditedAt()).isEqualTo(t1);

        assertThat(revisions.get(1).getRevisionNumber()).isEqualTo(2);
        assertThat(revisions.get(1).getPreviousCaption()).isEqualTo("Edited v1");
        assertThat(revisions.get(1).getCaption()).isEqualTo("Edited v2");
        assertThat(revisions.get(1).getEditedAt()).isEqualTo(t2);
    }

    @Test
    @DisplayName("Should enforce UNIQUE constraint on (postId, revisionNumber)")
    void shouldRejectDuplicateRevisionNumber() {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        CommunityPost post = CommunityPost.create(postId, authorId, "Initial", null, CommunityPostStatus.PUBLISHED, now);
        postAdapter.save(post);

        CommunityPostRevision rev1 = new CommunityPostRevision(
                UUID.randomUUID(),
                postId,
                1,
                authorId,
                "Initial",
                "Rev 1",
                now
        );
        revisionAdapter.save(rev1);

        CommunityPostRevision revDuplicate = new CommunityPostRevision(
                UUID.randomUUID(),
                postId,
                1, // Duplicate revision number!
                authorId,
                "Initial",
                "Conflicting Rev 1",
                now
        );

        assertThatThrownBy(() -> revisionAdapter.save(revDuplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Should query revisions ordered newest-first (DESC) from Spring Data repository")
    void shouldFindRevisionsOrderByRevisionNumberDesc() {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant t0 = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        Instant t1 = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        Instant t2 = Instant.now().truncatedTo(ChronoUnit.MICROS);

        CommunityPost post = CommunityPost.create(postId, authorId, "Initial", null, CommunityPostStatus.PUBLISHED, t0);
        postAdapter.save(post);

        CommunityPostRevision rev1 = new CommunityPostRevision(
                UUID.randomUUID(),
                postId,
                1,
                authorId,
                "Initial",
                "Rev 1",
                t1
        );
        revisionAdapter.save(rev1);

        CommunityPostRevision rev2 = new CommunityPostRevision(
                UUID.randomUUID(),
                postId,
                2,
                authorId,
                "Rev 1",
                "Rev 2",
                t2
        );
        revisionAdapter.save(rev2);

        List<CommunityPostRevisionJpaEntity> entities = springDataRepository.findByPostIdOrderByRevisionNumberDesc(postId.toString());
        assertThat(entities).hasSize(2);
        assertThat(entities.get(0).getRevisionNumber()).isEqualTo(2);
        assertThat(entities.get(1).getRevisionNumber()).isEqualTo(1);
    }
}
