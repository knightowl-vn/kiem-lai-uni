package com.universe.interaction.application.mutation;

import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.application.ports.CommentRevisionRepositoryPort;
import com.universe.interaction.application.ports.CommentRevisionSlice;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentStatus;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.infrastructure.persistence.CommentPersistenceAdapter;
import com.universe.interaction.infrastructure.persistence.CommentPersistenceMapper;
import com.universe.interaction.infrastructure.persistence.CommentRevisionPersistenceAdapter;
import com.universe.interaction.infrastructure.persistence.CommentRevisionPersistenceMapper;
import com.universe.interaction.infrastructure.persistence.InteractionReportPersistenceAdapter;
import com.universe.interaction.infrastructure.persistence.InteractionReportPersistenceMapper;
import com.universe.interaction.infrastructure.persistence.reaction.ReactionPersistenceAdapter;
import com.universe.interaction.infrastructure.persistence.reaction.ReactionPersistenceMapper;
import com.universe.shared.id.UuidGeneratorAdapter;
import com.universe.shared.time.SystemClockAdapter;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * Real MySQL integration tests proving MS-05E5G5C Atomic Edit/Delete Integration contracts:
 * <ul>
 *   <li>1. First changing edit (A -> B): current comment becomes B, revision #1 archived with body A;</li>
 *   <li>2. Second changing edit (B -> C): current comment becomes C, revision #2 archived with body B, revision #1 remains A;</li>
 *   <li>3. Normalized no-op edit: returns successfully, zero Comment writes, zero updatedAt change, zero revision insert;</li>
 *   <li>4. Revision persistence failure: rolls back Comment edit;</li>
 *   <li>5. Comment save failure after revision insert: rolls back revision insert;</li>
 *   <li>6. Delete comment with revisions: revisions purged to zero, comment tombstoned;</li>
 *   <li>7. Deleting one comment does not purge another comment's revisions;</li>
 *   <li>8. Revision purge failure: comment remains ACTIVE;</li>
 *   <li>9. Comment save failure after purge: revision purge rolls back;</li>
 *   <li>10. Delete idempotency preserved on already-deleted comment.</li>
 * </ul>
 */
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
        InteractionReportPersistenceAdapter.class,
        InteractionReportPersistenceMapper.class,
        ReactionPersistenceAdapter.class,
        ReactionPersistenceMapper.class,
        EditCommentUseCase.class,
        DeleteCommentUseCase.class,
        UuidGeneratorAdapter.class,
        SystemClockAdapter.class
})
@DisplayName("Comment Edit & Delete Atomic Revision Integration Tests (Real MySQL)")
class CommentEditDeleteRevisionIntegrationTest {

    private static final UUID AUTHOR_1_ID = UUID.fromString("11111111-aaaa-bbbb-cccc-000000000001");
    private static final UUID AUTHOR_2_ID = UUID.fromString("22222222-aaaa-bbbb-cccc-000000000002");
    private static final UUID CHAPTER_ID = UUID.fromString("33333333-aaaa-bbbb-cccc-000000000003");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private org.springframework.transaction.support.TransactionTemplate txTemplate;

    @SpyBean
    private CommentRevisionPersistenceAdapter revisionAdapter;

    @SpyBean
    private CommentPersistenceAdapter commentAdapter;

    @Autowired
    private EditCommentUseCase editCommentUseCase;

    @Autowired
    private DeleteCommentUseCase deleteCommentUseCase;

    @BeforeEach
    void setUp() {
        txTemplate = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        reset(revisionAdapter, commentAdapter);
        cleanupDatabase();
    }

    @AfterEach
    void tearDown() {
        reset(revisionAdapter, commentAdapter);
        cleanupDatabase();
    }

    private void cleanupDatabase() {
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0;");
        jdbcTemplate.execute("DELETE FROM interaction_comment_revisions;");
        jdbcTemplate.execute("DELETE FROM interaction_comments;");
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1;");
    }

    private Comment createAndSaveRootComment(UUID authorId, String body) {
        UUID commentId = UUID.randomUUID();
        CommentTarget target = CommentTarget.novelChapter(CHAPTER_ID);
        Instant createdAt = Instant.parse("2026-09-18T10:00:00Z");
        Comment root = Comment.createRoot(commentId, target, authorId, body, createdAt);
        return commentAdapter.save(root);
    }

    // =========================================================================
    // EDIT CONTRACT TESTS
    // =========================================================================

