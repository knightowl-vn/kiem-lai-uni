package com.universe.interaction.infrastructure.persistence;

import com.universe.interaction.application.ports.CommentRevisionSlice;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentRevision;
import com.universe.interaction.domain.CommentTarget;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
        CommentRevisionPersistenceMapper.class
})
@DisplayName("CommentRevisionPersistenceAdapter Real MySQL Integration Tests")
class CommentRevisionPersistenceAdapterMySQLTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CommentPersistenceAdapter commentAdapter;

    @Autowired
    private CommentRevisionPersistenceAdapter revisionAdapter;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate txTemplate;

    @BeforeEach
    void setUp() {
        txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        cleanData();
    }

    @AfterEach
    void tearDown() {
        cleanData();
    }

    private void cleanData() {
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0;");
        jdbcTemplate.execute("DELETE FROM interaction_comment_revisions;");
        jdbcTemplate.execute("DELETE FROM interaction_comments;");
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1;");
    }

    private Comment createAndSaveParentComment(UUID commentId) {
        CommentTarget target = CommentTarget.novelChapter(UUID.randomUUID());
        UUID authorId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-09-18T10:00:00Z");
        Comment root = Comment.createRoot(commentId, target, authorId, "Initial comment body", createdAt);
        return commentAdapter.save(root);
    }

    // =========================================================================
    // 1. TRANSACTIONAL PROPAGATION (MANDATORY) CONTRACT
    // =========================================================================

    @Test
    @DisplayName("Should enforce Propagation.MANDATORY when calling save without active transaction")
    void shouldEnforceMandatoryTransactionOnSave() {
        CommentRevision revision = new CommentRevision(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "Body",
                Instant.parse("2026-09-18T10:00:00Z")
        );

        assertThatThrownBy(() -> revisionAdapter.save(revision))
                .isInstanceOf(IllegalTransactionStateException.class)
                .hasMessageContaining("No existing transaction found for transaction marked with propagation 'mandatory'");
    }

    @Test
    @DisplayName("Should enforce Propagation.MANDATORY when calling getNextRevisionNumber without active transaction")
    void shouldEnforceMandatoryTransactionOnGetNextRevisionNumber() {
        UUID commentId = UUID.randomUUID();

        assertThatThrownBy(() -> revisionAdapter.getNextRevisionNumber(commentId))
                .isInstanceOf(IllegalTransactionStateException.class)
                .hasMessageContaining("No existing transaction found for transaction marked with propagation 'mandatory'");
    }

    @Test
    @DisplayName("Should enforce Propagation.MANDATORY when calling deleteAllByCommentId without active transaction")
    void shouldEnforceMandatoryTransactionOnDeleteAllByCommentId() {
        UUID commentId = UUID.randomUUID();

        assertThatThrownBy(() -> revisionAdapter.deleteAllByCommentId(commentId))
                .isInstanceOf(IllegalTransactionStateException.class)
                .hasMessageContaining("No existing transaction found for transaction marked with propagation 'mandatory'");
    }

    // =========================================================================
    // 2. SAVE & GET NEXT REVISION NUMBER
    // =========================================================================

    @Test
    @DisplayName("getNextRevisionNumber should return 1 when no revisions exist, and N+1 after revisions are saved")
    void shouldComputeNextRevisionNumberCorrectly() {
        UUID commentId = UUID.randomUUID();
        createAndSaveParentComment(commentId);

        // Initially no revisions exist
        int nextRev0 = txTemplate.execute(status -> revisionAdapter.getNextRevisionNumber(commentId));
        assertThat(nextRev0).isEqualTo(1);

        // Save revision 1
        UUID rev1Id = UUID.randomUUID();
        Instant t1 = Instant.parse("2026-09-18T10:15:00Z");
        CommentRevision rev1 = new CommentRevision(rev1Id, commentId, 1, "Body v1", t1);
        txTemplate.execute(status -> revisionAdapter.save(rev1));

        int nextRev1 = txTemplate.execute(status -> revisionAdapter.getNextRevisionNumber(commentId));
        assertThat(nextRev1).isEqualTo(2);

        // Save revision 2
        UUID rev2Id = UUID.randomUUID();
        Instant t2 = Instant.parse("2026-09-18T10:30:00Z");
        CommentRevision rev2 = new CommentRevision(rev2Id, commentId, 2, "Body v2", t2);
        txTemplate.execute(status -> revisionAdapter.save(rev2));

        int nextRev2 = txTemplate.execute(status -> revisionAdapter.getNextRevisionNumber(commentId));
        assertThat(nextRev2).isEqualTo(3);
    }

    @Test
    @DisplayName("save should be insert-only, rejecting duplicate revision ID and preserving existing historical row")
    void shouldRejectOverwritingExistingRevisionIdAndPreserveOriginalHistoricalRow() {
        UUID commentId = UUID.randomUUID();
        createAndSaveParentComment(commentId);

        UUID revisionId = UUID.randomUUID();
        Instant t1 = Instant.parse("2026-09-18T10:00:00Z");
        CommentRevision initial = new CommentRevision(revisionId, commentId, 1, "Original body A", t1);

        txTemplate.execute(status -> revisionAdapter.save(initial));

        // Attempt to save another revision with SAME revisionId but changed state
        Instant t2 = Instant.parse("2026-09-18T10:05:00Z");
        CommentRevision duplicateIdRevision = new CommentRevision(revisionId, commentId, 2, "Mutated body B", t2);

        assertThatThrownBy(() -> txTemplate.execute(status -> revisionAdapter.save(duplicateIdRevision)))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Verify original row remains unchanged
        CommentRevisionSlice slice = revisionAdapter.findSliceByCommentId(commentId, 0, 10);
        assertThat(slice.items()).hasSize(1);
        assertThat(slice.items().get(0).getId()).isEqualTo(revisionId);
        assertThat(slice.items().get(0).getRevisionNumber()).isEqualTo(1);
        assertThat(slice.items().get(0).getBody()).isEqualTo("Original body A");
        assertThat(slice.items().get(0).getCreatedAt()).isEqualTo(t1);
    }

    // =========================================================================
    // 3. SLICE LOOKUP & ORDERING (revisionNumber DESC, id DESC)
    // =========================================================================

    @Test
    @DisplayName("findSliceByCommentId should return revisions ordered newest-first (revisionNumber DESC, id DESC)")
    void shouldReturnRevisionsOrderedNewestFirst() {
        UUID commentId = UUID.randomUUID();
        createAndSaveParentComment(commentId);

        UUID rev1Id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID rev2Id = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID rev3Id = UUID.fromString("33333333-3333-3333-3333-333333333333");

        txTemplate.execute(status -> {
            revisionAdapter.save(new CommentRevision(rev1Id, commentId, 1, "Body 1", Instant.parse("2026-09-18T10:10:00Z")));
            revisionAdapter.save(new CommentRevision(rev2Id, commentId, 2, "Body 2", Instant.parse("2026-09-18T10:20:00Z")));
            revisionAdapter.save(new CommentRevision(rev3Id, commentId, 3, "Body 3", Instant.parse("2026-09-18T10:30:00Z")));
            return null;
        });

        CommentRevisionSlice slice = revisionAdapter.findSliceByCommentId(commentId, 0, 10);

        assertThat(slice.items()).hasSize(3);
        assertThat(slice.items().get(0).getRevisionNumber()).isEqualTo(3);
        assertThat(slice.items().get(0).getId()).isEqualTo(rev3Id);
        assertThat(slice.items().get(1).getRevisionNumber()).isEqualTo(2);
        assertThat(slice.items().get(1).getId()).isEqualTo(rev2Id);
        assertThat(slice.items().get(2).getRevisionNumber()).isEqualTo(1);
        assertThat(slice.items().get(2).getId()).isEqualTo(rev1Id);
        assertThat(slice.hasNext()).isFalse();
    }

    @Test
    @DisplayName("findSliceByCommentId should paginate with zero-based page and hasNext semantics")
    void shouldPaginateRevisionsCorrectly() {
        UUID commentId = UUID.randomUUID();
        createAndSaveParentComment(commentId);

        txTemplate.execute(status -> {
            revisionAdapter.save(new CommentRevision(UUID.randomUUID(), commentId, 1, "Body 1", Instant.parse("2026-09-18T10:10:00Z")));
            revisionAdapter.save(new CommentRevision(UUID.randomUUID(), commentId, 2, "Body 2", Instant.parse("2026-09-18T10:20:00Z")));
            revisionAdapter.save(new CommentRevision(UUID.randomUUID(), commentId, 3, "Body 3", Instant.parse("2026-09-18T10:30:00Z")));
            return null;
        });

        // Page 0 (size 2): expect rev 3, rev 2; hasNext = true
        CommentRevisionSlice page0 = revisionAdapter.findSliceByCommentId(commentId, 0, 2);
        assertThat(page0.page()).isEqualTo(0);
        assertThat(page0.size()).isEqualTo(2);
        assertThat(page0.items()).hasSize(2);
        assertThat(page0.items().get(0).getRevisionNumber()).isEqualTo(3);
        assertThat(page0.items().get(1).getRevisionNumber()).isEqualTo(2);
        assertThat(page0.hasNext()).isTrue();

        // Page 1 (size 2): expect rev 1; hasNext = false
        CommentRevisionSlice page1 = revisionAdapter.findSliceByCommentId(commentId, 1, 2);
        assertThat(page1.page()).isEqualTo(1);
        assertThat(page1.size()).isEqualTo(2);
        assertThat(page1.items()).hasSize(1);
        assertThat(page1.items().get(0).getRevisionNumber()).isEqualTo(1);
        assertThat(page1.hasNext()).isFalse();

        // Page 2 (size 2): empty
        CommentRevisionSlice page2 = revisionAdapter.findSliceByCommentId(commentId, 2, 2);
        assertThat(page2.items()).isEmpty();
        assertThat(page2.hasNext()).isFalse();
    }

    @Test
    @DisplayName("findSliceByCommentId should return empty slice for comment with zero revisions")
    void shouldReturnEmptySliceForCommentWithNoRevisions() {
        UUID commentId = UUID.randomUUID();
        createAndSaveParentComment(commentId);

        CommentRevisionSlice slice = revisionAdapter.findSliceByCommentId(commentId, 0, 20);

        assertThat(slice.items()).isEmpty();
        assertThat(slice.hasNext()).isFalse();
        assertThat(slice.page()).isEqualTo(0);
        assertThat(slice.size()).isEqualTo(20);
    }

    // =========================================================================
    // 4. DATABASE CONSTRAINTS: UNIQUE(comment_id, revision_number)
    // =========================================================================

    @Test
    @DisplayName("Should enforce UNIQUE(comment_id, revision_number) on the database level")
    void shouldEnforceUniqueConstraintOnCommentIdAndRevisionNumber() {
        UUID commentId = UUID.randomUUID();
        createAndSaveParentComment(commentId);

        txTemplate.execute(status -> {
            revisionAdapter.save(new CommentRevision(UUID.randomUUID(), commentId, 1, "Body v1", Instant.parse("2026-09-18T10:10:00Z")));
            return null;
        });

        // Duplicate revisionNumber 1 for same commentId must fail
        CommentRevision duplicate = new CommentRevision(UUID.randomUUID(), commentId, 1, "Duplicate body", Instant.parse("2026-09-18T10:20:00Z"));
        assertThatThrownBy(() -> txTemplate.execute(status -> revisionAdapter.save(duplicate)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Should allow identical revision_number across different comments")
    void shouldAllowSameRevisionNumberAcrossDifferentComments() {
        UUID commentA = UUID.randomUUID();
        UUID commentB = UUID.randomUUID();
        createAndSaveParentComment(commentA);
        createAndSaveParentComment(commentB);

        txTemplate.execute(status -> {
            revisionAdapter.save(new CommentRevision(UUID.randomUUID(), commentA, 1, "Comment A Body", Instant.parse("2026-09-18T10:10:00Z")));
            revisionAdapter.save(new CommentRevision(UUID.randomUUID(), commentB, 1, "Comment B Body", Instant.parse("2026-09-18T10:10:00Z")));
            return null;
        });

        assertThat(revisionAdapter.findSliceByCommentId(commentA, 0, 10).items()).hasSize(1);
        assertThat(revisionAdapter.findSliceByCommentId(commentB, 0, 10).items()).hasSize(1);
    }

    // =========================================================================
    // 5. DELETE ALL BY COMMENT ID
    // =========================================================================

    @Test
    @DisplayName("deleteAllByCommentId should delete only revisions for target comment and leave other comments intact")
    void shouldDeleteOnlyTargetCommentRevisions() {
        UUID commentA = UUID.randomUUID();
        UUID commentB = UUID.randomUUID();
        createAndSaveParentComment(commentA);
        createAndSaveParentComment(commentB);

        txTemplate.execute(status -> {
            revisionAdapter.save(new CommentRevision(UUID.randomUUID(), commentA, 1, "A rev 1", Instant.parse("2026-09-18T10:10:00Z")));
            revisionAdapter.save(new CommentRevision(UUID.randomUUID(), commentA, 2, "A rev 2", Instant.parse("2026-09-18T10:20:00Z")));
            revisionAdapter.save(new CommentRevision(UUID.randomUUID(), commentB, 1, "B rev 1", Instant.parse("2026-09-18T10:15:00Z")));
            return null;
        });

        assertThat(revisionAdapter.findSliceByCommentId(commentA, 0, 10).items()).hasSize(2);
        assertThat(revisionAdapter.findSliceByCommentId(commentB, 0, 10).items()).hasSize(1);

        // Delete all revisions for Comment A
        txTemplate.execute(status -> {
            revisionAdapter.deleteAllByCommentId(commentA);
            return null;
        });

        assertThat(revisionAdapter.findSliceByCommentId(commentA, 0, 10).items()).isEmpty();
        assertThat(revisionAdapter.findSliceByCommentId(commentB, 0, 10).items()).hasSize(1);
    }

    // =========================================================================
    // 6. FOREIGN KEY & CASCADE INTEGRITY
    // =========================================================================

    @Test
    @DisplayName("Should reject saving revision for nonexistent comment ID with foreign key violation")
    void shouldRejectSavingRevisionForNonexistentComment() {
        UUID nonexistentCommentId = UUID.randomUUID();
        CommentRevision revision = new CommentRevision(
                UUID.randomUUID(),
                nonexistentCommentId,
                1,
                "Orphan body",
                Instant.parse("2026-09-18T10:10:00Z")
        );

        assertThatThrownBy(() -> txTemplate.execute(status -> revisionAdapter.save(revision)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Should physically cascade delete revisions when parent comment is physically deleted from database")
    void shouldCascadeDeleteRevisionsWhenParentCommentPhysicallyDeleted() {
        UUID commentId = UUID.randomUUID();
        createAndSaveParentComment(commentId);

        txTemplate.execute(status -> {
            revisionAdapter.save(new CommentRevision(UUID.randomUUID(), commentId, 1, "Rev 1", Instant.parse("2026-09-18T10:10:00Z")));
            revisionAdapter.save(new CommentRevision(UUID.randomUUID(), commentId, 2, "Rev 2", Instant.parse("2026-09-18T10:20:00Z")));
            return null;
        });

        assertThat(revisionAdapter.findSliceByCommentId(commentId, 0, 10).items()).hasSize(2);

        // Physically delete parent comment row
        jdbcTemplate.update("DELETE FROM interaction_comments WHERE id = ?", commentId.toString());

        // Revisions should be cascaded by DB foreign key ON DELETE CASCADE
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_comment_revisions WHERE comment_id = ?",
                Integer.class,
                commentId.toString()
        );
        assertThat(count).isZero();
    }
}
