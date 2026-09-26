package com.universe.interaction.infrastructure.persistence;

import com.universe.interaction.application.exceptions.CommentHasRepliesException;
import com.universe.interaction.application.mutation.DeleteCommentCommand;
import com.universe.interaction.application.mutation.DeleteCommentUseCase;
import com.universe.interaction.application.mutation.ResolveCommentReportCommand;
import com.universe.interaction.application.mutation.ResolveCommentReportUseCase;
import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.application.ports.CommentRevisionRepositoryPort;
import com.universe.interaction.application.ports.InteractionReportRepositoryPort;
import com.universe.interaction.application.ports.ReactionRepositoryPort;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.domain.reaction.Reaction;
import com.universe.interaction.domain.reaction.ReactionTarget;
import com.universe.interaction.domain.reaction.ReactionType;
import com.universe.interaction.domain.report.InteractionReport;
import com.universe.interaction.domain.report.ReportModerationAction;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.infrastructure.persistence.reaction.ReactionPersistenceAdapter;
import com.universe.interaction.infrastructure.persistence.reaction.ReactionPersistenceMapper;
import com.universe.shared.time.ClockPort;
import com.universe.test.TestDatabaseSupport;
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

import javax.sql.DataSource;
import java.time.Instant;
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
        CommentPersistenceAdapter.class,
        CommentPersistenceMapper.class,
        CommentRevisionPersistenceAdapter.class,
        CommentRevisionPersistenceMapper.class,
        ReactionPersistenceAdapter.class,
        ReactionPersistenceMapper.class,
        InteractionReportPersistenceAdapter.class,
        InteractionReportPersistenceMapper.class,
        DeleteCommentUseCase.class,
        ResolveCommentReportUseCase.class,
        CommentHardDeleteMySQLTest.TestClockConfig.class
})
@DisplayName("Comment Hard Delete Real MySQL Integration Tests")
class CommentHardDeleteMySQLTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    static class TestClockConfig {
        @org.springframework.context.annotation.Bean
        public ClockPort clockPort() {
            return () -> Instant.parse("2026-09-26T12:00:00Z");
        }
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private CommentRepositoryPort commentRepositoryPort;

    @Autowired
    private CommentRevisionRepositoryPort commentRevisionRepositoryPort;

    @Autowired
    private ReactionRepositoryPort reactionRepositoryPort;

    @Autowired
    private InteractionReportRepositoryPort reportRepositoryPort;

    @Autowired
    private DeleteCommentUseCase deleteCommentUseCase;

    @Autowired
    private ResolveCommentReportUseCase resolveCommentReportUseCase;

    private JdbcTemplate jdbc;

