package com.universe.interaction.entry.wiki;

import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MS-05E6E: Wiki Comment Privacy, Publication Gate, Target Scope, and Tombstone Isolation Integration Tests.
 *
 * <p>Validates strict privacy and fail-closed security invariants across all Wiki comment endpoints
 * using real MySQL persistence:
 * <ul>
 *   <li>1. Publication Gate: Published articles allow access; Draft, Archived, and Missing articles fail closed with 404;</li>
 *   <li>2. Response-Shape Indistinguishability: Draft, Archived, and Missing articles return uniform 404 with empty body without leaking state;</li>
 *   <li>3. Lifecycle Transitions: Comments hidden when archived while database rows remain preserved;</li>
 *   <li>4. Target Isolation: Comments belonging to other articles or Novel chapters cannot be read or mutated via Wiki routes;</li>
 *   <li>5. Tombstone & Deleted Root Privacy: Deleted roots hide entire threads and revisions; deleted replies hide body and author;</li>
 *   <li>6. Author Privacy: Tombstone authors are excluded from identity lookups;</li>
 *   <li>7. Revision Privacy: Revisions expose only whitelisted DTO fields and hide deleted/draft/archived comment revisions;</li>
 *   <li>8. Mutation Privacy Gates: Unpublished/missing articles reject create, reply, edit, and delete mutations before persistence.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "security.remember-me.key=test-secret-key-1234567890123456",
        "security.remember-me.secure-cookie=false",
        "spring.mail.username=test@universe.local",
        "spring.mail.password=testpassword",
        "cloudinary.cloud_name=test",
        "cloudinary.api_key=test",
        "cloudinary.api_secret=test",
        "spring.security.oauth2.client.registration.google.client-id=test-client-id",
        "spring.security.oauth2.client.registration.google.client-secret=test-client-secret"
})
@DisplayName("MS-05E6E Wiki Article Comment Privacy Integration Tests")
class WikiArticleCommentPrivacyIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    private static final Instant NOW = Instant.parse("2026-09-19T10:00:00Z");
    private static final String USER_EMAIL = "privacy_author@test.local";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID authorUserId;
    private UUID publishedArticleId;
    private UUID draftArticleId;
    private UUID archivedArticleId;

    @BeforeEach
    void setUp() {
        cleanup();

        authorUserId = UUID.randomUUID();
        insertUser(authorUserId, USER_EMAIL, "Privacy Author");

        publishedArticleId = UUID.randomUUID();
        insertArticle(publishedArticleId, "Published Article", "wiki-priv-pub-" + publishedArticleId, "PUBLISHED", NOW);

        draftArticleId = UUID.randomUUID();
        insertArticle(draftArticleId, "Draft Article", "wiki-priv-draft-" + draftArticleId, "DRAFT", NOW);

        archivedArticleId = UUID.randomUUID();
        insertArticle(archivedArticleId, "Archived Article", "wiki-priv-arc-" + archivedArticleId, "ARCHIVED", NOW);
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    private void cleanup() {
        try {
            jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) con -> {
                try (java.sql.Statement stmt = con.createStatement()) {
                    try {
                        stmt.execute("SET FOREIGN_KEY_CHECKS = 0;");
                        stmt.executeUpdate("DELETE FROM interaction_comment_revisions;");
                        stmt.executeUpdate("DELETE FROM interaction_comments WHERE parent_comment_id IS NOT NULL;");
                        stmt.executeUpdate("DELETE FROM interaction_comments;");
                        stmt.executeUpdate("DELETE FROM wiki_articles WHERE slug LIKE 'wiki-priv-%';");
                        stmt.executeUpdate("DELETE FROM identity_users WHERE email LIKE '%@test.local';");
                    } finally {
                        stmt.execute("SET FOREIGN_KEY_CHECKS = 1;");
                    }
                }
                return null;
            });
        } catch (Exception ex) {
            System.err.println("Cleanup error in WikiArticleCommentPrivacyIntegrationTest: " + ex.getMessage());
        }
    }

    // =========================================================================
    // 1. PUBLISHED ARTICLE CONTRACT
    // =========================================================================

    @Test
    @WithAnonymousUser
    @DisplayName("Published article: feed, thread, and revisions are accessible to public")
    void publishedArticleEndpointsAreAccessible() throws Exception {
        UUID rootId = UUID.randomUUID();
        UUID replyId = UUID.randomUUID();
        insertComment(rootId, publishedArticleId, authorUserId, null, null, "Root on published", "ACTIVE", NOW, NOW, null);
        insertComment(replyId, publishedArticleId, authorUserId, rootId, rootId, "Reply on published", "ACTIVE", NOW.plusSeconds(1), NOW.plusSeconds(1), null);
        insertRevision(UUID.randomUUID(), rootId, 1, "Historical root body", NOW.minusSeconds(10));

        // Feed
        mockMvc.perform(get("/api/wiki/articles/" + publishedArticleId + "/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.threadCount").value(1))
                .andExpect(jsonPath("$.commentCount").value(2))
                .andExpect(jsonPath("$.threads[0].root.id").value(rootId.toString()))
                .andExpect(jsonPath("$.threads[0].root.body").value("Root on published"))
                .andExpect(jsonPath("$.threads[0].replies[0].id").value(replyId.toString()));

        // Single thread
        mockMvc.perform(get("/api/wiki/articles/" + publishedArticleId + "/comments/" + rootId + "/thread"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.root.id").value(rootId.toString()))
                .andExpect(jsonPath("$.replies[0].id").value(replyId.toString()));

        // Revisions
        mockMvc.perform(get("/api/wiki/articles/" + publishedArticleId + "/comments/" + rootId + "/revisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].revisionNumber").value(1))
                .andExpect(jsonPath("$.items[0].body").value("Historical root body"));
    }

    // =========================================================================
    // 2. DRAFT ARTICLE FAIL-CLOSED CONTRACT
    // =========================================================================

    @Test
    @WithAnonymousUser
    @DisplayName("Draft article: feed, thread, and revisions fail closed with neutral 404")
    void draftArticleReadsFailClosedWith404() throws Exception {
        UUID rootId = UUID.randomUUID();
        insertComment(rootId, draftArticleId, authorUserId, null, null, "Hidden draft comment", "ACTIVE", NOW, NOW, null);
        insertRevision(UUID.randomUUID(), rootId, 1, "Hidden revision", NOW.minusSeconds(10));

        mockMvc.perform(get("/api/wiki/articles/" + draftArticleId + "/comments"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        mockMvc.perform(get("/api/wiki/articles/" + draftArticleId + "/comments/" + rootId + "/thread"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        mockMvc.perform(get("/api/wiki/articles/" + draftArticleId + "/comments/" + rootId + "/revisions"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
    }

    @Test
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    @DisplayName("Draft article: create, reply, edit, delete mutations fail closed with 404 and do not mutate DB")
    void draftArticleMutationsFailClosedWith404() throws Exception {
        UUID rootId = UUID.randomUUID();
        insertComment(rootId, draftArticleId, authorUserId, null, null, "Existing draft comment", "ACTIVE", NOW, NOW, null);

        int commentsBefore = countComments();

        // Create root -> 404
        mockMvc.perform(post("/api/wiki/articles/" + draftArticleId + "/comments")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"New root on draft\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        // Reply -> 404
        mockMvc.perform(post("/api/wiki/articles/" + draftArticleId + "/comments/" + rootId + "/replies")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"New reply on draft\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        // Edit -> 404
        mockMvc.perform(patch("/api/wiki/articles/" + draftArticleId + "/comments/" + rootId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Mutated draft body\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        // Delete -> 404
        mockMvc.perform(delete("/api/wiki/articles/" + draftArticleId + "/comments/" + rootId)
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        // Assert database state unchanged
        assertThat(countComments()).isEqualTo(commentsBefore);
        String currentBody = jdbcTemplate.queryForObject(
                "SELECT body FROM interaction_comments WHERE id = ?", String.class, rootId.toString());
        assertThat(currentBody).isEqualTo("Existing draft comment");
        String currentStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM interaction_comments WHERE id = ?", String.class, rootId.toString());
        assertThat(currentStatus).isEqualTo("ACTIVE");
    }

    // =========================================================================
    // 3. ARCHIVED ARTICLE FAIL-CLOSED CONTRACT
    // =========================================================================

    @Test
    @WithAnonymousUser
    @DisplayName("Archived article: feed, thread, and revisions fail closed with neutral 404 while rows remain stored")
    void archivedArticleReadsFailClosedWith404() throws Exception {
        UUID rootId = UUID.randomUUID();
        insertComment(rootId, archivedArticleId, authorUserId, null, null, "Archived comment", "ACTIVE", NOW, NOW, null);
        insertRevision(UUID.randomUUID(), rootId, 1, "Archived revision", NOW.minusSeconds(10));

        mockMvc.perform(get("/api/wiki/articles/" + archivedArticleId + "/comments"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        mockMvc.perform(get("/api/wiki/articles/" + archivedArticleId + "/comments/" + rootId + "/thread"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        mockMvc.perform(get("/api/wiki/articles/" + archivedArticleId + "/comments/" + rootId + "/revisions"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        // Prove row is still stored in DB
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_comments WHERE id = ?", Integer.class, rootId.toString());
        assertThat(count).isEqualTo(1);
    }

    @Test
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    @DisplayName("Archived article: mutations fail closed with 404")
    void archivedArticleMutationsFailClosedWith404() throws Exception {
        UUID rootId = UUID.randomUUID();
        insertComment(rootId, archivedArticleId, authorUserId, null, null, "Archived comment", "ACTIVE", NOW, NOW, null);

        int commentsBefore = countComments();

        // Create root -> 404
        mockMvc.perform(post("/api/wiki/articles/" + archivedArticleId + "/comments")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"New root on archived\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        // Reply -> 404
        mockMvc.perform(post("/api/wiki/articles/" + archivedArticleId + "/comments/" + rootId + "/replies")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"New reply on archived\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        // Edit -> 404
        mockMvc.perform(patch("/api/wiki/articles/" + archivedArticleId + "/comments/" + rootId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Mutated archived body\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        // Delete -> 404
        mockMvc.perform(delete("/api/wiki/articles/" + archivedArticleId + "/comments/" + rootId)
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        assertThat(countComments()).isEqualTo(commentsBefore);
        String currentBody = jdbcTemplate.queryForObject(
                "SELECT body FROM interaction_comments WHERE id = ?", String.class, rootId.toString());
        assertThat(currentBody).isEqualTo("Archived comment");
    }

    // =========================================================================
    // 4. MISSING ARTICLE & INDISTINGUISHABILITY
    // =========================================================================

    @Test
    @WithAnonymousUser
    @DisplayName("Missing article returns neutral 404 with empty body indistinguishable from Draft/Archived")
    void missingArticleReturnsNeutral404() throws Exception {
        UUID nonExistentArticleId = UUID.randomUUID();
        UUID nonExistentCommentId = UUID.randomUUID();

        mockMvc.perform(get("/api/wiki/articles/" + nonExistentArticleId + "/comments"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        mockMvc.perform(get("/api/wiki/articles/" + nonExistentArticleId + "/comments/" + nonExistentCommentId + "/thread"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        mockMvc.perform(get("/api/wiki/articles/" + nonExistentArticleId + "/comments/" + nonExistentCommentId + "/revisions"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
    }

    @Test
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    @DisplayName("Missing article: create, reply, edit, delete mutations fail closed with 404 and do not mutate DB")
    void missingArticleMutationsFailClosedWith404() throws Exception {
        UUID nonExistentArticleId = UUID.randomUUID();
        UUID nonExistentCommentId = UUID.randomUUID();

        int commentsBefore = countComments();

        // Create root -> 404
        mockMvc.perform(post("/api/wiki/articles/" + nonExistentArticleId + "/comments")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Missing article create payload\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        // Reply -> 404
        mockMvc.perform(post("/api/wiki/articles/" + nonExistentArticleId + "/comments/" + nonExistentCommentId + "/replies")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Missing article reply payload\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        // Edit -> 404
        mockMvc.perform(patch("/api/wiki/articles/" + nonExistentArticleId + "/comments/" + nonExistentCommentId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Missing article edit payload\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        // Delete -> 404
        mockMvc.perform(delete("/api/wiki/articles/" + nonExistentArticleId + "/comments/" + nonExistentCommentId)
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        // Assert database state unchanged
        assertThat(countComments()).isEqualTo(commentsBefore);
        Integer payloadMatches = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_comments WHERE body LIKE '%Missing article%'", Integer.class);
        assertThat(payloadMatches).isZero();
    }

    // =========================================================================
    // 5. LIFECYCLE TRANSITION: ARCHIVED HIDES COMMENTS WHILE PRESERVING DB ROWS
    // =========================================================================

    @Test
    @WithAnonymousUser
    @DisplayName("Lifecycle transition: comments hidden when article archived, while interaction rows remain preserved")
    void publishedCommentsBecomeHiddenOnArchiveWhileInteractionRowsRemainPreserved() throws Exception {
        UUID rootId = UUID.randomUUID();
        insertComment(rootId, publishedArticleId, authorUserId, null, null, "Lifecycle comment", "ACTIVE", NOW, NOW, null);

        // Phase 1: PUBLISHED -> visible
        mockMvc.perform(get("/api/wiki/articles/" + publishedArticleId + "/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.threadCount").value(1));

        // Phase 2: Transition to ARCHIVED -> hidden (404)
        jdbcTemplate.update("UPDATE wiki_articles SET status = 'ARCHIVED', archived_by = ?, archived_at = ? WHERE id = ?",
                authorUserId.toString(), Timestamp.from(NOW), publishedArticleId.toString());

        mockMvc.perform(get("/api/wiki/articles/" + publishedArticleId + "/comments"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
        mockMvc.perform(get("/api/wiki/articles/" + publishedArticleId + "/comments/" + rootId + "/thread"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        // Phase 3: Assert interaction rows remain intact in DB
        Integer commentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_comments WHERE id = ?", Integer.class, rootId.toString());
        assertThat(commentCount).isEqualTo(1);
        String body = jdbcTemplate.queryForObject(
                "SELECT body FROM interaction_comments WHERE id = ?", String.class, rootId.toString());
        assertThat(body).isEqualTo("Lifecycle comment");
    }

    // =========================================================================
    // 6. TARGET ISOLATION
    // =========================================================================

    @Test
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    @DisplayName("Target isolation: cannot read or mutate comments belonging to another Wiki article")
    void crossArticleScopeMismatchReturns404() throws Exception {
        UUID otherArticleId = UUID.randomUUID();
        insertArticle(otherArticleId, "Other Article", "wiki-priv-other-" + otherArticleId, "PUBLISHED", NOW);

        UUID commentOnOtherArticle = UUID.randomUUID();
        insertComment(commentOnOtherArticle, otherArticleId, authorUserId, null, null, "Comment on other", "ACTIVE", NOW, NOW, null);

        // Try reading commentOnOtherArticle via publishedArticleId endpoint -> 404
        mockMvc.perform(get("/api/wiki/articles/" + publishedArticleId + "/comments/" + commentOnOtherArticle + "/thread"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        mockMvc.perform(get("/api/wiki/articles/" + publishedArticleId + "/comments/" + commentOnOtherArticle + "/revisions"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        // Try mutating commentOnOtherArticle via publishedArticleId endpoint -> 404
        mockMvc.perform(patch("/api/wiki/articles/" + publishedArticleId + "/comments/" + commentOnOtherArticle)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Cross-article edit attempt\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        mockMvc.perform(delete("/api/wiki/articles/" + publishedArticleId + "/comments/" + commentOnOtherArticle)
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        // Verify body unchanged
        String body = jdbcTemplate.queryForObject(
                "SELECT body FROM interaction_comments WHERE id = ?", String.class, commentOnOtherArticle.toString());
        assertThat(body).isEqualTo("Comment on other");
    }

    @Test
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    @DisplayName("Target isolation: cannot read or mutate comments belonging to NOVEL_CHAPTER even with matching scalar UUID")
    void crossTargetTypeMismatchWithNovelChapterReturns404() throws Exception {
        // Create comment targeting NOVEL_CHAPTER with target_id = publishedArticleId
        UUID novelCommentId = UUID.randomUUID();
        insertNovelComment(novelCommentId, publishedArticleId, authorUserId, null, null, "Novel chapter comment", "ACTIVE", NOW, NOW, null);

        // Access via Wiki endpoint with same scalar UUID -> 404
        mockMvc.perform(get("/api/wiki/articles/" + publishedArticleId + "/comments/" + novelCommentId + "/thread"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        mockMvc.perform(get("/api/wiki/articles/" + publishedArticleId + "/comments/" + novelCommentId + "/revisions"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        mockMvc.perform(patch("/api/wiki/articles/" + publishedArticleId + "/comments/" + novelCommentId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Cross-target edit attempt\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        mockMvc.perform(delete("/api/wiki/articles/" + publishedArticleId + "/comments/" + novelCommentId)
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
    }

    // =========================================================================
    // 7. TOMBSTONE & DELETED ROOT PRIVACY
    // =========================================================================

    @Test
    @WithAnonymousUser
    @DisplayName("Deleted root privacy: whole thread hidden from feed, thread returns 404, revisions return 404")
    void deletedRootHidesEntireThreadAndRevisions() throws Exception {
        UUID rootId = UUID.randomUUID();
        UUID replyId = UUID.randomUUID();
        // Soft-deleted root with active child reply
        insertComment(rootId, publishedArticleId, authorUserId, null, null, null, "DELETED", NOW, NOW.plusSeconds(5), NOW.plusSeconds(5));
        insertComment(replyId, publishedArticleId, authorUserId, rootId, rootId, "Reply under deleted root", "ACTIVE", NOW.plusSeconds(1), NOW.plusSeconds(1), null);
        insertRevision(UUID.randomUUID(), rootId, 1, "Historical root body before delete", NOW.minusSeconds(10));

        // Feed must NOT expose this thread
        mockMvc.perform(get("/api/wiki/articles/" + publishedArticleId + "/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.threadCount").value(0))
                .andExpect(jsonPath("$.commentCount").value(0))
                .andExpect(jsonPath("$.threads", hasSize(0)));

        // Single thread endpoint returns 404 (does not leak reply descendants)
        mockMvc.perform(get("/api/wiki/articles/" + publishedArticleId + "/comments/" + rootId + "/thread"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        // Revisions on deleted root return 404
        mockMvc.perform(get("/api/wiki/articles/" + publishedArticleId + "/comments/" + rootId + "/revisions"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Tombstone privacy: deleted intermediate reply hides body, author profile, and edit/delete capability")
    void deletedIntermediateReplyTombstoneHidesBodyAndAuthor() throws Exception {
        UUID rootId = UUID.randomUUID();
        UUID intermediateReplyId = UUID.randomUUID();
        UUID leafReplyId = UUID.randomUUID();

        insertComment(rootId, publishedArticleId, authorUserId, null, null, "Root comment", "ACTIVE", NOW, NOW, null);
        // Intermediate reply is deleted (tombstone)
        insertComment(intermediateReplyId, publishedArticleId, authorUserId, rootId, rootId, null, "DELETED", NOW.plusSeconds(1), NOW.plusSeconds(5), NOW.plusSeconds(5));
        // Leaf reply is active under intermediate reply
        insertComment(leafReplyId, publishedArticleId, authorUserId, intermediateReplyId, rootId, "Active leaf reply", "ACTIVE", NOW.plusSeconds(2), NOW.plusSeconds(2), null);

        mockMvc.perform(get("/api/wiki/articles/" + publishedArticleId + "/comments/" + rootId + "/thread"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replies", hasSize(2)))
                // Tombstone assertions
                .andExpect(jsonPath("$.replies[0].id").value(intermediateReplyId.toString()))
                .andExpect(jsonPath("$.replies[0].tombstone").value(true))
                .andExpect(jsonPath("$.replies[0].body").value(nullValue()))
                .andExpect(jsonPath("$.replies[0].author").value(nullValue()))
                .andExpect(jsonPath("$.replies[0].canEdit").value(false))
                .andExpect(jsonPath("$.replies[0].canDelete").value(false))
                // Active leaf reply assertions
                .andExpect(jsonPath("$.replies[1].id").value(leafReplyId.toString()))
                .andExpect(jsonPath("$.replies[1].tombstone").value(false))
                .andExpect(jsonPath("$.replies[1].body").value("Active leaf reply"))
                .andExpect(jsonPath("$.replies[1].author.displayName").value("Privacy Author"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Deleted comment revisions return 404: historical bodies are never exposed")
    void deletedCommentRevisionsReturn404() throws Exception {
        UUID rootId = UUID.randomUUID();
        insertComment(rootId, publishedArticleId, authorUserId, null, null, null, "DELETED", NOW, NOW.plusSeconds(10), NOW.plusSeconds(10));
        insertRevision(UUID.randomUUID(), rootId, 1, "Secret historical body 1", NOW.minusSeconds(10));
        insertRevision(UUID.randomUUID(), rootId, 2, "Secret historical body 2", NOW.minusSeconds(5));

        mockMvc.perform(get("/api/wiki/articles/" + publishedArticleId + "/comments/" + rootId + "/revisions"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
    }

    // =========================================================================
    // 8. REVISION PRIVACY DTO FIELDS
    // =========================================================================

    @Test
    @WithAnonymousUser
    @DisplayName("Revision privacy: DTO exposes only revisionNumber, body, createdAt; never internal IDs or identities")
    void revisionDtoExposesOnlyWhitelistedFields() throws Exception {
        UUID rootId = UUID.randomUUID();
        UUID revEntityId = UUID.randomUUID();
        insertComment(rootId, publishedArticleId, authorUserId, null, null, "Current active body", "ACTIVE", NOW, NOW.plusSeconds(20), null);
        insertRevision(revEntityId, rootId, 1, "Previous body version 1", NOW);

        mockMvc.perform(get("/api/wiki/articles/" + publishedArticleId + "/comments/" + rootId + "/revisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].revisionNumber").value(1))
                .andExpect(jsonPath("$.items[0].body").value("Previous body version 1"))
                .andExpect(jsonPath("$.items[0].createdAt").isNotEmpty())
                // Ensure internal fields are absent
                .andExpect(jsonPath("$.items[0].id").doesNotExist())
                .andExpect(jsonPath("$.items[0].authorUserId").doesNotExist())
                .andExpect(jsonPath("$.items[0].editorUserId").doesNotExist())
                .andExpect(jsonPath("$.items[0].moderatorUserId").doesNotExist())
                .andExpect(jsonPath("$.items[0].commentId").doesNotExist());
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================

    private void insertUser(UUID id, String email, String displayName) {
        String handle = "u_" + id.toString().replace("-", "");
        jdbcTemplate.update("""
                INSERT INTO identity_users (id, email, password_hash, display_name, public_handle, status, role, auth_provider, created_at, updated_at, avatar_customized)
                VALUES (?, ?, '$2a$10$hash', ?, ?, 'ACTIVE', 'USER', 'LOCAL', ?, ?, false)
                """, id.toString(), email, displayName, handle, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private void insertArticle(UUID id, String title, String slug, String status, Instant now) {
        String publishedBy = "PUBLISHED".equals(status) ? authorUserId.toString() : null;
        Timestamp publishedAt = "PUBLISHED".equals(status) ? Timestamp.from(now) : null;
        String archivedBy = "ARCHIVED".equals(status) ? authorUserId.toString() : null;
        Timestamp archivedAt = "ARCHIVED".equals(status) ? Timestamp.from(now) : null;

        jdbcTemplate.update("""
                INSERT INTO wiki_articles (id, title, slug, article_type, summary, content, status, created_by, updated_by, published_by, archived_by, aggregate_version, persistence_version, content_version, created_at, updated_at, published_at, archived_at)
                VALUES (?, ?, ?, 'CHARACTER', 'Summary text', 'Content text', ?, ?, ?, ?, ?, 1, 0, 1, ?, ?, ?, ?)
                """, id.toString(), title, slug, status, authorUserId.toString(), authorUserId.toString(),
                publishedBy, archivedBy, Timestamp.from(now), Timestamp.from(now), publishedAt, archivedAt);
    }

    private void insertComment(UUID id, UUID articleId, UUID authorId, UUID parentId, UUID rootId, String body, String status, Instant created, Instant updated, Instant deleted) {
        jdbcTemplate.update("""
                INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, thread_root_comment_id, body, status, created_at, updated_at, deleted_at)
                VALUES (?, 'WIKI_ARTICLE', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id.toString(), articleId.toString(), authorId.toString(),
                parentId != null ? parentId.toString() : null,
                rootId != null ? rootId.toString() : null,
                body, status, Timestamp.from(created), Timestamp.from(updated),
                deleted != null ? Timestamp.from(deleted) : null);
    }

    private void insertNovelComment(UUID id, UUID chapterId, UUID authorId, UUID parentId, UUID rootId, String body, String status, Instant created, Instant updated, Instant deleted) {
        jdbcTemplate.update("""
                INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, thread_root_comment_id, body, status, created_at, updated_at, deleted_at)
                VALUES (?, 'NOVEL_CHAPTER', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id.toString(), chapterId.toString(), authorId.toString(),
                parentId != null ? parentId.toString() : null,
                rootId != null ? rootId.toString() : null,
                body, status, Timestamp.from(created), Timestamp.from(updated),
                deleted != null ? Timestamp.from(deleted) : null);
    }

    private void insertRevision(UUID id, UUID commentId, int revNum, String body, Instant created) {
        jdbcTemplate.update("""
                INSERT INTO interaction_comment_revisions (id, comment_id, revision_number, body, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, id.toString(), commentId.toString(), revNum, body, Timestamp.from(created));
    }

    private int countComments() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM interaction_comments", Integer.class);
        return count != null ? count : 0;
    }
}
