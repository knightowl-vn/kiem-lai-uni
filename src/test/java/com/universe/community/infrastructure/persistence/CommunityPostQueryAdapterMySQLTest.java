package com.universe.community.infrastructure.persistence;

import com.universe.community.contracts.dto.CommunityPostPublicDTO;
import com.universe.community.contracts.dto.CommunityPostRankingCandidateDTO;
import com.universe.community.contracts.dto.CommunityPostRevisionPublicDTO;
import com.universe.community.domain.CommunityPost;
import com.universe.community.domain.CommunityPostRevision;
import com.universe.community.domain.CommunityPostStatus;
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
        TestDatabaseSupport.resetTestDatabase("kiemlai_test");
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
    @DisplayName("Should query public post projection DTO by ID for PUBLISHED post")
    void shouldFindPublicPostById() {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID mediaAssetId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        CommunityPost post = CommunityPost.create(postId, authorId, "Public post caption", mediaAssetId, CommunityPostStatus.PUBLISHED, now, now, null);
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
        assertThat(dto.publishedAt()).isEqualTo(now);

        // Non-existent post
        assertThat(queryAdapter.findPublicPostById(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("findPublicPostById should return empty for non-PUBLISHED posts (PENDING_REVIEW, HIDDEN, REJECTED)")
    void shouldReturnEmptyForNonPublishedPostById() {
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

            CommunityPost post = CommunityPost.create(postId, authorId, "Post " + status, null, status, now, publishedAt, reviewRequestedAt);
            persistenceAdapter.save(post);

            assertThat(queryAdapter.findPublicPostById(postId))
                    .as("findPublicPostById for status %s", status)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("Should query public revision history ordered newest-first (DESC) for PUBLISHED post")
    void shouldFindPublicRevisionHistory() {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant t0 = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        Instant t1 = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        Instant t2 = Instant.now().truncatedTo(ChronoUnit.MICROS);

        CommunityPost post = CommunityPost.create(postId, authorId, "v0", null, CommunityPostStatus.PUBLISHED, t0, t0, null);
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
        assertThat(history.get(0).revisionNumber()).isEqualTo(2);
        assertThat(history.get(0).caption()).isEqualTo("v2");
        assertThat(history.get(0).previousCaption()).isEqualTo("v1");
        assertThat(history.get(0).editedAt()).isEqualTo(t2);

        assertThat(history.get(1).revisionNumber()).isEqualTo(1);
        assertThat(history.get(1).caption()).isEqualTo("v1");
        assertThat(history.get(1).previousCaption()).isEqualTo("v0");
        assertThat(history.get(1).editedAt()).isEqualTo(t1);
    }

    @Test
    @DisplayName("findPublicRevisionHistory should return empty for non-PUBLISHED posts (PENDING_REVIEW, HIDDEN, REJECTED) even if revisions exist")
    void shouldReturnEmptyRevisionHistoryForNonPublishedPost() {
        UUID authorId = UUID.randomUUID();
        Instant t0 = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        Instant t1 = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);

        for (CommunityPostStatus status : List.of(CommunityPostStatus.PENDING_REVIEW, CommunityPostStatus.HIDDEN, CommunityPostStatus.REJECTED)) {
            UUID postId = UUID.randomUUID();
            Instant publishedAt = (status == CommunityPostStatus.HIDDEN) ? t0 : null;
            Instant reviewRequestedAt = (status == CommunityPostStatus.PENDING_REVIEW) ? t0 : null;
            CommunityPost post = CommunityPost.create(postId, authorId, "Post " + status, null, status, t0, publishedAt, reviewRequestedAt);
            persistenceAdapter.save(post);

            revisionAdapter.save(new CommunityPostRevision(UUID.randomUUID(), postId, 1, authorId, "v0", "Post " + status, t1));

            assertThat(queryAdapter.findPublicRevisionHistory(postId))
                    .as("findPublicRevisionHistory for %s post", status)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("Should query newest posts using keyset pagination with canonical tie-break and only return PUBLISHED")
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
        UUID idHidden = UUID.fromString("99999999-9999-9999-9999-999999999999");

        persistenceAdapter.save(CommunityPost.create(id1, authorId, "Post 1 (t3)", null, CommunityPostStatus.PUBLISHED, t3, t3, null));
        persistenceAdapter.save(CommunityPost.create(id2B, authorId, "Post 2B (t2, id=b...)", null, CommunityPostStatus.PUBLISHED, t2, t2, null));
        persistenceAdapter.save(CommunityPost.create(id2A, authorId, "Post 2A (t2, id=a...)", null, CommunityPostStatus.PUBLISHED, t2, t2, null));
        persistenceAdapter.save(CommunityPost.create(id4, authorId, "Post 4 (t1)", null, CommunityPostStatus.PUBLISHED, t1, t1, null));
        persistenceAdapter.save(CommunityPost.create(id5, authorId, "Post 5 (t0)", null, CommunityPostStatus.PUBLISHED, t0, t0, null));
        // Hidden post at t3 must not be returned in feed
        persistenceAdapter.save(CommunityPost.create(idHidden, authorId, "Hidden post", null, CommunityPostStatus.HIDDEN, t3, t3, null));

        // --- Page 1 (limit 2) ---
        List<CommunityPostPublicDTO> page1 = queryAdapter.findNewestPostsKeyset(null, null, 2);
        assertThat(page1).hasSize(2);
        assertThat(page1.get(0).id()).isEqualTo(id1);
        assertThat(page1.get(1).id()).isEqualTo(id2B); // id2B comes before id2A due to id DESC

        // --- Page 2 (limit 2, cursor = last item of page 1: t2, id2B) ---
        CommunityPostPublicDTO lastOfPage1 = page1.get(1);
        List<CommunityPostPublicDTO> page2 = queryAdapter.findNewestPostsKeyset(lastOfPage1.publishedAt(), lastOfPage1.id(), 2);
        assertThat(page2).hasSize(2);
        assertThat(page2.get(0).id()).isEqualTo(id2A);
        assertThat(page2.get(1).id()).isEqualTo(id4);

        // --- Page 3 (limit 2, cursor = last item of page 2: t1, id4) ---
        CommunityPostPublicDTO lastOfPage2 = page2.get(1);
        List<CommunityPostPublicDTO> page3 = queryAdapter.findNewestPostsKeyset(lastOfPage2.publishedAt(), lastOfPage2.id(), 2);
        assertThat(page3).hasSize(1);
        assertThat(page3.get(0).id()).isEqualTo(id5);

        // --- Page 4 (limit 2, cursor = last item of page 3: t0, id5) ---
        CommunityPostPublicDTO lastOfPage3 = page3.get(0);
        List<CommunityPostPublicDTO> page4 = queryAdapter.findNewestPostsKeyset(lastOfPage3.publishedAt(), lastOfPage3.id(), 2);
        assertThat(page4).isEmpty();

        // Verify total traversal: exactly 5 items, zero duplicates, no hidden post
        List<UUID> fullSequence = List.of(
                page1.get(0).id(), page1.get(1).id(),
                page2.get(0).id(), page2.get(1).id(),
                page3.get(0).id()
        );
        assertThat(fullSequence).containsExactly(id1, id2B, id2A, id4, id5);
    }

    @Test
    @DisplayName("Should find all lightweight ranking candidates from MySQL strictly for PUBLISHED posts")
    void shouldFindAllRankingCandidates() {
        UUID authorId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant t1 = now.minus(10, ChronoUnit.MINUTES);
        Instant t2 = now.minus(5, ChronoUnit.MINUTES);
        Instant t3 = now.minus(2, ChronoUnit.MINUTES);

        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID idPending = UUID.randomUUID();

        persistenceAdapter.save(CommunityPost.create(id1, authorId, "Post 1", null, CommunityPostStatus.PUBLISHED, t1, t1, null));
        persistenceAdapter.save(CommunityPost.create(id2, authorId, "Post 2", null, CommunityPostStatus.PUBLISHED, t2, t2, null));
        persistenceAdapter.save(CommunityPost.create(idPending, authorId, "Pending Post", null, CommunityPostStatus.PENDING_REVIEW, t3, null, t3));

        List<CommunityPostRankingCandidateDTO> candidates = queryAdapter.findAllRankingCandidates();
        assertThat(candidates).hasSize(2);
        assertThat(candidates).extracting(CommunityPostRankingCandidateDTO::postId)
                .containsExactlyInAnyOrder(id1, id2);
        assertThat(candidates).extracting(CommunityPostRankingCandidateDTO::publishedAt)
                .containsExactlyInAnyOrder(t1, t2);
    }

    @Test
    @DisplayName("Should find public posts by IDs in bulk and strictly filter out non-PUBLISHED posts")
    void shouldFindPublicPostsByIds() {
        UUID authorId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID idHidden = UUID.randomUUID();
        UUID missingId = UUID.randomUUID();

        persistenceAdapter.save(CommunityPost.create(id1, authorId, "Post 1", null, CommunityPostStatus.PUBLISHED, now, now, null));
        persistenceAdapter.save(CommunityPost.create(id2, authorId, "Post 2", null, CommunityPostStatus.PUBLISHED, now, now, null));
        persistenceAdapter.save(CommunityPost.create(idHidden, authorId, "Hidden post", null, CommunityPostStatus.HIDDEN, now, now, null));

        List<CommunityPostPublicDTO> list = queryAdapter.findPublicPostsByIds(List.of(id1, id2, idHidden, missingId));
        assertThat(list).hasSize(2);
        assertThat(list).extracting(CommunityPostPublicDTO::id).containsExactlyInAnyOrder(id1, id2);
        assertThat(list).extracting(CommunityPostPublicDTO::caption).containsExactlyInAnyOrder("Post 1", "Post 2");

        // Empty input test
        assertThat(queryAdapter.findPublicPostsByIds(List.of())).isEmpty();
    }

    @Test
    @DisplayName("Should query authored posts using keyset pagination with author isolation, tie-break and only PUBLISHED posts")
    void shouldQueryAuthoredPostsKeysetWithAuthorIsolationAndTieBreak() {
        UUID authorA = UUID.randomUUID();
        UUID authorB = UUID.randomUUID();
        UUID authorC = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        Instant t3 = now.minus(1, ChronoUnit.HOURS);
        Instant t2 = now.minus(2, ChronoUnit.HOURS); // Shared timestamp
        Instant t1 = now.minus(3, ChronoUnit.HOURS);
        Instant t0 = now.minus(4, ChronoUnit.HOURS);

        UUID aId1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID aId2B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"); // shared t2, higher id
        UUID aId2A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"); // shared t2, lower id
        UUID aId4 = UUID.fromString("44444444-4444-4444-4444-444444444444");
        UUID aId5 = UUID.fromString("55555555-5555-5555-5555-555555555555");
        UUID aIdHidden = UUID.fromString("77777777-7777-7777-7777-777777777777");

        // Author A posts (including one HIDDEN post that must be excluded)
        persistenceAdapter.save(CommunityPost.create(aId1, authorA, "A - Post 1 (t3)", null, CommunityPostStatus.PUBLISHED, t3, t3, null));
        persistenceAdapter.save(CommunityPost.create(aId2B, authorA, "A - Post 2B (t2, id=b...)", null, CommunityPostStatus.PUBLISHED, t2, t2, null));
        persistenceAdapter.save(CommunityPost.create(aId2A, authorA, "A - Post 2A (t2, id=a...)", null, CommunityPostStatus.PUBLISHED, t2, t2, null));
        persistenceAdapter.save(CommunityPost.create(aId4, authorA, "A - Post 4 (t1)", null, CommunityPostStatus.PUBLISHED, t1, t1, null));
        persistenceAdapter.save(CommunityPost.create(aId5, authorA, "A - Post 5 (t0)", null, CommunityPostStatus.PUBLISHED, t0, t0, null));
        persistenceAdapter.save(CommunityPost.create(aIdHidden, authorA, "A - Hidden post", null, CommunityPostStatus.HIDDEN, t3, t3, null));

        // Author B posts (interleaved timestamps)
        UUID bId1 = UUID.randomUUID();
        UUID bId2 = UUID.randomUUID();
        persistenceAdapter.save(CommunityPost.create(bId1, authorB, "B - Post 1 (t3)", null, CommunityPostStatus.PUBLISHED, t3, t3, null));
        persistenceAdapter.save(CommunityPost.create(bId2, authorB, "B - Post 2 (t1)", null, CommunityPostStatus.PUBLISHED, t1, t1, null));

        // --- Author A: Page 1 (limit 2) ---
        List<CommunityPostPublicDTO> aPage1 = queryAdapter.findAuthoredPostsKeyset(authorA, null, null, 2);
        assertThat(aPage1).hasSize(2);
        assertThat(aPage1.get(0).id()).isEqualTo(aId1);
        assertThat(aPage1.get(0).authorUserId()).isEqualTo(authorA);
        assertThat(aPage1.get(1).id()).isEqualTo(aId2B); // id2B comes before id2A due to id DESC
        assertThat(aPage1.get(1).authorUserId()).isEqualTo(authorA);

        // --- Author A: Page 2 (limit 2, cursor = last item of page 1: t2, aId2B) ---
        CommunityPostPublicDTO lastOfPage1 = aPage1.get(1);
        List<CommunityPostPublicDTO> aPage2 = queryAdapter.findAuthoredPostsKeyset(authorA, lastOfPage1.publishedAt(), lastOfPage1.id(), 2);
        assertThat(aPage2).hasSize(2);
        assertThat(aPage2.get(0).id()).isEqualTo(aId2A);
        assertThat(aPage2.get(0).authorUserId()).isEqualTo(authorA);
        assertThat(aPage2.get(1).id()).isEqualTo(aId4);
        assertThat(aPage2.get(1).authorUserId()).isEqualTo(authorA);

        // --- Author A: Page 3 (limit 2, cursor = last item of page 2: t1, aId4) ---
        CommunityPostPublicDTO lastOfPage2 = aPage2.get(1);
        List<CommunityPostPublicDTO> aPage3 = queryAdapter.findAuthoredPostsKeyset(authorA, lastOfPage2.publishedAt(), lastOfPage2.id(), 2);
        assertThat(aPage3).hasSize(1);
        assertThat(aPage3.get(0).id()).isEqualTo(aId5);
        assertThat(aPage3.get(0).authorUserId()).isEqualTo(authorA);

        // --- Author A: Page 4 (limit 2, cursor = last item of page 3: t0, aId5) ---
        CommunityPostPublicDTO lastOfPage3 = aPage3.get(0);
        List<CommunityPostPublicDTO> aPage4 = queryAdapter.findAuthoredPostsKeyset(authorA, lastOfPage3.publishedAt(), lastOfPage3.id(), 2);
        assertThat(aPage4).isEmpty();

        // Verify total traversal for Author A: exactly 5 items, zero from Author B, zero hidden
        List<UUID> fullSequenceA = List.of(
                aPage1.get(0).id(), aPage1.get(1).id(),
                aPage2.get(0).id(), aPage2.get(1).id(),
                aPage3.get(0).id()
        );
        assertThat(fullSequenceA).containsExactly(aId1, aId2B, aId2A, aId4, aId5);

        // --- Author B: Page 1 (limit 10) ---
        List<CommunityPostPublicDTO> bPage1 = queryAdapter.findAuthoredPostsKeyset(authorB, null, null, 10);
        assertThat(bPage1).hasSize(2);
        assertThat(bPage1).extracting(CommunityPostPublicDTO::id).containsExactly(bId1, bId2);
        assertThat(bPage1).extracting(CommunityPostPublicDTO::authorUserId).containsOnly(authorB);

        // --- Author C (no posts) ---
        List<CommunityPostPublicDTO> cPage1 = queryAdapter.findAuthoredPostsKeyset(authorC, null, null, 10);
        assertThat(cPage1).isEmpty();
    }

    @Test
    @DisplayName("Chronology & Exclusion Contract: initial approval sets publishedAt, edit under PRE_MODERATION hides from public and appears in pending, re-approval preserves original publishedAt")
    void shouldEnforceApprovalTimestampChronologyAndExclusionDuringReReview() {
        UUID authorId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();

        Instant t1 = Instant.parse("2026-10-04T15:00:00Z"); // submittedAt
        Instant t2 = Instant.parse("2026-10-04T16:20:00Z"); // initial approvedAt
        Instant t3 = Instant.parse("2026-10-04T16:40:00Z"); // editAt
        Instant t4 = Instant.parse("2026-10-04T16:45:00Z"); // reApproveAt

        // 1. Initial pending submission at 15:00 (createdAt=t1, reviewRequestedAt=t1, publishedAt=null)
        CommunityPost post = CommunityPost.create(
                postId, authorId, "Initial pending caption", null,
                CommunityPostStatus.PENDING_REVIEW, t1, null, t1
        );
        persistenceAdapter.save(post);

        // Assert absent from public lookups while initially pending
        assertThat(queryAdapter.findPublicPostById(postId)).isEmpty();
        assertThat(queryAdapter.findNewestPostsKeyset(null, null, 10)).isEmpty();
        assertThat(queryAdapter.findAllRankingCandidates()).isEmpty();
        assertThat(queryAdapter.findAuthoredPostsKeyset(authorId, null, null, 10)).isEmpty();

        // Assert present in own pending and admin pending
        assertThat(persistenceAdapter.findPendingReviewPostsByAuthor(authorId))
                .extracting(CommunityPost::getId).containsExactly(postId);
        assertThat(persistenceAdapter.findPendingReviewPosts(0, 10).items())
                .extracting(CommunityPost::getId).containsExactly(postId);

        // 2. Initial admin approval at 16:20 (approvedAt=t2)
        post.approve(t2);
        persistenceAdapter.save(post);

        // Assert public DTO has createdAt = 15:00, publishedAt = 16:20 (NOT 15:00)
        Optional<CommunityPostPublicDTO> publicDtoOpt = queryAdapter.findPublicPostById(postId);
        assertThat(publicDtoOpt).isPresent();
        CommunityPostPublicDTO publicDto = publicDtoOpt.get();
        assertThat(publicDto.createdAt()).isEqualTo(t1);
        assertThat(publicDto.publishedAt()).isEqualTo(t2);

        // Assert present in NEWEST, candidates, and authored public profile
        List<CommunityPostPublicDTO> newest = queryAdapter.findNewestPostsKeyset(null, null, 10);
        assertThat(newest).hasSize(1);
        assertThat(newest.get(0).publishedAt()).isEqualTo(t2);

        List<CommunityPostRankingCandidateDTO> candidates = queryAdapter.findAllRankingCandidates();
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).publishedAt()).isEqualTo(t2);

        List<CommunityPostPublicDTO> authored = queryAdapter.findAuthoredPostsKeyset(authorId, null, null, 10);
        assertThat(authored).hasSize(1);
        assertThat(authored.get(0).publishedAt()).isEqualTo(t2);

        // Assert absent from pending queues
        assertThat(persistenceAdapter.findPendingReviewPostsByAuthor(authorId)).isEmpty();
        assertThat(persistenceAdapter.findPendingReviewPosts(0, 10).items()).isEmpty();

        // 3. Effective edit under PRE_MODERATION at 16:40 (editAt=t3)
        boolean changed = post.editCaption(authorId, "Edited caption awaiting re-approval", t3, com.universe.community.domain.CommunityPublicationMode.PRE_MODERATION);
        assertThat(changed).isTrue();
        assertThat(post.getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
        assertThat(post.getCaption()).isEqualTo("Initial pending caption");
        assertThat(post.getPendingCaption()).isEqualTo("Edited caption awaiting re-approval");
        assertThat(post.isPendingCaptionEdit()).isTrue();
        assertThat(post.getReviewRequestedAt()).isEqualTo(t3);
        assertThat(post.getPublishedAt()).isEqualTo(t2); // strictly preserved!
        persistenceAdapter.save(post);

        // Assert post REMAINS visible publicly displaying the PREVIOUS approved caption (never pendingCaption!)
        Optional<CommunityPostPublicDTO> publicDtoWhilePending = queryAdapter.findPublicPostById(postId);
        assertThat(publicDtoWhilePending).isPresent();
        assertThat(publicDtoWhilePending.get().caption()).isEqualTo("Initial pending caption");
        assertThat(publicDtoWhilePending.get().publishedAt()).isEqualTo(t2);

        List<CommunityPostPublicDTO> newestWhilePending = queryAdapter.findNewestPostsKeyset(null, null, 10);
        assertThat(newestWhilePending).hasSize(1);
        assertThat(newestWhilePending.get(0).caption()).isEqualTo("Initial pending caption");
        assertThat(newestWhilePending.get(0).publishedAt()).isEqualTo(t2);

        List<CommunityPostRankingCandidateDTO> candidatesWhilePending = queryAdapter.findAllRankingCandidates();
        assertThat(candidatesWhilePending).hasSize(1);
        assertThat(candidatesWhilePending.get(0).postId()).isEqualTo(postId);
        assertThat(candidatesWhilePending.get(0).publishedAt()).isEqualTo(t2);

        List<CommunityPostPublicDTO> authoredWhilePending = queryAdapter.findAuthoredPostsKeyset(authorId, null, null, 10);
        assertThat(authoredWhilePending).hasSize(1);
        assertThat(authoredWhilePending.get(0).caption()).isEqualTo("Initial pending caption");

        // Assert present in own pending and admin pending queues as pending caption edit
        List<CommunityPost> ownPending = persistenceAdapter.findPendingReviewPostsByAuthor(authorId);
        assertThat(ownPending).hasSize(1);
        assertThat(ownPending.get(0).getId()).isEqualTo(postId);
        assertThat(ownPending.get(0).getPendingCaption()).isEqualTo("Edited caption awaiting re-approval");
        assertThat(ownPending.get(0).isPendingCaptionEdit()).isTrue();
        assertThat(ownPending.get(0).getPublishedAt()).isEqualTo(t2);

        List<CommunityPost> adminPending = persistenceAdapter.findPendingReviewPosts(0, 10).items();
        assertThat(adminPending).hasSize(1);
        assertThat(adminPending.get(0).getId()).isEqualTo(postId);
        assertThat(adminPending.get(0).getPendingCaption()).isEqualTo("Edited caption awaiting re-approval");
        assertThat(adminPending.get(0).isPendingCaptionEdit()).isTrue();
        assertThat(adminPending.get(0).getPublishedAt()).isEqualTo(t2);

        // 4. Admin approval of edited caption at 16:45 (reApproveAt=t4)
        post.approve(t4);
        assertThat(post.getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
        assertThat(post.getCaption()).isEqualTo("Edited caption awaiting re-approval");
        assertThat(post.getPendingCaption()).isNull();
        assertThat(post.isPendingCaptionEdit()).isFalse();
        assertThat(post.getReviewRequestedAt()).isNull();
        assertThat(post.getPublishedAt()).isEqualTo(t2); // MUST REMAIN t2 (16:20), NOT bumped to t4 (16:45)!
        persistenceAdapter.save(post);

        // Assert public presence now reflects approved new caption with original publishedAt t2
        Optional<CommunityPostPublicDTO> reApprovedDtoOpt = queryAdapter.findPublicPostById(postId);
        assertThat(reApprovedDtoOpt).isPresent();
        assertThat(reApprovedDtoOpt.get().caption()).isEqualTo("Edited caption awaiting re-approval");
        assertThat(reApprovedDtoOpt.get().publishedAt()).isEqualTo(t2);

        List<CommunityPostPublicDTO> restoredNewest = queryAdapter.findNewestPostsKeyset(null, null, 10);
        assertThat(restoredNewest).hasSize(1);
        assertThat(restoredNewest.get(0).caption()).isEqualTo("Edited caption awaiting re-approval");
        assertThat(restoredNewest.get(0).publishedAt()).isEqualTo(t2);
    }

    @Test
    @DisplayName("Review queue query plan audit via MySQL EXPLAIN: range scan without filesort")
    void shouldAuditReviewQueueQueryPlans() {
        UUID authorId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        for (int i = 0; i < 20; i++) {
            persistenceAdapter.save(CommunityPost.create(UUID.randomUUID(), authorId, "Published " + i, null, CommunityPostStatus.PUBLISHED, now.minusSeconds(i * 10), now.minusSeconds(i * 10), null));
        }
        for (int i = 0; i < 5; i++) {
            persistenceAdapter.save(CommunityPost.create(UUID.randomUUID(), authorId, "Pending " + i, null, CommunityPostStatus.PENDING_REVIEW, now.minusSeconds(i * 10), null, now.minusSeconds(i * 10)));
        }
        for (int i = 0; i < 3; i++) {
            CommunityPost post = CommunityPost.rehydrate(UUID.randomUUID(), authorId, "Approved " + i, "Candidate " + i, null, CommunityPostStatus.PUBLISHED, 0, now.minusSeconds(100), now, now.minusSeconds(100), now.minusSeconds(i * 5));
            persistenceAdapter.save(post);
        }

        // 1. Admin review queue query
        List<Map<String, Object>> adminExplain = jdbcTemplate.queryForList("""
                EXPLAIN SELECT * FROM community_posts p
                WHERE p.review_requested_at IS NOT NULL
                  AND ((p.status = 'PENDING_REVIEW')
                    OR (p.status = 'PUBLISHED' AND p.pending_caption IS NOT NULL))
                ORDER BY p.review_requested_at ASC, p.id ASC
                LIMIT 10
                """);
        assertThat(adminExplain).isNotEmpty();
        Map<String, Object> adminPlan = adminExplain.get(0);
        assertThat(adminPlan.get("key")).isEqualTo("idx_community_posts_review_queue");
        assertThat(adminPlan.get("type")).isEqualTo("range");
        assertThat(adminPlan.get("Extra").toString()).doesNotContain("Using filesort");

        // 2. Author review queue query
        List<Map<String, Object>> authorExplain = jdbcTemplate.queryForList("""
                EXPLAIN SELECT * FROM community_posts p
                WHERE p.author_user_id = ?
                  AND p.review_requested_at IS NOT NULL
                  AND ((p.status = 'PENDING_REVIEW')
                    OR (p.status = 'PUBLISHED' AND p.pending_caption IS NOT NULL))
                ORDER BY p.review_requested_at DESC, p.id DESC
                """, authorId.toString());
        assertThat(authorExplain).isNotEmpty();
        Map<String, Object> authorPlan = authorExplain.get(0);
        assertThat(authorPlan.get("key")).isEqualTo("idx_community_posts_author_review_queue");
        assertThat(authorPlan.get("type")).isEqualTo("range");
        assertThat(authorPlan.get("Extra").toString()).doesNotContain("Using filesort");
    }

    @Test
    @DisplayName("Public isolation regression: public DTO contracts and feeds strictly isolate pendingCaption")
    void shouldVerifyPublicIsolationWhilePendingCaptionExistsAndOnModerationOutcomes() {
        // 1. Reflection contract audit: public DTOs must never contain pendingCaption
        assertThat(CommunityPostPublicDTO.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .doesNotContain("pendingCaption", "pending_caption");
        assertThat(com.universe.community.contracts.dto.CommunityPostFeedItemDTO.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .doesNotContain("pendingCaption", "pending_caption");
        assertThat(com.universe.community.contracts.dto.CommunityPostRankingCandidateDTO.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .doesNotContain("pendingCaption", "pending_caption");

        UUID authorId = UUID.randomUUID();
        UUID postRejectId = UUID.randomUUID();
        UUID postApproveId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant t0 = now.minusSeconds(3600);
        Instant t1 = now.minusSeconds(600);
        Instant t2 = now.minusSeconds(60);

        // 2. Seed post with pending candidate
        CommunityPost postReject = CommunityPost.rehydrate(
                postRejectId, authorId, "Original approved caption A", "Candidate pending edit A", null,
                CommunityPostStatus.PUBLISHED, 0, t0, t1, t0, t1
        );
        persistenceAdapter.save(postReject);

        // Verify public lookups read OLD approved caption while pending edit exists
        assertThat(queryAdapter.findPublicPostById(postRejectId)).isPresent()
                .hasValueSatisfying(dto -> {
                    assertThat(dto.caption()).isEqualTo("Original approved caption A");
                    assertThat(dto.publishedAt()).isEqualTo(t0);
                });
        assertThat(queryAdapter.findNewestPostsKeyset(null, null, 10))
                .extracting(CommunityPostPublicDTO::caption)
                .contains("Original approved caption A")
                .doesNotContain("Candidate pending edit A");
        assertThat(queryAdapter.findAuthoredPostsKeyset(authorId, null, null, 10))
                .extracting(CommunityPostPublicDTO::caption)
                .contains("Original approved caption A")
                .doesNotContain("Candidate pending edit A");
        assertThat(queryAdapter.findAllRankingCandidates())
                .extracting(CommunityPostRankingCandidateDTO::postId)
                .contains(postRejectId);

        // 3. Reject candidate: public caption remains OLD, publishedAt preserved at t0
        postReject.reject(t2);
        persistenceAdapter.save(postReject);
        assertThat(queryAdapter.findPublicPostById(postRejectId)).isPresent()
                .hasValueSatisfying(dto -> {
                    assertThat(dto.caption()).isEqualTo("Original approved caption A");
                    assertThat(dto.publishedAt()).isEqualTo(t0);
                });

        // 4. Seed another post for approval verification
        CommunityPost postApprove = CommunityPost.rehydrate(
                postApproveId, authorId, "Original approved caption B", "Candidate pending edit B", null,
                CommunityPostStatus.PUBLISHED, 0, t0, t1, t0, t1
        );
        persistenceAdapter.save(postApprove);

        // Approve candidate: public caption becomes NEW, publishedAt strictly preserved at t0
        postApprove.approve(t2);
        persistenceAdapter.save(postApprove);
        assertThat(queryAdapter.findPublicPostById(postApproveId)).isPresent()
                .hasValueSatisfying(dto -> {
                    assertThat(dto.caption()).isEqualTo("Candidate pending edit B");
                    assertThat(dto.publishedAt()).isEqualTo(t0);
                });
        assertThat(queryAdapter.findNewestPostsKeyset(null, null, 10))
                .extracting(CommunityPostPublicDTO::caption)
                .contains("Candidate pending edit B")
                .doesNotContain("Original approved caption B");
    }
}