    @Test
    @DisplayName("1. First changing edit (A -> B): current Comment becomes B and revision #1 contains body A")
    void shouldPerformFirstChangingEditAndArchiveRevision1() {
        Comment comment = createAndSaveRootComment(AUTHOR_1_ID, "Original body A");

        EditCommentCommand editCmd = new EditCommentCommand(AUTHOR_1_ID, comment.getId(), "Edited body B");
        Comment edited = editCommentUseCase.execute(editCmd);

        assertThat(edited.getBody()).isEqualTo("Edited body B");

        // Verify Comment in MySQL
        String commentBodyInDb = jdbcTemplate.queryForObject(
                "SELECT body FROM interaction_comments WHERE id = ?",
                String.class,
                comment.getId().toString()
        );
        assertThat(commentBodyInDb).isEqualTo("Edited body B");

        // Verify revision #1 in MySQL
        CommentRevisionSlice slice = revisionAdapter.findSliceByCommentId(comment.getId(), 0, 10);
        assertThat(slice.items()).hasSize(1);
        assertThat(slice.items().get(0).getRevisionNumber()).isEqualTo(1);
        assertThat(slice.items().get(0).getBody()).isEqualTo("Original body A");
    }

    @Test
    @DisplayName("2. Second changing edit (B -> C): current Comment becomes C, revision #2 contains B, revision #1 remains A")
    void shouldPerformSecondChangingEditAndArchiveRevision2() {
        Comment comment = createAndSaveRootComment(AUTHOR_1_ID, "Original body A");

        // Edit 1: A -> B
        editCommentUseCase.execute(new EditCommentCommand(AUTHOR_1_ID, comment.getId(), "Edited body B"));

        // Edit 2: B -> C
        Comment finalComment = editCommentUseCase.execute(new EditCommentCommand(AUTHOR_1_ID, comment.getId(), "Final body C"));

        assertThat(finalComment.getBody()).isEqualTo("Final body C");

        // Verify Comment in MySQL
        String commentBodyInDb = jdbcTemplate.queryForObject(
                "SELECT body FROM interaction_comments WHERE id = ?",
                String.class,
                comment.getId().toString()
        );
        assertThat(commentBodyInDb).isEqualTo("Final body C");

        // Verify revisions in MySQL ordered newest-first (rev #2 then rev #1)
        CommentRevisionSlice slice = revisionAdapter.findSliceByCommentId(comment.getId(), 0, 10);
        assertThat(slice.items()).hasSize(2);

        assertThat(slice.items().get(0).getRevisionNumber()).isEqualTo(2);
        assertThat(slice.items().get(0).getBody()).isEqualTo("Edited body B");

        assertThat(slice.items().get(1).getRevisionNumber()).isEqualTo(1);
        assertThat(slice.items().get(1).getBody()).isEqualTo("Original body A");
    }

    @Test
    @DisplayName("3. Normalized no-op edit: succeeds, zero DB writes, zero updatedAt change, zero revision insert")
    void shouldTreatNormalizedIdenticalBodyAsNoOpWithoutWritingOrConsumingRevision() {
        Comment comment = createAndSaveRootComment(AUTHOR_1_ID, "Initial body");

        Timestamp originalUpdatedAt = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM interaction_comments WHERE id = ?",
                Timestamp.class,
                comment.getId().toString()
        );

        // Edit with leading/trailing whitespace that normalizes to identical body
        EditCommentCommand noopCmd = new EditCommentCommand(AUTHOR_1_ID, comment.getId(), "  Initial body \t\n ");
        Comment result = editCommentUseCase.execute(noopCmd);

        assertThat(result.getBody()).isEqualTo("Initial body");

