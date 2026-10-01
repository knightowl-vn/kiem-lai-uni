package com.universe.interaction.entry.community;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityTestSupport;
import com.universe.interaction.application.ports.CommunityPostEngagementQueryPort;
import com.universe.interaction.application.query.CommentTargetMetrics;
import com.universe.interaction.application.query.CommunityPostEngagementCountsDTO;
import com.universe.interaction.application.query.GetCommentTargetMetricsUseCase;
import com.universe.interaction.domain.CommentTarget;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MS-07B8.2.2-CORRECTIVE-1: Real persistence integration test suite for Community post comments.
 *
 * <p>Proves:
 * <ul>
 *   <li>1. Authoritative count consistency across N -> N+1 -> N+2 mutation lifecycle with real MySQL;</li>
 *   <li>2. Root thread count is NOT substituted for total active comment count;</li>
 *   <li>3. Query capability used by feeds returns identical synchronized count;</li>
 *   <li>4. Strict cross-post target isolation (post A root cannot be fetched under post B);</li>
 *   <li>5. Strict cross-bounded-context target isolation (Wiki/Novel comments rejected under Community routes);</li>
 *   <li>6. Reply parent cross-target rejections (Wiki/Novel/foreign post parents return 404).</li>
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
@DisplayName("CommunityPostComment Real Persistence Integration Tests")
class CommunityPostCommentIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    private static final Instant NOW = Instant.parse("2026-09-19T10:00:00Z");
    private static final String USER_EMAIL = "comm_integ_author@test.local";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CommunityPostEngagementQueryPort communityPostEngagementQueryPort;

    @Autowired
    private GetCommentTargetMetricsUseCase getCommentTargetMetricsUseCase;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID authorUserId;
    private UUID postAId;
    private UUID postBId;
    private UUID wikiArticleId;
    private UUID novelChapterId;

    @BeforeEach
    void setUp() {
        cleanup();

        authorUserId = UUID.randomUUID();
        insertUser(authorUserId, USER_EMAIL, "Community Author", "comm_author");

        postAId = UUID.randomUUID();
        insertPost(postAId, authorUserId, "Post A Caption", NOW);

        postBId = UUID.randomUUID();
        insertPost(postBId, authorUserId, "Post B Caption", NOW);

        wikiArticleId = UUID.randomUUID();
        novelChapterId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    private void cleanup() {
        try {
            jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) con -> {
                try (java.sql.Statement stmt = con.createStatement()) {
                    stmt.execute("SET FOREIGN_KEY_CHECKS = 0;");
                    try {
                        stmt.executeUpdate("DELETE FROM interaction_comments WHERE author_user_id IN (SELECT id FROM identity_users WHERE email LIKE '%@test.local');");
                        stmt.executeUpdate("DELETE FROM community_posts WHERE author_user_id IN (SELECT id FROM identity_users WHERE email LIKE '%@test.local');");
                        stmt.executeUpdate("DELETE FROM identity_users WHERE email LIKE '%@test.local';");
                    } finally {
                        stmt.execute("SET FOREIGN_KEY_CHECKS = 1;");
                    }
                }
                return null;
            });
        } catch (Exception ex) {
            System.err.println("Cleanup error in CommunityPostCommentIntegrationTest: " + ex.getMessage());
        }
    }

    private RequestPostProcessor authenticatedIdentity(UUID userId) {
        AuthenticatedRequestIdentity identity = new AuthenticatedRequestIdentity(
                userId,
                USER_EMAIL,
                "Community Author",
                null,
                UserStatus.ACTIVE,
                UserRole.USER
        );
        return request -> {
            AuthenticatedRequestIdentityTestSupport.attach(request, identity);
            return request;
        };
    }

    private void insertUser(UUID id, String email, String displayName, String handle) {
        jdbcTemplate.update("""
                INSERT INTO identity_users (id, email, password_hash, display_name, public_handle, status, role, auth_provider, created_at, updated_at, avatar_customized)
                VALUES (?, ?, '$2a$10$hash', ?, ?, 'ACTIVE', 'USER', 'LOCAL', ?, ?, false)
                """, id.toString(), email, displayName, handle, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private void insertPost(UUID id, UUID authorId, String caption, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO community_posts (id, author_user_id, caption, image_media_asset_id, content_version, created_at, updated_at)
                VALUES (?, ?, ?, NULL, 0, ?, ?)
                """, id.toString(), authorId.toString(), caption, Timestamp.from(now), Timestamp.from(now));
    }

    private void insertForeignComment(UUID id, String targetType, UUID targetId, UUID authorId, UUID parentId, UUID rootId, String body, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, thread_root_comment_id, body, status, created_at, updated_at, deleted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, NULL)
                """, id.toString(), targetType, targetId.toString(), authorId.toString(),
                parentId != null ? parentId.toString() : null,
                rootId != null ? rootId.toString() : null,
                body, Timestamp.from(now), Timestamp.from(now));
    }

    // =========================================================================
    // 1. AUTHORITATIVE COUNT CONSISTENCY (N -> N+1 -> N+2) WITH REAL PERSISTENCE
    // =========================================================================

    @Test
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    @DisplayName("Proves N -> N+1 -> N+2 count progression and that rootThreadCount is NOT substituted for commentCount")
    void shouldProveAuthoritativeCountConsistencyWithRealPersistence() throws Exception {
        // Initial state: N = 0
        CommunityPostEngagementCountsDTO initialCounts = communityPostEngagementQueryPort
                .getEngagementCountsForPosts(List.of(postAId))
                .get(postAId);
        assertThat(initialCounts.commentCount()).isEqualTo(0L);

        CommentTargetMetrics initialMetrics = getCommentTargetMetricsUseCase
                .execute(CommentTarget.communityPost(postAId));
        assertThat(initialMetrics.commentCount()).isEqualTo(0L);
        assertThat(initialMetrics.threadCount()).isEqualTo(0L);

        // Step 1: Create root comment -> N + 1 = 1
        MvcResult rootResult = mockMvc.perform(post("/api/community/posts/" + postAId + "/comments")
                        .with(csrf())
                        .with(authenticatedIdentity(authorUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"First root comment on Post A\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.updatedCommentCount").value(1))
                .andReturn();

        JsonNode rootJson = objectMapper.readTree(rootResult.getResponse().getContentAsString());
        UUID rootCommentId = UUID.fromString(rootJson.get("commentId").asText());

        // Step 2: Create reply comment under root -> N + 2 = 2
        mockMvc.perform(post("/api/community/posts/" + postAId + "/comments/" + rootCommentId + "/replies")
                        .with(csrf())
                        .with(authenticatedIdentity(authorUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"First reply under root on Post A\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.updatedCommentCount").value(2));

        // Step 3: Query through CommunityPostEngagementQueryPort (same capability used by feeds)
        CommunityPostEngagementCountsDTO feedCounts = communityPostEngagementQueryPort
                .getEngagementCountsForPosts(List.of(postAId))
                .get(postAId);
        assertThat(feedCounts.commentCount()).isEqualTo(2L);

        // Step 4: Query through GetCommentTargetMetricsUseCase (layer-compliant count capability)
        CommentTargetMetrics finalMetrics = getCommentTargetMetricsUseCase
                .execute(CommentTarget.communityPost(postAId));

        assertThat(finalMetrics.commentCount()).isEqualTo(2L);
        assertThat(finalMetrics.threadCount()).isEqualTo(1L);

        // Explicitly assert that rootThreadCount (1) is NOT substituted for commentCount (2)
        assertThat(finalMetrics.threadCount()).isNotEqualTo(finalMetrics.commentCount());
    }

    // =========================================================================
    // 2. TARGET ISOLATION HARDENING WITH REAL PERSISTENCE
    // =========================================================================

    @Test
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    @DisplayName("Target isolation: Post A root comment cannot be fetched through Post B thread route")
    void postARootCannotBeFetchedUnderPostB() throws Exception {
        MvcResult rootResult = mockMvc.perform(post("/api/community/posts/" + postAId + "/comments")
                        .with(csrf())
                        .with(authenticatedIdentity(authorUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Root on Post A\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        UUID rootA = UUID.fromString(objectMapper.readTree(rootResult.getResponse().getContentAsString()).get("commentId").asText());

        // Fetching rootA under postB must return 404
        mockMvc.perform(get("/api/community/posts/" + postBId + "/comments/" + rootA + "/thread"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    @DisplayName("Target isolation: Reply parent from Post A cannot be used under Post B route")
    void replyParentFromOtherPostCannotBeUsed() throws Exception {
        MvcResult rootResult = mockMvc.perform(post("/api/community/posts/" + postAId + "/comments")
                        .with(csrf())
                        .with(authenticatedIdentity(authorUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Root on Post A\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        UUID rootA = UUID.fromString(objectMapper.readTree(rootResult.getResponse().getContentAsString()).get("commentId").asText());

        // Attempting to reply to rootA under postB must return 404
        mockMvc.perform(post("/api/community/posts/" + postBId + "/comments/" + rootA + "/replies")
                        .with(csrf())
                        .with(authenticatedIdentity(authorUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Cross-post reply\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Target isolation: Wiki root UUID cannot be fetched through Community thread route")
    void wikiRootCannotBeFetchedThroughCommunityRoute() throws Exception {
        UUID wikiRootId = UUID.randomUUID();
        insertForeignComment(wikiRootId, "WIKI_ARTICLE", wikiArticleId, authorUserId, null, null, "Wiki Root Comment", NOW);

        mockMvc.perform(get("/api/community/posts/" + postAId + "/comments/" + wikiRootId + "/thread"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    @DisplayName("Target isolation: Reply parent from Wiki cannot be used under Community route")
    void wikiRootCannotBeRepliedToUnderCommunityRoute() throws Exception {
        UUID wikiRootId = UUID.randomUUID();
        insertForeignComment(wikiRootId, "WIKI_ARTICLE", wikiArticleId, authorUserId, null, null, "Wiki Root Comment", NOW);

        mockMvc.perform(post("/api/community/posts/" + postAId + "/comments/" + wikiRootId + "/replies")
                        .with(csrf())
                        .with(authenticatedIdentity(authorUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Cross-context Wiki reply\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Target isolation: Novel root UUID cannot be fetched through Community thread route")
    void novelRootCannotBeFetchedThroughCommunityRoute() throws Exception {
        UUID novelRootId = UUID.randomUUID();
        insertForeignComment(novelRootId, "NOVEL_CHAPTER", novelChapterId, authorUserId, null, null, "Novel Root Comment", NOW);

        mockMvc.perform(get("/api/community/posts/" + postAId + "/comments/" + novelRootId + "/thread"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    @DisplayName("Target isolation: Reply parent from Novel cannot be used under Community route")
    void novelRootCannotBeRepliedToUnderCommunityRoute() throws Exception {
        UUID novelRootId = UUID.randomUUID();
        insertForeignComment(novelRootId, "NOVEL_CHAPTER", novelChapterId, authorUserId, null, null, "Novel Root Comment", NOW);

        mockMvc.perform(post("/api/community/posts/" + postAId + "/comments/" + novelRootId + "/replies")
                        .with(csrf())
                        .with(authenticatedIdentity(authorUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Cross-context Novel reply\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    @DisplayName("Target isolation: Missing Community post fails closed with 404 across all comment routes")
    void missingCommunityPostCannotBeAccessedOrRepliedTo() throws Exception {
        UUID missingPostId = UUID.randomUUID();
        UUID randomCommentId = UUID.randomUUID();

        // 1. GET feed for missing post -> 404
        mockMvc.perform(get("/api/community/posts/" + missingPostId + "/comments"))
                .andExpect(status().isNotFound());

        // 2. GET thread for missing post -> 404
        mockMvc.perform(get("/api/community/posts/" + missingPostId + "/comments/" + randomCommentId + "/thread"))
                .andExpect(status().isNotFound());

        // 3. POST root comment on missing post -> 404
        mockMvc.perform(post("/api/community/posts/" + missingPostId + "/comments")
                        .with(csrf())
                        .with(authenticatedIdentity(authorUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Root on non-existent post\"}"))
                .andExpect(status().isNotFound());

        // 4. POST reply on missing post -> 404
        mockMvc.perform(post("/api/community/posts/" + missingPostId + "/comments/" + randomCommentId + "/replies")
                        .with(csrf())
                        .with(authenticatedIdentity(authorUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Reply on non-existent post\"}"))
                .andExpect(status().isNotFound());
    }
}
