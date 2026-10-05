package com.universe.community.entry.admin;

import com.universe.community.application.command.ApproveCommunityPostCommand;
import com.universe.community.application.command.HideCommunityPostCommand;
import com.universe.community.application.command.EditCommunityPostCaptionCommand;
import com.universe.community.application.command.RejectCommunityPostCommand;
import com.universe.community.application.command.RestoreCommunityPostCommand;
import com.universe.community.application.port.out.CommunityPostModerationEventRepositoryPort;
import com.universe.community.application.port.out.CommunityPostRepositoryPort;
import com.universe.community.application.port.out.CommunityPostRepositoryPort.CommunityPostPage;
import com.universe.community.application.usecase.ApproveCommunityPostUseCase;
import com.universe.community.application.usecase.DeleteCommunityPostUseCase;
import com.universe.community.application.usecase.EditCommunityPostCaptionUseCase;
import com.universe.community.application.usecase.HideCommunityPostUseCase;
import com.universe.community.application.usecase.RejectCommunityPostUseCase;
import com.universe.community.application.usecase.RestoreCommunityPostUseCase;
import com.universe.interaction.application.mutation.ResolveInteractionReportUseCase;
import com.universe.interaction.application.query.GetInteractionReportDetailUseCase;
import com.universe.community.domain.CommunityPost;
import com.universe.community.domain.CommunityPostStatus;
import com.universe.community.domain.exception.CommunityPostHiddenDeleteForbiddenException;
import com.universe.community.domain.exception.CommunityPostPendingReportConflictException;
import com.universe.community.domain.moderation.CommunityPostModerationAction;
import com.universe.community.domain.moderation.CommunityPostModerationEvent;
import com.universe.community.infrastructure.interaction.InteractionCommunityPostCleanupAdapter;
import com.universe.community.infrastructure.interaction.InteractionCommunityPostReportQueryAdapter;
import com.universe.community.infrastructure.persistence.CommunityPostModerationEventPersistenceAdapter;
import com.universe.community.infrastructure.persistence.CommunityPostModerationEventPersistenceMapper;
import com.universe.community.infrastructure.persistence.CommunityPostPersistenceAdapter;
import com.universe.community.infrastructure.persistence.CommunityPostPersistenceMapper;
import com.universe.community.infrastructure.persistence.CommunityPostRevisionPersistenceAdapter;
import com.universe.community.infrastructure.persistence.CommunityPostRevisionPersistenceMapper;
import com.universe.community.infrastructure.persistence.CommunitySettingsPersistenceAdapter;
import com.universe.interaction.application.mutation.CleanupCommunityPostInteractionsUseCase;
import com.universe.interaction.application.ports.InteractionReportRepositoryPort;
import com.universe.interaction.application.ports.InteractionReportRepositoryPort.InteractionReportPage;
import com.universe.interaction.domain.report.InteractionReport;
import com.universe.interaction.domain.report.ReportModerationAction;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;
import com.universe.interaction.domain.report.ReportTargetType;
import com.universe.interaction.infrastructure.persistence.CommentPersistenceAdapter;
import com.universe.interaction.infrastructure.persistence.CommentPersistenceMapper;
import com.universe.interaction.infrastructure.persistence.CommentRevisionPersistenceAdapter;
import com.universe.interaction.infrastructure.persistence.CommentRevisionPersistenceMapper;
import com.universe.interaction.infrastructure.persistence.InteractionReportPersistenceAdapter;
import com.universe.interaction.infrastructure.persistence.InteractionReportPersistenceMapper;
import com.universe.interaction.infrastructure.persistence.reaction.ReactionPersistenceAdapter;
import com.universe.interaction.infrastructure.persistence.reaction.ReactionPersistenceMapper;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.shared.id.UuidGeneratorAdapter;
import com.universe.shared.time.ClockPort;
import com.universe.shared.time.SystemClockAdapter;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@EntityScan(basePackages = {
        "com.universe.community.infrastructure.persistence",
        "com.universe.interaction.infrastructure.persistence"
})
@EnableJpaRepositories(basePackages = {
        "com.universe.community.infrastructure.persistence",
        "com.universe.interaction.infrastructure.persistence"
})
@Import({
        CommunityPostPersistenceAdapter.class,
        CommunityPostRevisionPersistenceAdapter.class,
        CommunityPostModerationEventPersistenceAdapter.class,
        CommunityPostPersistenceMapper.class,
        CommunityPostRevisionPersistenceMapper.class,
        CommunityPostModerationEventPersistenceMapper.class,
        InteractionReportPersistenceAdapter.class,
        InteractionReportPersistenceMapper.class,
        CommentPersistenceAdapter.class,
        CommentPersistenceMapper.class,
        CommentRevisionPersistenceAdapter.class,
        CommentRevisionPersistenceMapper.class,
        ReactionPersistenceAdapter.class,
        ReactionPersistenceMapper.class,
        InteractionCommunityPostCleanupAdapter.class,
        InteractionCommunityPostReportQueryAdapter.class,
        CleanupCommunityPostInteractionsUseCase.class,
        GetInteractionReportDetailUseCase.class,
        ResolveInteractionReportUseCase.class,
        ResolveCommunityPostReportUseCase.class,
        ApproveCommunityPostUseCase.class,
        RejectCommunityPostUseCase.class,
        HideCommunityPostUseCase.class,
        RestoreCommunityPostUseCase.class,
        DeleteCommunityPostUseCase.class,
        EditCommunityPostCaptionUseCase.class,
        CommunitySettingsPersistenceAdapter.class,
        UuidGeneratorAdapter.class,
        SystemClockAdapter.class,
        AdminCommunityPostModerationMySQLIntegrationTest.TestConfig.class
})
@DisplayName("Admin Community Post Moderation Real MySQL Integration Tests (MS-07B8.5.3)")
class AdminCommunityPostModerationMySQLIntegrationTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.resetTestDatabase("kiemlai_test");
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        MediaContract mediaContract() {
            return Mockito.mock(MediaContract.class);
        }
    }

    @Autowired
    private CommunityPostRepositoryPort postRepositoryPort;

    @Autowired
    private CommunityPostModerationEventRepositoryPort moderationEventRepositoryPort;

    @Autowired
    private InteractionReportRepositoryPort reportRepositoryPort;

    @Autowired
    private ResolveCommunityPostReportUseCase resolveReportUseCase;

    @Autowired
    private ApproveCommunityPostUseCase approveUseCase;

    @Autowired
    private RejectCommunityPostUseCase rejectUseCase;

    @Autowired
    private HideCommunityPostUseCase hideUseCase;

    @Autowired
    private RestoreCommunityPostUseCase restoreUseCase;

    @Autowired
    private DeleteCommunityPostUseCase deleteUseCase;

    @Autowired
    private EditCommunityPostCaptionUseCase editUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate txTemplate;

    @BeforeEach
    void setUp() {
        txTemplate = new TransactionTemplate(transactionManager);
        cleanDatabase();
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    private void cleanDatabase() {
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0;");
        jdbcTemplate.execute("TRUNCATE TABLE community_post_moderation_events;");
        jdbcTemplate.execute("TRUNCATE TABLE community_post_revisions;");
        jdbcTemplate.execute("TRUNCATE TABLE community_posts;");
        jdbcTemplate.execute("TRUNCATE TABLE interaction_reports;");
        jdbcTemplate.execute("TRUNCATE TABLE interaction_comments;");
        jdbcTemplate.execute("TRUNCATE TABLE interaction_comment_revisions;");
        jdbcTemplate.execute("TRUNCATE TABLE interaction_reactions;");
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1;");
    }

    @Test
    @DisplayName("V82 check constraint allows CONTENT_HIDDEN and NO_ACTION, but rejects invalid moderation actions")
    void shouldValidateV82CheckConstraint() {
        UUID reportId1 = UUID.randomUUID();
        UUID reportId2 = UUID.randomUUID();
        UUID reportId3 = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();
        UUID resolverId = UUID.randomUUID();

        // 1. CONTENT_HIDDEN with RESOLVED_ACTION_TAKEN -> valid
        jdbcTemplate.update("""
                INSERT INTO interaction_reports (
                    id, target_type, target_id, reporter_user_id, reason, description,
                    content_snapshot, status, moderation_action, resolved_by_user_id,
                    resolved_at, created_at
                ) VALUES (
                    ?, 'COMMUNITY_POST', ?, ?, 'SPAM', 'Spam details',
                    'Post snapshot', 'RESOLVED_ACTION_TAKEN', 'CONTENT_HIDDEN', ?,
                    NOW(), NOW()
                )
                """, reportId1.toString(), targetId.toString(), reporterId.toString(), resolverId.toString());

        // 2. NO_ACTION with RESOLVED_NO_ACTION -> valid
        jdbcTemplate.update("""
                INSERT INTO interaction_reports (
                    id, target_type, target_id, reporter_user_id, reason, description,
                    content_snapshot, status, moderation_action, resolved_by_user_id,
                    resolved_at, created_at
                ) VALUES (
                    ?, 'COMMUNITY_POST', ?, ?, 'SPAM', 'Spam details',
                    'Post snapshot', 'RESOLVED_NO_ACTION', 'NO_ACTION', ?,
                    NOW(), NOW()
                )
                """, reportId2.toString(), targetId.toString(), reporterId.toString(), resolverId.toString());

        // 3. Invalid moderation_action 'APPROVE' with RESOLVED_ACTION_TAKEN -> violates V82 constraint
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO interaction_reports (
                    id, target_type, target_id, reporter_user_id, reason, description,
                    content_snapshot, status, moderation_action, resolved_by_user_id,
                    resolved_at, created_at
                ) VALUES (
                    ?, 'COMMUNITY_POST', ?, ?, 'SPAM', 'Spam details',
                    'Post snapshot', 'RESOLVED_ACTION_TAKEN', 'APPROVE', ?,
                    NOW(), NOW()
                )
                """, reportId3.toString(), targetId.toString(), reporterId.toString(), resolverId.toString()))
                .hasMessageContaining("chk_interaction_reports_moderation_action");
    }

    @Test
    @DisplayName("Resolves community post report with CONTENT_HIDDEN: hides post, appends event, resolves report, and activates delete barrier")
    void shouldResolveReportWithContentHidden() {
        UUID authorId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();
        UUID moderatorId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        // Seed PUBLISHED post
        CommunityPost post = CommunityPost.rehydrate(
                postId, authorId, "A post with offensive content", null,
                CommunityPostStatus.PUBLISHED, 1, now.minusSeconds(100), now.minusSeconds(100),
                now.minusSeconds(100), null
        );
        postRepositoryPort.save(post);

        // Seed PENDING report
        InteractionReport report = InteractionReport.createPending(
                UUID.randomUUID(),
                ReportTargetType.COMMUNITY_POST,
                postId,
                reporterId,
                ReportReason.HARASSMENT,
                "Harassing content",
                "A post with offensive content",
                null,
                now.minusSeconds(50)
        );
        reportRepositoryPort.save(report);

        // Execute report resolution with CONTENT_HIDDEN
        ResolveCommunityPostReportCommand command = new ResolveCommunityPostReportCommand(
                report.getId(),
                moderatorId,
                ReportModerationAction.CONTENT_HIDDEN,
                "Confirmed harassment violation"
        );
        resolveReportUseCase.execute(command);

        // 1. Verify post status transitioned to HIDDEN in MySQL
        CommunityPost updatedPost = postRepositoryPort.findById(postId).orElseThrow();
        assertThat(updatedPost.getStatus()).isEqualTo(CommunityPostStatus.HIDDEN);

        // 2. Verify moderation event persisted in MySQL
        List<CommunityPostModerationEvent> events = moderationEventRepositoryPort.findByPostIdOrderByCreatedAtAsc(postId);
        assertThat(events).hasSize(1);
        CommunityPostModerationEvent event = events.get(0);
        assertThat(event.action()).isEqualTo(CommunityPostModerationAction.HIDE);
        assertThat(event.fromStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
        assertThat(event.toStatus()).isEqualTo(CommunityPostStatus.HIDDEN);
        assertThat(event.moderatorUserId()).isEqualTo(moderatorId);
        assertThat(event.reason()).isEqualTo("Confirmed harassment violation");

        // 3. Verify report resolved in MySQL
        InteractionReport updatedReport = reportRepositoryPort.findById(report.getId()).orElseThrow();
        assertThat(updatedReport.getStatus()).isEqualTo(ReportStatus.RESOLVED_ACTION_TAKEN);
        assertThat(updatedReport.getModerationAction()).isEqualTo(ReportModerationAction.CONTENT_HIDDEN);
        assertThat(updatedReport.getResolvedByUserId()).isEqualTo(moderatorId);
        assertThat(updatedReport.getResolvedAt()).isNotNull();

        // 4. Verify post deletion is forbidden by moderation barrier
        assertThatThrownBy(() -> deleteUseCase.execute(authorId, postId))
                .isInstanceOf(CommunityPostHiddenDeleteForbiddenException.class);
    }

    @Test
    @DisplayName("Resolves community post report with NO_ACTION: leaves post published, no event, releases delete barrier")
    void shouldResolveReportWithNoActionAndReleaseDeleteBarrier() {
        UUID authorId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();
        UUID moderatorId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        // Seed PUBLISHED post
        CommunityPost post = CommunityPost.rehydrate(
                postId, authorId, "Completely fine post", null,
                CommunityPostStatus.PUBLISHED, 1, now.minusSeconds(100), now.minusSeconds(100),
                now.minusSeconds(100), null
        );
        postRepositoryPort.save(post);

        // Seed PENDING report
        InteractionReport report = InteractionReport.createPending(
                UUID.randomUUID(),
                ReportTargetType.COMMUNITY_POST,
                postId,
                reporterId,
                ReportReason.SPAM,
                "False report",
                "Completely fine post",
                null,
                now.minusSeconds(50)
        );
        reportRepositoryPort.save(report);

        // 1. Author cannot delete while pending report exists
        assertThatThrownBy(() -> deleteUseCase.execute(authorId, postId))
                .isInstanceOf(CommunityPostPendingReportConflictException.class);

        // 2. Resolve report with NO_ACTION
        ResolveCommunityPostReportCommand command = new ResolveCommunityPostReportCommand(
                report.getId(),
                moderatorId,
                ReportModerationAction.NO_ACTION,
                "Report rejected, post does not violate policy"
        );
        resolveReportUseCase.execute(command);

        // Verify post remains PUBLISHED in MySQL
        CommunityPost updatedPost = postRepositoryPort.findById(postId).orElseThrow();
        assertThat(updatedPost.getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);

        // Verify no moderation event recorded for post
        List<CommunityPostModerationEvent> events = moderationEventRepositoryPort.findByPostIdOrderByCreatedAtAsc(postId);
        assertThat(events).isEmpty();

        // Verify report resolved with NO_ACTION
        InteractionReport updatedReport = reportRepositoryPort.findById(report.getId()).orElseThrow();
        assertThat(updatedReport.getStatus()).isEqualTo(ReportStatus.RESOLVED_NO_ACTION);
        assertThat(updatedReport.getModerationAction()).isEqualTo(ReportModerationAction.NO_ACTION);

        // 3. Delete barrier is released: Author can delete post
        deleteUseCase.execute(authorId, postId);
        assertThat(postRepositoryPort.findById(postId)).isEmpty();
    }

    @Test
    @DisplayName("Approve post transitions PENDING_REVIEW -> PUBLISHED and records event")
    void shouldApprovePendingReviewPost() {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID moderatorId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        CommunityPost post = CommunityPost.rehydrate(
                postId, authorId, "Pending approval caption", null,
                CommunityPostStatus.PENDING_REVIEW, 1, now.minusSeconds(60), now.minusSeconds(60),
                null, now.minusSeconds(60)
        );
        postRepositoryPort.save(post);

        approveUseCase.execute(new ApproveCommunityPostCommand(postId, moderatorId, "Approved by editor"));

        CommunityPost updated = postRepositoryPort.findById(postId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);

        List<CommunityPostModerationEvent> events = moderationEventRepositoryPort.findByPostIdOrderByCreatedAtAsc(postId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).action()).isEqualTo(CommunityPostModerationAction.APPROVE);
        assertThat(events.get(0).fromStatus()).isEqualTo(CommunityPostStatus.PENDING_REVIEW);
        assertThat(events.get(0).toStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
        assertThat(events.get(0).reason()).isEqualTo("Approved by editor");
    }

    @Test
    @DisplayName("Reject post transitions PENDING_REVIEW -> REJECTED and records event")
    void shouldRejectPendingReviewPost() {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID moderatorId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        CommunityPost post = CommunityPost.rehydrate(
                postId, authorId, "Pending rejection caption", null,
                CommunityPostStatus.PENDING_REVIEW, 1, now.minusSeconds(60), now.minusSeconds(60),
                null, now.minusSeconds(60)
        );
        postRepositoryPort.save(post);

        rejectUseCase.execute(new RejectCommunityPostCommand(postId, moderatorId, "Rejected: does not meet criteria"));

        CommunityPost updated = postRepositoryPort.findById(postId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(CommunityPostStatus.REJECTED);

        List<CommunityPostModerationEvent> events = moderationEventRepositoryPort.findByPostIdOrderByCreatedAtAsc(postId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).action()).isEqualTo(CommunityPostModerationAction.REJECT);
        assertThat(events.get(0).fromStatus()).isEqualTo(CommunityPostStatus.PENDING_REVIEW);
        assertThat(events.get(0).toStatus()).isEqualTo(CommunityPostStatus.REJECTED);
        assertThat(events.get(0).reason()).isEqualTo("Rejected: does not meet criteria");
    }

    @Test
    @DisplayName("Restore post transitions HIDDEN -> PUBLISHED and records event")
    void shouldRestoreHiddenPost() {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID moderatorId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        CommunityPost post = CommunityPost.rehydrate(
                postId, authorId, "Hidden post caption", null,
                CommunityPostStatus.HIDDEN, 1, now.minusSeconds(60), now.minusSeconds(60),
                now.minusSeconds(60), null
        );
        postRepositoryPort.save(post);

        restoreUseCase.execute(new RestoreCommunityPostCommand(postId, moderatorId, "Restored after appeal"));

        CommunityPost updated = postRepositoryPort.findById(postId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);

        List<CommunityPostModerationEvent> events = moderationEventRepositoryPort.findByPostIdOrderByCreatedAtAsc(postId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).action()).isEqualTo(CommunityPostModerationAction.RESTORE);
        assertThat(events.get(0).fromStatus()).isEqualTo(CommunityPostStatus.HIDDEN);
        assertThat(events.get(0).toStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
        assertThat(events.get(0).reason()).isEqualTo("Restored after appeal");
    }

    @Test
    @DisplayName("Pending review queue queries in created_at ASC order (oldest first)")
    void shouldQueryPendingReviewPostsOldestFirst() {
        Instant baseTime = Instant.now().minus(10, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();

        // Save in random order
        postRepositoryPort.save(CommunityPost.rehydrate(p2, UUID.randomUUID(), "Post 2", null, CommunityPostStatus.PENDING_REVIEW, 1, baseTime.plusSeconds(200), baseTime.plusSeconds(200), null, baseTime.plusSeconds(200)));
        postRepositoryPort.save(CommunityPost.rehydrate(p1, UUID.randomUUID(), "Post 1", null, CommunityPostStatus.PENDING_REVIEW, 1, baseTime.plusSeconds(100), baseTime.plusSeconds(100), null, baseTime.plusSeconds(100)));
        postRepositoryPort.save(CommunityPost.rehydrate(p3, UUID.randomUUID(), "Post 3", null, CommunityPostStatus.PENDING_REVIEW, 1, baseTime.plusSeconds(300), baseTime.plusSeconds(300), null, baseTime.plusSeconds(300)));

        CommunityPostPage page = postRepositoryPort.findPendingReviewPosts(0, 10);
        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.items()).hasSize(3);
        assertThat(page.items().get(0).getId()).isEqualTo(p1);
        assertThat(page.items().get(1).getId()).isEqualTo(p2);
        assertThat(page.items().get(2).getId()).isEqualTo(p3);
    }

    @Test
    @DisplayName("Hidden queue queries in updated_at DESC order (newest first)")
    void shouldQueryHiddenPostsNewestFirst() {
        Instant baseTime = Instant.now().minus(10, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();

        // Save in random order
        postRepositoryPort.save(CommunityPost.rehydrate(p2, UUID.randomUUID(), "Post 2", null, CommunityPostStatus.HIDDEN, 1, baseTime, baseTime.plusSeconds(200), baseTime, null));
        postRepositoryPort.save(CommunityPost.rehydrate(p1, UUID.randomUUID(), "Post 1", null, CommunityPostStatus.HIDDEN, 1, baseTime, baseTime.plusSeconds(100), baseTime, null));
        postRepositoryPort.save(CommunityPost.rehydrate(p3, UUID.randomUUID(), "Post 3", null, CommunityPostStatus.HIDDEN, 1, baseTime, baseTime.plusSeconds(300), baseTime, null));

        CommunityPostPage page = postRepositoryPort.findHiddenPosts(0, 10);
        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.items()).hasSize(3);
        assertThat(page.items().get(0).getId()).isEqualTo(p3);
        assertThat(page.items().get(1).getId()).isEqualTo(p2);
        assertThat(page.items().get(2).getId()).isEqualTo(p1);
    }

    @Test
    @DisplayName("Community post report queue queries with scope, reason, and pagination")
    void shouldQueryCommunityPostReportsWithFilters() {
        Instant baseTime = Instant.now().minus(10, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        UUID targetId = UUID.randomUUID();

        // Report 1: PENDING, SPAM
        reportRepositoryPort.save(InteractionReport.createPending(
                UUID.randomUUID(), ReportTargetType.COMMUNITY_POST, targetId, UUID.randomUUID(),
                ReportReason.SPAM, "Spam 1", "Snapshot", null, baseTime.plusSeconds(100)
        ));
        // Report 2: PENDING, HARASSMENT
        reportRepositoryPort.save(InteractionReport.createPending(
                UUID.randomUUID(), ReportTargetType.COMMUNITY_POST, targetId, UUID.randomUUID(),
                ReportReason.HARASSMENT, "Harassment 1", "Snapshot", null, baseTime.plusSeconds(200)
        ));
        // Report 3: RESOLVED_NO_ACTION, SPAM
        InteractionReport r3 = InteractionReport.createPending(
                UUID.randomUUID(), ReportTargetType.COMMUNITY_POST, targetId, UUID.randomUUID(),
                ReportReason.SPAM, "Spam 2", "Snapshot", null, baseTime.plusSeconds(300)
        );
        r3.resolveNoAction(UUID.randomUUID(), baseTime.plusSeconds(350));
        reportRepositoryPort.save(r3);

        // Query PENDING scope, any reason
        InteractionReportPage pendingPage = reportRepositoryPort.findCommunityPostReports(ReportStatus.PENDING, null, false, 0, 10);
        assertThat(pendingPage.totalElements()).isEqualTo(2);

        // Query PENDING scope, HARASSMENT reason
        InteractionReportPage harassmentPage = reportRepositoryPort.findCommunityPostReports(ReportStatus.PENDING, ReportReason.HARASSMENT, false, 0, 10);
        assertThat(harassmentPage.totalElements()).isEqualTo(1);
        assertThat(harassmentPage.items().get(0).getReason()).isEqualTo(ReportReason.HARASSMENT);

        // Query RESOLVED_NO_ACTION scope
        InteractionReportPage resolvedPage = reportRepositoryPort.findCommunityPostReports(ReportStatus.RESOLVED_NO_ACTION, null, false, 0, 10);
        assertThat(resolvedPage.totalElements()).isEqualTo(1);

        // Query ALL scope (status = null)
        InteractionReportPage allPage = reportRepositoryPort.findCommunityPostReports(null, null, false, 0, 10);
        assertThat(allPage.totalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("Pessimistic concurrency: concurrent CONTENT_HIDDEN resolution does not violate invariants")
    void shouldHandleConcurrentReportResolutionUnderLock() throws Exception {
        UUID authorId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        UUID moderator1 = UUID.randomUUID();
        UUID moderator2 = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        CommunityPost post = CommunityPost.rehydrate(
                postId, authorId, "Concurrent test caption", null,
                CommunityPostStatus.PUBLISHED, 1, now.minusSeconds(100), now.minusSeconds(100),
                now.minusSeconds(100), null
        );
        postRepositoryPort.save(post);

        InteractionReport report = InteractionReport.createPending(
                UUID.randomUUID(), ReportTargetType.COMMUNITY_POST, postId, UUID.randomUUID(),
                ReportReason.SPAM, "Report", "Concurrent test caption", null, now.minusSeconds(50)
        );
        reportRepositoryPort.save(report);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Future<Boolean> taskHide = executor.submit(() -> {
            startLatch.await();
            try {
                resolveReportUseCase.execute(new ResolveCommunityPostReportCommand(
                        report.getId(), moderator1, ReportModerationAction.CONTENT_HIDDEN, "Mod 1 hide"
                ));
                return true;
            } catch (Exception e) {
                return false;
            }
        });

        Future<Boolean> taskNoAction = executor.submit(() -> {
            startLatch.await();
            try {
                resolveReportUseCase.execute(new ResolveCommunityPostReportCommand(
                        report.getId(), moderator2, ReportModerationAction.NO_ACTION, "Mod 2 no action"
                ));
                return true;
            } catch (Exception e) {
                return false;
            }
        });

        startLatch.countDown();
        boolean hideSuccess = taskHide.get(10, TimeUnit.SECONDS);
        boolean noActionSuccess = taskNoAction.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Exactly one moderator succeeds; loser fails deterministically
        assertThat(hideSuccess ^ noActionSuccess).isTrue();

        InteractionReport resolvedReport = reportRepositoryPort.findById(report.getId()).orElseThrow();
        CommunityPost updatedPost = postRepositoryPort.findById(postId).orElseThrow();
        List<CommunityPostModerationEvent> events = moderationEventRepositoryPort.findByPostIdOrderByCreatedAtAsc(postId);

        if (hideSuccess) {
            assertThat(resolvedReport.getStatus()).isEqualTo(ReportStatus.RESOLVED_ACTION_TAKEN);
            assertThat(resolvedReport.getModerationAction()).isEqualTo(ReportModerationAction.CONTENT_HIDDEN);
            assertThat(updatedPost.getStatus()).isEqualTo(CommunityPostStatus.HIDDEN);
            assertThat(events).hasSize(1);
            assertThat(events.get(0).action()).isEqualTo(CommunityPostModerationAction.HIDE);
        } else {
            assertThat(resolvedReport.getStatus()).isEqualTo(ReportStatus.RESOLVED_NO_ACTION);
            assertThat(resolvedReport.getModerationAction()).isEqualTo(ReportModerationAction.NO_ACTION);
            assertThat(updatedPost.getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
            assertThat(events).isEmpty();
        }
    }

    // =========================================================================
    // SECTION 5: MULTI-PENDING-REPORT BARRIER AUDIT
    // =========================================================================

    @Test
    @DisplayName("Section 5: Multi-pending-report barrier keeps delete blocked until ALL pending reports resolved NO_ACTION")
    void shouldEnforceDeleteBarrierUntilAllPendingReportsResolved() {
        UUID authorId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        UUID reporter1 = UUID.randomUUID();
        UUID reporter2 = UUID.randomUUID();
        UUID moderatorId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        // Seed PUBLISHED post
        CommunityPost post = CommunityPost.rehydrate(
                postId, authorId, "Multi-report test caption", null,
                CommunityPostStatus.PUBLISHED, 1, now.minusSeconds(100), now.minusSeconds(100),
                now.minusSeconds(100), null
        );
        postRepositoryPort.save(post);

        // Seed Report A (PENDING)
        InteractionReport reportA = InteractionReport.createPending(
                UUID.randomUUID(), ReportTargetType.COMMUNITY_POST, postId, reporter1,
                ReportReason.SPAM, "Report A spam", "Multi-report test caption", null, now.minusSeconds(80)
        );
        reportRepositoryPort.save(reportA);

        // Seed Report B (PENDING)
        InteractionReport reportB = InteractionReport.createPending(
                UUID.randomUUID(), ReportTargetType.COMMUNITY_POST, postId, reporter2,
                ReportReason.HARASSMENT, "Report B harassment", "Multi-report test caption", null, now.minusSeconds(60)
        );
        reportRepositoryPort.save(reportB);

        // Baseline: Delete blocked because 2 pending reports exist
        assertThatThrownBy(() -> deleteUseCase.execute(authorId, postId))
                .isInstanceOf(CommunityPostPendingReportConflictException.class);

        // Step 1: Resolve Report A as NO_ACTION
        resolveReportUseCase.execute(new ResolveCommunityPostReportCommand(
                reportA.getId(), moderatorId, ReportModerationAction.NO_ACTION, "Report A dismissed"
        ));

        // Delete MUST STILL BE BLOCKED because Report B is still PENDING
        assertThatThrownBy(() -> deleteUseCase.execute(authorId, postId))
                .isInstanceOf(CommunityPostPendingReportConflictException.class);

        // Verify post remains PUBLISHED
        CommunityPost postAfterA = postRepositoryPort.findById(postId).orElseThrow();
        assertThat(postAfterA.getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);

        // Step 2: Resolve Report B as NO_ACTION
        resolveReportUseCase.execute(new ResolveCommunityPostReportCommand(
                reportB.getId(), moderatorId, ReportModerationAction.NO_ACTION, "Report B dismissed"
        ));

        // Delete barrier is now completely released -> Delete MUST SUCCEED
        deleteUseCase.execute(authorId, postId);
        assertThat(postRepositoryPort.findById(postId)).isEmpty();
    }

    // =========================================================================
    // SECTION 6: ZERO-REPORT DISCOVERABILITY AUDIT
    // =========================================================================

    @Test
    @DisplayName("Section 6: Pending review and hidden queues discover posts with ZERO reports")
    void shouldDiscoverZeroReportPostsInPendingAndHiddenQueues() {
        UUID pendingPostId = UUID.randomUUID();
        UUID hiddenPostId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        // Post in PENDING_REVIEW without reports
        CommunityPost pendingPost = CommunityPost.rehydrate(
                pendingPostId, UUID.randomUUID(), "Pending review zero reports", null,
                CommunityPostStatus.PENDING_REVIEW, 1, now.minusSeconds(100), now.minusSeconds(100),
                null, now.minusSeconds(100)
        );
        postRepositoryPort.save(pendingPost);

        // Post in HIDDEN without reports
        CommunityPost hiddenPost = CommunityPost.rehydrate(
                hiddenPostId, UUID.randomUUID(), "Hidden post zero reports", null,
                CommunityPostStatus.HIDDEN, 1, now.minusSeconds(50), now.minusSeconds(50),
                now.minusSeconds(50), null
        );
        postRepositoryPort.save(hiddenPost);

        // Verify pending queue discovers the zero-report post
        CommunityPostPage pendingPage = postRepositoryPort.findPendingReviewPosts(0, 10);
        assertThat(pendingPage.items().stream().anyMatch(p -> p.getId().equals(pendingPostId))).isTrue();

        // Verify hidden queue discovers the zero-report post
        CommunityPostPage hiddenPage = postRepositoryPort.findHiddenPosts(0, 10);
        assertThat(hiddenPage.items().stream().anyMatch(p -> p.getId().equals(hiddenPostId))).isTrue();
    }

    // =========================================================================
    // SECTION 9: REAL MYSQL CONCURRENCY & STALE-ACTION PROOFS
    // =========================================================================

    @Test
    @DisplayName("Section 9 Proof A: Two admins concurrently approve same pending post — exactly one succeeds")
    void shouldHandleConcurrentApproveUnderLock() throws Exception {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID mod1 = UUID.randomUUID();
        UUID mod2 = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        CommunityPost post = CommunityPost.rehydrate(
                postId, authorId, "Pending approval race caption", null,
                CommunityPostStatus.PENDING_REVIEW, 1, now.minusSeconds(60), now.minusSeconds(60),
                null, now.minusSeconds(60)
        );
        postRepositoryPort.save(post);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Future<Boolean> task1 = executor.submit(() -> {
            startLatch.await();
            try {
                approveUseCase.execute(new ApproveCommunityPostCommand(postId, mod1, "Mod 1 approve"));
                return true;
            } catch (Exception e) {
                return false;
            }
        });

        Future<Boolean> task2 = executor.submit(() -> {
            startLatch.await();
            try {
                approveUseCase.execute(new ApproveCommunityPostCommand(postId, mod2, "Mod 2 approve"));
                return true;
            } catch (Exception e) {
                return false;
            }
        });

        startLatch.countDown();
        boolean res1 = task1.get(10, TimeUnit.SECONDS);
        boolean res2 = task2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Exactly one succeeds; other fails cleanly due to status no longer being PENDING_REVIEW
        assertThat(res1 ^ res2).isTrue();

        CommunityPost updated = postRepositoryPort.findById(postId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);

        List<CommunityPostModerationEvent> events = moderationEventRepositoryPort.findByPostIdOrderByCreatedAtAsc(postId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).action()).isEqualTo(CommunityPostModerationAction.APPROVE);
    }

    @Test
    @DisplayName("Section 9 Proof B: Admin A approves while Admin B rejects same pending post concurrently — exactly one succeeds")
    void shouldHandleConcurrentApproveVsRejectUnderLock() throws Exception {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID mod1 = UUID.randomUUID();
        UUID mod2 = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        CommunityPost post = CommunityPost.rehydrate(
                postId, authorId, "Pending approve vs reject race", null,
                CommunityPostStatus.PENDING_REVIEW, 1, now.minusSeconds(60), now.minusSeconds(60),
                null, now.minusSeconds(60)
        );
        postRepositoryPort.save(post);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Future<Boolean> taskApprove = executor.submit(() -> {
            startLatch.await();
            try {
                approveUseCase.execute(new ApproveCommunityPostCommand(postId, mod1, "Approve winner"));
                return true;
            } catch (Exception e) {
                return false;
            }
        });

        Future<Boolean> taskReject = executor.submit(() -> {
            startLatch.await();
            try {
                rejectUseCase.execute(new RejectCommunityPostCommand(postId, mod2, "Reject winner"));
                return true;
            } catch (Exception e) {
                return false;
            }
        });

        startLatch.countDown();
        boolean approveResult = taskApprove.get(10, TimeUnit.SECONDS);
        boolean rejectResult = taskReject.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Exactly one succeeds
        assertThat(approveResult ^ rejectResult).isTrue();

        CommunityPost updated = postRepositoryPort.findById(postId).orElseThrow();
        List<CommunityPostModerationEvent> events = moderationEventRepositoryPort.findByPostIdOrderByCreatedAtAsc(postId);
        assertThat(events).hasSize(1);

        if (approveResult) {
            assertThat(updated.getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
            assertThat(events.get(0).action()).isEqualTo(CommunityPostModerationAction.APPROVE);
        } else {
            assertThat(updated.getStatus()).isEqualTo(CommunityPostStatus.REJECTED);
            assertThat(events.get(0).action()).isEqualTo(CommunityPostModerationAction.REJECT);
        }
    }

    @Test
    @DisplayName("Concurrent approve vs reject on PUBLISHED post with pendingCaption edit — exactly one succeeds")
    void shouldHandleConcurrentApproveAndRejectForPendingCaptionEdit() throws Exception {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID mod1 = UUID.randomUUID();
        UUID mod2 = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant t0 = now.minusSeconds(3600);
        Instant t1 = now.minusSeconds(600);

        CommunityPost post = CommunityPost.rehydrate(
                postId, authorId, "Old approved caption", "New candidate caption", null,
                CommunityPostStatus.PUBLISHED, 0, t0, t1, t0, t1
        );
        postRepositoryPort.save(post);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Future<Boolean> taskApprove = executor.submit(() -> {
            startLatch.await();
            try {
                approveUseCase.execute(new ApproveCommunityPostCommand(postId, mod1, "Approve edit"));
                return true;
            } catch (Exception e) {
                return false;
            }
        });

        Future<Boolean> taskReject = executor.submit(() -> {
            startLatch.await();
            try {
                rejectUseCase.execute(new RejectCommunityPostCommand(postId, mod2, "Reject edit"));
                return true;
            } catch (Exception e) {
                return false;
            }
        });

        startLatch.countDown();
        boolean approveResult = taskApprove.get(10, TimeUnit.SECONDS);
        boolean rejectResult = taskReject.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Exactly one succeeds
        assertThat(approveResult ^ rejectResult).isTrue();

        CommunityPost updated = postRepositoryPort.findById(postId).orElseThrow();
        List<CommunityPostModerationEvent> events = moderationEventRepositoryPort.findByPostIdOrderByCreatedAtAsc(postId);
        assertThat(events).hasSize(1);

        // Common invariants in both outcomes
        assertThat(updated.getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
        assertThat(updated.getPendingCaption()).isNull();
        assertThat(updated.getReviewRequestedAt()).isNull();
        assertThat(updated.getPublishedAt()).isEqualTo(t0); // preserved!
        assertThat(events.get(0).fromStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
        assertThat(events.get(0).toStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);

        int revCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_post_revisions WHERE post_id = ?",
                Integer.class,
                postId.toString()
        );

        if (approveResult) {
            assertThat(updated.getCaption()).isEqualTo("New candidate caption");
            assertThat(updated.getContentVersion()).isEqualTo(1);
            assertThat(events.get(0).action()).isEqualTo(CommunityPostModerationAction.APPROVE);
            assertThat(revCount).isEqualTo(1);
        } else {
            assertThat(updated.getCaption()).isEqualTo("Old approved caption");
            assertThat(updated.getContentVersion()).isEqualTo(0);
            assertThat(events.get(0).action()).isEqualTo(CommunityPostModerationAction.REJECT);
            assertThat(revCount).isEqualTo(0);
        }
    }

    @Test
    @DisplayName("Race: Admin approval of pendingCaption vs second author edit attempt")
    void shouldHandleApprovalVsSecondAuthorEditConcurrency() throws Exception {
        jdbcTemplate.update("UPDATE community_settings SET publication_mode = 'PRE_MODERATION' WHERE id = 'DEFAULT'");

        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID modId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant t0 = now.minusSeconds(3600);
        Instant t1 = now.minusSeconds(600);

        CommunityPost post = CommunityPost.rehydrate(
                postId, authorId, "Old approved caption", "Candidate 1", null,
                CommunityPostStatus.PUBLISHED, 0, t0, t1, t0, t1
        );
        postRepositoryPort.save(post);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Future<Boolean> taskApprove = executor.submit(() -> {
            startLatch.await();
            try {
                approveUseCase.execute(new ApproveCommunityPostCommand(postId, modId, "Admin approves Candidate 1"));
                return true;
            } catch (Exception e) {
                return false;
            }
        });

        Future<Boolean> taskEdit = executor.submit(() -> {
            startLatch.await();
            try {
                editUseCase.execute(new EditCommunityPostCaptionCommand(postId, authorId, "Candidate 2"));
                return true;
            } catch (com.universe.community.domain.exception.CommunityPostPendingEditConflictException e) {
                // Expected conflict if edit observed existing pendingCaption
                return false;
            } catch (Exception e) {
                return false;
            }
        });

        startLatch.countDown();
        boolean approveResult = taskApprove.get(10, TimeUnit.SECONDS);
        boolean editResult = taskEdit.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Admin approve must always succeed because it either runs before the edit, or runs after the rejected edit
        assertThat(approveResult).isTrue();

        CommunityPost updated = postRepositoryPort.findById(postId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
        assertThat(updated.getPublishedAt()).isEqualTo(t0);

        if (editResult) {
            // Approval won first: Candidate 1 became approved caption (contentVersion=1).
            // Then edit ran: observed pendingCaption == null, set Candidate 2 as new pendingCaption!
            assertThat(updated.getCaption()).isEqualTo("Candidate 1");
            assertThat(updated.getPendingCaption()).isEqualTo("Candidate 2");
            assertThat(updated.getReviewRequestedAt()).isNotNull();
            assertThat(updated.getContentVersion()).isEqualTo(1);
        } else {
            // Edit observed Candidate 1 still pending: rejected by single-candidate barrier (409 Conflict).
            // Then approval promoted Candidate 1.
            assertThat(updated.getCaption()).isEqualTo("Candidate 1");
            assertThat(updated.getPendingCaption()).isNull();
            assertThat(updated.getReviewRequestedAt()).isNull();
            assertThat(updated.getContentVersion()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("Section 9 Proof C: Two admins concurrently restore same hidden post — exactly one succeeds")
    void shouldHandleConcurrentRestoreUnderLock() throws Exception {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID mod1 = UUID.randomUUID();
        UUID mod2 = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        CommunityPost post = CommunityPost.rehydrate(
                postId, authorId, "Hidden restore race caption", null,
                CommunityPostStatus.HIDDEN, 1, now.minusSeconds(60), now.minusSeconds(60),
                now.minusSeconds(60), null
        );
        postRepositoryPort.save(post);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Future<Boolean> task1 = executor.submit(() -> {
            startLatch.await();
            try {
                restoreUseCase.execute(new RestoreCommunityPostCommand(postId, mod1, "Mod 1 restore"));
                return true;
            } catch (Exception e) {
                return false;
            }
        });

        Future<Boolean> task2 = executor.submit(() -> {
            startLatch.await();
            try {
                restoreUseCase.execute(new RestoreCommunityPostCommand(postId, mod2, "Mod 2 restore"));
                return true;
            } catch (Exception e) {
                return false;
            }
        });

        startLatch.countDown();
        boolean res1 = task1.get(10, TimeUnit.SECONDS);
        boolean res2 = task2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Exactly one succeeds
        assertThat(res1 ^ res2).isTrue();

        CommunityPost updated = postRepositoryPort.findById(postId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);

        List<CommunityPostModerationEvent> events = moderationEventRepositoryPort.findByPostIdOrderByCreatedAtAsc(postId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).action()).isEqualTo(CommunityPostModerationAction.RESTORE);
    }

    @Test
    @DisplayName("Section 9 Proof E: Concurrent post hide vs author delete — strictly serialized without deadlock")
    void shouldHandleConcurrentHideVsDeleteUnderLock() throws Exception {
        UUID authorId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        UUID modId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        CommunityPost post = CommunityPost.rehydrate(
                postId, authorId, "Hide vs delete race caption", null,
                CommunityPostStatus.PUBLISHED, 1, now.minusSeconds(100), now.minusSeconds(100),
                now.minusSeconds(100), null
        );
        postRepositoryPort.save(post);

        InteractionReport report = InteractionReport.createPending(
                UUID.randomUUID(), ReportTargetType.COMMUNITY_POST, postId, UUID.randomUUID(),
                ReportReason.SPAM, "Spam race", "Hide vs delete race caption", null, now.minusSeconds(50)
        );
        reportRepositoryPort.save(report);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Future<Boolean> taskHide = executor.submit(() -> {
            startLatch.await();
            try {
                resolveReportUseCase.execute(new ResolveCommunityPostReportCommand(
                        report.getId(), modId, ReportModerationAction.CONTENT_HIDDEN, "Moderator hides post"
                ));
                return true;
            } catch (Exception e) {
                return false;
            }
        });

        Future<Boolean> taskDelete = executor.submit(() -> {
            startLatch.await();
            try {
                deleteUseCase.execute(authorId, postId);
                return true;
            } catch (Exception e) {
                return false;
            }
        });

        startLatch.countDown();
        boolean hideSuccess = taskHide.get(10, TimeUnit.SECONDS);
        boolean deleteSuccess = taskDelete.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Because a pending report existed before the race, owner delete must NOT succeed:
        // - If delete runs first: rejected by pending-report barrier (CommunityPostPendingReportConflictException)
        // - If delete runs second: rejected by hidden-delete barrier (CommunityPostHiddenDeleteForbiddenException)
        assertThat(hideSuccess).isTrue();
        assertThat(deleteSuccess).isFalse();

        // 1. Post still exists
        CommunityPost remainingPost = postRepositoryPort.findById(postId).orElseThrow();
        // 2. Post status is HIDDEN
        assertThat(remainingPost.getStatus()).isEqualTo(CommunityPostStatus.HIDDEN);
        // 3. Report is resolved CONTENT_HIDDEN
        InteractionReport updatedReport = reportRepositoryPort.findById(report.getId()).orElseThrow();
        assertThat(updatedReport.getStatus()).isEqualTo(ReportStatus.RESOLVED_ACTION_TAKEN);
        assertThat(updatedReport.getModerationAction()).isEqualTo(ReportModerationAction.CONTENT_HIDDEN);
        // 4. Exactly one HIDE event
        List<CommunityPostModerationEvent> events = moderationEventRepositoryPort.findByPostIdOrderByCreatedAtAsc(postId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).action()).isEqualTo(CommunityPostModerationAction.HIDE);
    }
}