    private static final UUID AUTHOR_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TARGET_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final CommentTarget TARGET = CommentTarget.novelChapter(TARGET_ID);
    private static final Instant T0 = Instant.parse("2026-09-26T10:00:00Z");

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0;");
        jdbc.execute("TRUNCATE TABLE interaction_reactions;");
        jdbc.execute("TRUNCATE TABLE interaction_comment_revisions;");
        jdbc.execute("TRUNCATE TABLE interaction_reports;");
        jdbc.execute("TRUNCATE TABLE interaction_comments;");
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1;");
    }

    @Test
    @DisplayName("1. Physical deletion of a leaf root comment removes root row, revisions, and reactions from MySQL")
    void shouldPhysicallyDeleteLeafRootCommentAndAssociatedRows() {
        UUID leafRootId = UUID.randomUUID();
        Comment root = Comment.createRoot(leafRootId, TARGET, AUTHOR_ID, "Leaf root comment body", T0);
        commentRepositoryPort.save(root);

        // Save a revision for root
        UUID rev1Id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO interaction_comment_revisions (id, comment_id, revision_number, body, created_at) VALUES (?, ?, ?, ?, ?)",
                rev1Id.toString(), leafRootId.toString(), 1, "Root original revision", java.sql.Timestamp.from(T0)
        );

        // Save reaction on root
        Reaction rx1 = Reaction.create(UUID.randomUUID(), USER_B, ReactionTarget.comment(leafRootId), ReactionType.FIRE, T0);
        reactionRepositoryPort.save(rx1);

        // Verify rows exist before deletion
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interaction_comments", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interaction_comment_revisions", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interaction_reactions WHERE target_type = 'COMMENT'", Integer.class)).isEqualTo(1);

        // Execute hard delete of leaf root comment
        deleteCommentUseCase.execute(new DeleteCommentCommand(AUTHOR_ID, leafRootId));

        // Verify all rows physically removed
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interaction_comments WHERE id = ?", Integer.class, leafRootId.toString())).isEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interaction_comments", Integer.class)).isEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interaction_comment_revisions WHERE comment_id = ?", Integer.class, leafRootId.toString())).isEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interaction_reactions WHERE target_type = 'COMMENT'", Integer.class)).isEqualTo(0);
    }

    @Test
    @DisplayName("2. Author root with another user's reply is rejected and removes NOTHING from MySQL")
    void shouldRejectDeleteWhenRootCommentHasRepliesAndRemoveNothing() {
        UUID rootId = UUID.randomUUID();
        Comment root = Comment.createRoot(rootId, TARGET, AUTHOR_ID, "Root comment body", T0);
        commentRepositoryPort.save(root);

        // Revision on root
        UUID rev1Id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO interaction_comment_revisions (id, comment_id, revision_number, body, created_at) VALUES (?, ?, ?, ?, ?)",
                rev1Id.toString(), rootId.toString(), 1, "Root original revision", java.sql.Timestamp.from(T0)
        );

        // User B reply
        UUID replyId = UUID.randomUUID();
        Comment reply = Comment.createReply(replyId, root, USER_B, "Reply to root by User B", T0.plusSeconds(60));
        commentRepositoryPort.save(reply);

        // Reaction on reply
        Reaction rxReply = Reaction.create(UUID.randomUUID(), AUTHOR_ID, ReactionTarget.comment(replyId), ReactionType.LOVE, T0);
        reactionRepositoryPort.save(rxReply);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interaction_comments", Integer.class)).isEqualTo(2);

        // Author attempts to delete root comment with replies -> MUST throw CommentHasRepliesException
        assertThatThrownBy(() -> deleteCommentUseCase.execute(new DeleteCommentCommand(AUTHOR_ID, rootId)))
                .isInstanceOf(CommentHasRepliesException.class)
                .hasMessage("Không thể xóa bình luận đang có phản hồi.");

        // PROOF: Zero rows removed from MySQL!
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interaction_comments WHERE id = ?", Integer.class, rootId.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interaction_comments WHERE id = ?", Integer.class, replyId.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interaction_comments", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interaction_comment_revisions WHERE comment_id = ?", Integer.class, rootId.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interaction_reactions WHERE target_type = 'COMMENT'", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("3. Author reply with child reply is rejected and removes NOTHING from MySQL")
    void shouldRejectDeleteWhenReplyHasDescendantsAndRemoveNothing() {
        UUID rootId = UUID.randomUUID();
        Comment root = Comment.createRoot(rootId, TARGET, USER_B, "Root comment body", T0);
        commentRepositoryPort.save(root);

        UUID reply1Id = UUID.randomUUID();
        Comment reply1 = Comment.createReply(reply1Id, root, AUTHOR_ID, "Reply 1 by Author", T0.plusSeconds(30));
        commentRepositoryPort.save(reply1);

        UUID reply2Id = UUID.randomUUID();
        Comment reply2 = Comment.createReply(reply2Id, reply1, USER_B, "Reply 2 child of Reply 1", T0.plusSeconds(60));
        commentRepositoryPort.save(reply2);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interaction_comments", Integer.class)).isEqualTo(3);

        // Author attempts to delete reply1 having child reply2 -> MUST throw CommentHasRepliesException
        assertThatThrownBy(() -> deleteCommentUseCase.execute(new DeleteCommentCommand(AUTHOR_ID, reply1Id)))
                .isInstanceOf(CommentHasRepliesException.class)
                .hasMessage("Không thể xóa bình luận đang có phản hồi.");

        // PROOF: All 3 comments remain intact in MySQL
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interaction_comments WHERE id = ?", Integer.class, rootId.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interaction_comments WHERE id = ?", Integer.class, reply1Id.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interaction_comments WHERE id = ?", Integer.class, reply2Id.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interaction_comments", Integer.class)).isEqualTo(3);
    }

    @Test
    @DisplayName("4. Moderation DELETE_COMMENT physically removes comment subtree while preserving report audit log")
    void shouldPhysicallyRemoveCommentOnModeratorResolutionWhileKeepingReport() {
        UUID rootId = UUID.randomUUID();
        Comment root = Comment.createRoot(rootId, TARGET, AUTHOR_ID, "Reported spam comment", T0);
        commentRepositoryPort.save(root);

        UUID replyId = UUID.randomUUID();
        Comment reply = Comment.createReply(replyId, root, USER_B, "Reply under reported root", T0.plusSeconds(30));
        commentRepositoryPort.save(reply);

        UUID reportId = UUID.randomUUID();
        InteractionReport report = InteractionReport.createPending(
                reportId,
                rootId,
                USER_B,
                ReportReason.SPAM,
                "Looks like spam",
                "Reported snapshot body",
                T0
        );
        reportRepositoryPort.save(report);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interaction_reports", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interaction_comments", Integer.class)).isEqualTo(2);

        // Moderator resolves report with DELETE_COMMENT
        UUID moderatorId = UUID.randomUUID();
        resolveCommentReportUseCase.execute(new ResolveCommentReportCommand(reportId, moderatorId, ReportModerationAction.DELETE_COMMENT));

        // Both comment rows (root + reply subtree) are physically deleted from MySQL
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interaction_comments WHERE id = ?", Integer.class, rootId.toString())).isEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interaction_comments WHERE id = ?", Integer.class, replyId.toString())).isEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interaction_comments", Integer.class)).isEqualTo(0);

        // Report audit row is PRESERVED with RESOLVED_ACTION_TAKEN
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM interaction_reports WHERE id = ?", Integer.class, reportId.toString())).isEqualTo(1);
        String reportStatus = jdbc.queryForObject("SELECT status FROM interaction_reports WHERE id = ?", String.class, reportId.toString());
        assertThat(reportStatus).isEqualTo("RESOLVED_ACTION_TAKEN");
    }
}
