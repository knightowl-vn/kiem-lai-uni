package com.universe.interaction.infrastructure.persistence;

import com.universe.interaction.contracts.dto.authored.AuthoredCommentItemDTO;
import com.universe.interaction.contracts.dto.authored.AuthoredCommentPageDTO;
import com.universe.interaction.domain.CommentTargetType;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
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
        UserAuthoredCommentsQueryPersistenceAdapter.class
})
@DisplayName("UserAuthoredCommentsQueryPersistenceAdapter Real MySQL Integration Tests")
class UserAuthoredCommentsQueryPersistenceAdapterMySQLTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserAuthoredCommentsQueryPersistenceAdapter adapter;

    private static final UUID AUTHOR_A = UUID.randomUUID();
    private static final UUID AUTHOR_B = UUID.randomUUID();
    private static final UUID TARGET_NOVEL = UUID.randomUUID();
    private static final UUID TARGET_WIKI = UUID.randomUUID();

    @BeforeEach
    @AfterEach
    void cleanData() {
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0;");
        jdbcTemplate.execute("DELETE FROM interaction_comments;");
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1;");
    }

    private void insertComment(
            UUID id,
            String targetType,
            UUID targetId,
            UUID authorUserId,
            UUID parentCommentId,
            UUID threadRootCommentId,
            String body,
            String status,
            Instant createdAt
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO interaction_comments (
                    id, target_type, target_id, author_user_id, parent_comment_id,
                    thread_root_comment_id, body, status, created_at, updated_at, deleted_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id.toString(),
                targetType,
                targetId.toString(),
                authorUserId.toString(),
                parentCommentId != null ? parentCommentId.toString() : null,
                threadRootCommentId != null ? threadRootCommentId.toString() : null,
                body,
                status,
                Timestamp.from(createdAt),
                Timestamp.from(createdAt),
                "DELETED".equals(status) ? Timestamp.from(createdAt) : null
        );
    }

    @Test
    @DisplayName("Author isolation: Author A receives only their active comments, Author B is excluded")
    void shouldIsolateAuthorComments() {
        UUID c1 = UUID.randomUUID();
        UUID c2 = UUID.randomUUID();
        Instant t1 = Instant.parse("2026-09-26T10:00:00Z");
        Instant t2 = Instant.parse("2026-09-26T10:05:00Z");

        insertComment(c1, "NOVEL_CHAPTER", TARGET_NOVEL, AUTHOR_A, null, null, "Comment by A", "ACTIVE", t1);
        insertComment(c2, "NOVEL_CHAPTER", TARGET_NOVEL, AUTHOR_B, null, null, "Comment by B", "ACTIVE", t2);

        AuthoredCommentPageDTO result = adapter.findAuthoredComments(
                AUTHOR_A,
                Set.of(CommentTargetType.NOVEL_CHAPTER, CommentTargetType.WIKI_ARTICLE),
                0,
                20
        );

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).commentId()).isEqualTo(c1);
        assertThat(result.items().get(0).body()).isEqualTo("Comment by A");
    }

    @Test
    @DisplayName("Status filter: DELETED comments are excluded at SQL level")
    void shouldExcludeDeletedComments() {
        UUID activeId = UUID.randomUUID();
        UUID deletedId = UUID.randomUUID();
        Instant t1 = Instant.parse("2026-09-26T10:00:00Z");
        Instant t2 = Instant.parse("2026-09-26T10:05:00Z");

        insertComment(activeId, "NOVEL_CHAPTER", TARGET_NOVEL, AUTHOR_A, null, null, "Active comment", "ACTIVE", t1);
        insertComment(deletedId, "NOVEL_CHAPTER", TARGET_NOVEL, AUTHOR_A, null, null, null, "DELETED", t2);

        AuthoredCommentPageDTO result = adapter.findAuthoredComments(
                AUTHOR_A,
                Set.of(CommentTargetType.NOVEL_CHAPTER, CommentTargetType.WIKI_ARTICLE),
                0,
                20
        );

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).commentId()).isEqualTo(activeId);
        assertThat(result.totalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("Context filter: NOVEL, WIKI, and ALL filters return expected target types")
    void shouldFilterByTargetType() {
        UUID novelComment = UUID.randomUUID();
        UUID wikiComment = UUID.randomUUID();
        Instant t1 = Instant.parse("2026-09-26T10:00:00Z");
        Instant t2 = Instant.parse("2026-09-26T10:05:00Z");

        insertComment(novelComment, "NOVEL_CHAPTER", TARGET_NOVEL, AUTHOR_A, null, null, "Novel comment", "ACTIVE", t1);
        insertComment(wikiComment, "WIKI_ARTICLE", TARGET_WIKI, AUTHOR_A, null, null, "Wiki comment", "ACTIVE", t2);

        // 1. ALL filter
        AuthoredCommentPageDTO allResult = adapter.findAuthoredComments(
                AUTHOR_A,
                Set.of(CommentTargetType.NOVEL_CHAPTER, CommentTargetType.WIKI_ARTICLE),
                0,
                20
        );
        assertThat(allResult.items()).hasSize(2);

        // 2. NOVEL filter
        AuthoredCommentPageDTO novelResult = adapter.findAuthoredComments(
                AUTHOR_A,
                Set.of(CommentTargetType.NOVEL_CHAPTER),
                0,
                20
        );
        assertThat(novelResult.items()).hasSize(1);
        assertThat(novelResult.items().get(0).commentId()).isEqualTo(novelComment);
        assertThat(novelResult.items().get(0).targetType()).isEqualTo(CommentTargetType.NOVEL_CHAPTER);

        // 3. WIKI filter
        AuthoredCommentPageDTO wikiResult = adapter.findAuthoredComments(
                AUTHOR_A,
                Set.of(CommentTargetType.WIKI_ARTICLE),
                0,
                20
        );
        assertThat(wikiResult.items()).hasSize(1);
        assertThat(wikiResult.items().get(0).commentId()).isEqualTo(wikiComment);
        assertThat(wikiResult.items().get(0).targetType()).isEqualTo(CommentTargetType.WIKI_ARTICLE);
    }

    @Test
    @DisplayName("Deterministic ordering and pagination: newest first (created_at DESC, id DESC)")
    void shouldOrderNewestFirstAndPaginate() {
        UUID c1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID c2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID c3 = UUID.fromString("00000000-0000-0000-0000-000000000003");

        Instant t1 = Instant.parse("2026-09-26T10:00:00Z");
        Instant t2 = Instant.parse("2026-09-26T10:10:00Z");
        Instant t3 = Instant.parse("2026-09-26T10:20:00Z");

        insertComment(c1, "NOVEL_CHAPTER", TARGET_NOVEL, AUTHOR_A, null, null, "Oldest", "ACTIVE", t1);
        insertComment(c2, "NOVEL_CHAPTER", TARGET_NOVEL, AUTHOR_A, null, null, "Middle", "ACTIVE", t2);
        insertComment(c3, "NOVEL_CHAPTER", TARGET_NOVEL, AUTHOR_A, null, null, "Newest", "ACTIVE", t3);

        // Page 0, size 2
        AuthoredCommentPageDTO page0 = adapter.findAuthoredComments(
                AUTHOR_A,
                Set.of(CommentTargetType.NOVEL_CHAPTER),
                0,
                2
        );
        assertThat(page0.totalElements()).isEqualTo(3);
        assertThat(page0.totalPages()).isEqualTo(2);
        assertThat(page0.items()).hasSize(2);
        assertThat(page0.items().get(0).commentId()).isEqualTo(c3); // Newest first
        assertThat(page0.items().get(1).commentId()).isEqualTo(c2);

        // Page 1, size 2
        AuthoredCommentPageDTO page1 = adapter.findAuthoredComments(
                AUTHOR_A,
                Set.of(CommentTargetType.NOVEL_CHAPTER),
                1,
                2
        );
        assertThat(page1.items()).hasSize(1);
        assertThat(page1.items().get(0).commentId()).isEqualTo(c1); // Oldest on page 1
    }
}