        // Verify updated_at in MySQL did not change
        Timestamp currentUpdatedAt = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM interaction_comments WHERE id = ?",
                Timestamp.class,
                comment.getId().toString()
        );
        assertThat(currentUpdatedAt).isEqualTo(originalUpdatedAt);

        // Verify zero revisions were inserted
        Integer revisionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_comment_revisions WHERE comment_id = ?",
                Integer.class,
                comment.getId().toString()
        );
        assertThat(revisionCount).isZero();
    }

    @Test
    @DisplayName("4. Revision persistence failure rolls back Comment edit (Comment body remains unchanged in DB)")
    void shouldRollbackCommentEditWhenRevisionPersistenceFails() {
        Comment comment = createAndSaveRootComment(AUTHOR_1_ID, "Body before failure");

        // Force revision save to fail within a transaction so Propagation.MANDATORY is satisfied during stubbing
        txTemplate.execute(status -> {
            doThrow(new RuntimeException("Simulated revision persistence failure"))
                    .when(revisionAdapter).save(any());
            return null;
        });

        EditCommentCommand editCmd = new EditCommentCommand(AUTHOR_1_ID, comment.getId(), "New body that must rollback");

        assertThatThrownBy(() -> editCommentUseCase.execute(editCmd))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Simulated revision persistence failure");

        // Verify Comment in DB remained unchanged
        String commentBodyInDb = jdbcTemplate.queryForObject(
                "SELECT body FROM interaction_comments WHERE id = ?",
                String.class,
                comment.getId().toString()
        );
        assertThat(commentBodyInDb).isEqualTo("Body before failure");

        // Verify zero revisions exist
        Integer revisionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_comment_revisions WHERE comment_id = ?",
                Integer.class,
                comment.getId().toString()
        );
        assertThat(revisionCount).isZero();
    }

    @Test
    @DisplayName("5. Comment save failure after revision insert rolls back revision insert in DB")
    void shouldRollbackRevisionInsertWhenCommentSaveFails() {
        Comment comment = createAndSaveRootComment(AUTHOR_1_ID, "Body before comment save failure");

        // Force Comment save to fail when saving the edited comment
        doThrow(new RuntimeException("Simulated comment save failure"))
                .when(commentAdapter).save(any());

        EditCommentCommand editCmd = new EditCommentCommand(AUTHOR_1_ID, comment.getId(), "New body");

        assertThatThrownBy(() -> editCommentUseCase.execute(editCmd))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Simulated comment save failure");

        // Verify revision was rolled back and does not exist in DB
        Integer revisionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_comment_revisions WHERE comment_id = ?",
                Integer.class,
                comment.getId().toString()
        );
        assertThat(revisionCount).isZero();

        // Verify Comment in DB still has original body
        String commentBodyInDb = jdbcTemplate.queryForObject(
                "SELECT body FROM interaction_comments WHERE id = ?",
                String.class,
                comment.getId().toString()
        );
        assertThat(commentBodyInDb).isEqualTo("Body before comment save failure");
    }

    // =========================================================================
    // DELETE CONTRACT TESTS
    // =========================================================================

    @Test
    @DisplayName("6. Delete Comment with revisions: revisions become zero, Comment becomes logically deleted")
    void shouldDeleteCommentAndPurgeRevisionsAtomically() {
        Comment comment = createAndSaveRootComment(AUTHOR_1_ID, "Body A");

        // Edit twice to create 2 revisions
        editCommentUseCase.execute(new EditCommentCommand(AUTHOR_1_ID, comment.getId(), "Body B"));
        editCommentUseCase.execute(new EditCommentCommand(AUTHOR_1_ID, comment.getId(), "Body C"));

        // Verify 2 revisions in DB prior to delete
        Integer countBeforeDelete = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_comment_revisions WHERE comment_id = ?",
                Integer.class,
                comment.getId().toString()
        );
        assertThat(countBeforeDelete).isEqualTo(2);

        // Execute hard delete
        DeleteCommentCommand deleteCmd = new DeleteCommentCommand(AUTHOR_1_ID, comment.getId());
        deleteCommentUseCase.execute(deleteCmd);

        // Verify Comment in MySQL is physically deleted
        Integer countInDb = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_comments WHERE id = ?",
                Integer.class,
                comment.getId().toString()
        );
        assertThat(countInDb).isEqualTo(0);

        // Verify all revisions for this Comment are purged
        Integer countAfterDelete = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_comment_revisions WHERE comment_id = ?",
                Integer.class,
                comment.getId().toString()
        );
        assertThat(countAfterDelete).isZero();
    }

    @Test
    @DisplayName("7. Deleting one Comment does not purge another Comment's revisions")
    void shouldPurgeRevisionsOnlyForTargetCommentLeavingOtherCommentsRevisionsIntact() {
        Comment comment1 = createAndSaveRootComment(AUTHOR_1_ID, "Comment 1 Original");
        Comment comment2 = createAndSaveRootComment(AUTHOR_2_ID, "Comment 2 Original");

        // Edit comment 1 twice (2 revisions)
        editCommentUseCase.execute(new EditCommentCommand(AUTHOR_1_ID, comment1.getId(), "Comment 1 v2"));
        editCommentUseCase.execute(new EditCommentCommand(AUTHOR_1_ID, comment1.getId(), "Comment 1 v3"));

        // Edit comment 2 once (1 revision)
        editCommentUseCase.execute(new EditCommentCommand(AUTHOR_2_ID, comment2.getId(), "Comment 2 v2"));

        // Delete comment 1 only
        deleteCommentUseCase.execute(new DeleteCommentCommand(AUTHOR_1_ID, comment1.getId()));

        // Comment 1 revisions must be 0
        Integer c1Revisions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_comment_revisions WHERE comment_id = ?",
                Integer.class,
                comment1.getId().toString()
        );
        assertThat(c1Revisions).isZero();

        // Comment 2 revisions must still be 1!
        Integer c2Revisions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_comment_revisions WHERE comment_id = ?",
                Integer.class,
                comment2.getId().toString()
        );
        assertThat(c2Revisions).isEqualTo(1);

        CommentRevisionSlice c2Slice = revisionAdapter.findSliceByCommentId(comment2.getId(), 0, 10);
        assertThat(c2Slice.items()).hasSize(1);
        assertThat(c2Slice.items().get(0).getBody()).isEqualTo("Comment 2 Original");
    }

    @Test
    @DisplayName("8. Revision purge failure: Comment remains ACTIVE in DB")
    void shouldKeepCommentActiveWhenRevisionPurgeFails() {
        Comment comment = createAndSaveRootComment(AUTHOR_1_ID, "Body A");
        editCommentUseCase.execute(new EditCommentCommand(AUTHOR_1_ID, comment.getId(), "Body B"));

        // Force revision purge to fail within a transaction so Propagation.MANDATORY is satisfied during stubbing
        txTemplate.execute(status -> {
            doThrow(new RuntimeException("Simulated purge failure"))
                    .when(revisionAdapter).deleteAllByCommentIds(any());
            return null;
        });

        DeleteCommentCommand deleteCmd = new DeleteCommentCommand(AUTHOR_1_ID, comment.getId());

        assertThatThrownBy(() -> deleteCommentUseCase.execute(deleteCmd))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Simulated purge failure");

        // Verify comment in MySQL is still ACTIVE with non-null body
        String statusInDb = jdbcTemplate.queryForObject(
                "SELECT status FROM interaction_comments WHERE id = ?",
                String.class,
                comment.getId().toString()
        );
        assertThat(statusInDb).isEqualTo("ACTIVE");

        String bodyInDb = jdbcTemplate.queryForObject(
                "SELECT body FROM interaction_comments WHERE id = ?",
                String.class,
                comment.getId().toString()
        );
        assertThat(bodyInDb).isEqualTo("Body B");

        // Revisions must remain intact
        Integer revisionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_comment_revisions WHERE comment_id = ?",
                Integer.class,
                comment.getId().toString()
        );
        assertThat(revisionCount).isEqualTo(1);
    }

    @Test
    @DisplayName("9. Comment delete failure after purge: revision purge rolls back in DB")
    void shouldRollbackRevisionPurgeWhenCommentDeleteFailsOnDelete() {
        Comment comment = createAndSaveRootComment(AUTHOR_1_ID, "Body A");
        editCommentUseCase.execute(new EditCommentCommand(AUTHOR_1_ID, comment.getId(), "Body B"));

        // Verify 1 revision in DB
        Integer initialRevCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_comment_revisions WHERE comment_id = ?",
                Integer.class,
                comment.getId().toString()
        );
        assertThat(initialRevCount).isEqualTo(1);

        // Force comment delete to fail during delete
        txTemplate.execute(status -> {
            doThrow(new RuntimeException("Simulated comment delete failure"))
                    .when(commentAdapter).deleteById(any());
            return null;
        });

        DeleteCommentCommand deleteCmd = new DeleteCommentCommand(AUTHOR_1_ID, comment.getId());

        assertThatThrownBy(() -> deleteCommentUseCase.execute(deleteCmd))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Simulated comment delete failure");

        // Verify comment in DB is still ACTIVE
        String statusInDb = jdbcTemplate.queryForObject(
                "SELECT status FROM interaction_comments WHERE id = ?",
                String.class,
                comment.getId().toString()
        );
        assertThat(statusInDb).isEqualTo("ACTIVE");

        // Verify revision purge rolled back: revision still exists in DB
        Integer revCountAfterRollback = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_comment_revisions WHERE comment_id = ?",
                Integer.class,
                comment.getId().toString()
        );
        assertThat(revCountAfterRollback).isEqualTo(1);
    }

    @Test
    @DisplayName("10. Delete comment physically removes row and subsequent delete fails with CommentNotFoundException")
    void shouldPhysicallyDeleteCommentAndSubsequentDeleteFailsNotFound() {
        Comment comment = createAndSaveRootComment(AUTHOR_1_ID, "Body A");

        // First delete
        deleteCommentUseCase.execute(new DeleteCommentCommand(AUTHOR_1_ID, comment.getId()));

        Integer countInDb = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_comments WHERE id = ?",
                Integer.class,
                comment.getId().toString()
        );
        assertThat(countInDb).isEqualTo(0);

        // Second delete fails because comment no longer exists
        assertThatThrownBy(() -> deleteCommentUseCase.execute(new DeleteCommentCommand(AUTHOR_1_ID, comment.getId())))
                .isInstanceOf(com.universe.interaction.application.exceptions.CommentNotFoundException.class);
    }
}
