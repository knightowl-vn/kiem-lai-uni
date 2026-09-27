package com.universe.wiki.infrastructure.persistence.contribution;

import com.universe.test.TestDatabaseSupport;
import com.universe.wiki.domain.contribution.WikiContribution;
import com.universe.wiki.domain.contribution.WikiContributionEventType;
import com.universe.wiki.domain.contribution.WikiContributionResolutionOutcome;
import com.universe.wiki.domain.contribution.WikiContributionStatus;
import com.universe.wiki.domain.contribution.WikiContributionType;
import com.universe.wiki.domain.contribution.WikiContributionWorkflowEvent;
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
        "spring.flyway.enabled=true",
        "spring.flyway.out-of-order=true"
})
@Import({
        WikiContributionPersistenceAdapter.class,
        WikiContributionWorkflowEventPersistenceAdapter.class
})
@DisplayName("WikiContributionWorkflowEvent JPA Persistence Integration Tests")
class WikiContributionWorkflowEventJpaPersistenceIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private WikiContributionPersistenceAdapter contributionAdapter;

    @Autowired
    private WikiContributionWorkflowEventPersistenceAdapter workflowEventAdapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM wiki_contribution_workflow_events");
        jdbcTemplate.execute("DELETE FROM wiki_contribution_sources");
        jdbcTemplate.execute("DELETE FROM wiki_contributions");
    }

    private WikiContribution createTestContribution(UUID contributionId) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        WikiContribution contribution = WikiContribution.createGeneral(
                contributionId,
                UUID.randomUUID(),
                "CHARACTER",
                "Trần Bình An",
                "tran-binh-an",
                1L,
                UUID.randomUUID(),
                WikiContributionType.MISSING_INFORMATION,
                "Đóng góp bài viết thử nghiệm đính kèm sự kiện quy trình.",
                now
        );
        return contributionAdapter.save(contribution);
    }

    @Test
    @DisplayName("Lưu và truy vấn sự kiện quy trình theo contributionId: bảo đảm sắp xếp tăng dần theo createdAt ASC, id ASC")
    void shouldPersistAndRetrieveEventsOrderedByCreatedAtAscIdAsc() {
        UUID contributionId = UUID.randomUUID();
        createTestContribution(contributionId);

        Instant baseTime = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID actorId = UUID.randomUUID();

        WikiContributionWorkflowEvent event1 = new WikiContributionWorkflowEvent(
                UUID.randomUUID(),
                contributionId,
                WikiContributionEventType.REVIEW_STARTED,
                actorId,
                null,
                WikiContributionStatus.NEW,
                WikiContributionStatus.REVIEWING,
                1L,
                null,
                "Bắt đầu thẩm định",
                baseTime
        );

        WikiContributionWorkflowEvent event2 = new WikiContributionWorkflowEvent(
                UUID.randomUUID(),
                contributionId,
                WikiContributionEventType.ARTICLE_UPDATE_LINKED,
                actorId,
                null,
                WikiContributionStatus.REVIEWING,
                WikiContributionStatus.REVIEWING,
                2L,
                null,
                "Liên kết bản cập nhật bài viết v2",
                baseTime.plus(5, ChronoUnit.MINUTES)
        );

        WikiContributionWorkflowEvent event3 = new WikiContributionWorkflowEvent(
                UUID.randomUUID(),
                contributionId,
                WikiContributionEventType.RESOLVED,
                actorId,
                null,
                WikiContributionStatus.REVIEWING,
                WikiContributionStatus.RESOLVED,
                2L,
                WikiContributionResolutionOutcome.APPLIED,
                "Đã áp dụng thay đổi vào bài viết",
                baseTime.plus(10, ChronoUnit.MINUTES)
        );

        workflowEventAdapter.save(event1);
        workflowEventAdapter.save(event2);
        workflowEventAdapter.save(event3);

        List<WikiContributionWorkflowEvent> events = workflowEventAdapter.findByContributionId(contributionId);

        assertThat(events).hasSize(3);

        assertThat(events.get(0).id()).isEqualTo(event1.id());
        assertThat(events.get(0).eventType()).isEqualTo(WikiContributionEventType.REVIEW_STARTED);
        assertThat(events.get(0).actorUserId()).isEqualTo(actorId);
        assertThat(events.get(0).fromStatus()).isEqualTo(WikiContributionStatus.NEW);
        assertThat(events.get(0).toStatus()).isEqualTo(WikiContributionStatus.REVIEWING);
        assertThat(events.get(0).articleContentVersion()).isEqualTo(1L);
        assertThat(events.get(0).note()).isEqualTo("Bắt đầu thẩm định");

        assertThat(events.get(1).id()).isEqualTo(event2.id());
        assertThat(events.get(1).eventType()).isEqualTo(WikiContributionEventType.ARTICLE_UPDATE_LINKED);
        assertThat(events.get(1).articleContentVersion()).isEqualTo(2L);

        assertThat(events.get(2).id()).isEqualTo(event3.id());
        assertThat(events.get(2).eventType()).isEqualTo(WikiContributionEventType.RESOLVED);
        assertThat(events.get(2).fromStatus()).isEqualTo(WikiContributionStatus.REVIEWING);
        assertThat(events.get(2).toStatus()).isEqualTo(WikiContributionStatus.RESOLVED);
        assertThat(events.get(2).resolutionOutcome()).isEqualTo(WikiContributionResolutionOutcome.APPLIED);
        assertThat(events.get(2).note()).isEqualTo("Đã áp dụng thay đổi vào bài viết");
    }

    @Test
    @DisplayName("Lưu và truy vấn đúng from_status và to_status cho toàn bộ 6 loại sự kiện quy trình")
    void shouldPersistAndRetrieveFromStatusAndToStatusForAllEventTypes() {
        UUID contributionId = UUID.randomUUID();
        createTestContribution(contributionId);

        Instant baseTime = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        // 1. REVIEW_STARTED (NEW -> REVIEWING)
        WikiContributionWorkflowEvent reviewStarted = WikiContributionWorkflowEvent.createReviewStarted(
                UUID.randomUUID(), contributionId, actorId, 1L, baseTime
        );
        // 2. CLAIMED (REVIEWING -> REVIEWING)
        WikiContributionWorkflowEvent claimed = WikiContributionWorkflowEvent.createClaimed(
                UUID.randomUUID(), contributionId, actorId, baseTime.plus(1, ChronoUnit.MINUTES)
        );
        // 3. REASSIGNED (REVIEWING -> REVIEWING)
        WikiContributionWorkflowEvent reassigned = WikiContributionWorkflowEvent.createReassigned(
                UUID.randomUUID(), contributionId, actorId, targetId, "Bàn giao việc", baseTime.plus(2, ChronoUnit.MINUTES)
        );
        // 4. ARTICLE_UPDATE_LINKED (REVIEWING -> REVIEWING)
        WikiContributionWorkflowEvent updateLinked = WikiContributionWorkflowEvent.createArticleUpdateLinked(
                UUID.randomUUID(), contributionId, actorId, 2L, "Sửa bài theo đóng góp", baseTime.plus(3, ChronoUnit.MINUTES)
        );
        // 5. RESOLVED (REVIEWING -> RESOLVED)
        WikiContributionWorkflowEvent resolved = WikiContributionWorkflowEvent.createResolved(
                UUID.randomUUID(), contributionId, targetId, WikiContributionResolutionOutcome.APPLIED, 2L, "Đã hoàn thành", baseTime.plus(4, ChronoUnit.MINUTES)
        );
        // 6. REJECTED (REVIEWING -> REJECTED)
        UUID contributionId2 = UUID.randomUUID();
        createTestContribution(contributionId2);
        WikiContributionWorkflowEvent rejected = WikiContributionWorkflowEvent.createRejected(
                UUID.randomUUID(), contributionId2, actorId, "Không hợp lệ", baseTime.plus(5, ChronoUnit.MINUTES)
        );

        workflowEventAdapter.save(reviewStarted);
        workflowEventAdapter.save(claimed);
        workflowEventAdapter.save(reassigned);
        workflowEventAdapter.save(updateLinked);
        workflowEventAdapter.save(resolved);
        workflowEventAdapter.save(rejected);

        List<WikiContributionWorkflowEvent> events1 = workflowEventAdapter.findByContributionId(contributionId);
        assertThat(events1).hasSize(5);

        assertThat(events1.get(0).eventType()).isEqualTo(WikiContributionEventType.REVIEW_STARTED);
        assertThat(events1.get(0).fromStatus()).isEqualTo(WikiContributionStatus.NEW);
        assertThat(events1.get(0).toStatus()).isEqualTo(WikiContributionStatus.REVIEWING);

        assertThat(events1.get(1).eventType()).isEqualTo(WikiContributionEventType.CLAIMED);
        assertThat(events1.get(1).fromStatus()).isEqualTo(WikiContributionStatus.REVIEWING);
        assertThat(events1.get(1).toStatus()).isEqualTo(WikiContributionStatus.REVIEWING);

        assertThat(events1.get(2).eventType()).isEqualTo(WikiContributionEventType.REASSIGNED);
        assertThat(events1.get(2).fromStatus()).isEqualTo(WikiContributionStatus.REVIEWING);
        assertThat(events1.get(2).toStatus()).isEqualTo(WikiContributionStatus.REVIEWING);

        assertThat(events1.get(3).eventType()).isEqualTo(WikiContributionEventType.ARTICLE_UPDATE_LINKED);
        assertThat(events1.get(3).fromStatus()).isEqualTo(WikiContributionStatus.REVIEWING);
        assertThat(events1.get(3).toStatus()).isEqualTo(WikiContributionStatus.REVIEWING);

        assertThat(events1.get(4).eventType()).isEqualTo(WikiContributionEventType.RESOLVED);
        assertThat(events1.get(4).fromStatus()).isEqualTo(WikiContributionStatus.REVIEWING);
        assertThat(events1.get(4).toStatus()).isEqualTo(WikiContributionStatus.RESOLVED);

        List<WikiContributionWorkflowEvent> events2 = workflowEventAdapter.findByContributionId(contributionId2);
        assertThat(events2).hasSize(1);
        assertThat(events2.get(0).eventType()).isEqualTo(WikiContributionEventType.REJECTED);
        assertThat(events2.get(0).fromStatus()).isEqualTo(WikiContributionStatus.REVIEWING);
        assertThat(events2.get(0).toStatus()).isEqualTo(WikiContributionStatus.REJECTED);
    }

    @Test
    @DisplayName("Ràng buộc khóa ngoại ON DELETE RESTRICT: Ngăn xóa wiki_contributions khi đã có bản ghi wiki_contribution_workflow_events")
    void shouldEnforceForeignKeyAndOnDeleteRestrict() {
        UUID contributionId = UUID.randomUUID();
        createTestContribution(contributionId);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        WikiContributionWorkflowEvent event = new WikiContributionWorkflowEvent(
                UUID.randomUUID(),
                contributionId,
                WikiContributionEventType.REVIEW_STARTED,
                UUID.randomUUID(),
                null,
                WikiContributionStatus.NEW,
                WikiContributionStatus.REVIEWING,
                1L,
                null,
                "Khởi tạo thẩm định",
                now
        );
        workflowEventAdapter.save(event);

        // Thử xóa contribution cha từ database -> Bị từ chối bởi ON DELETE RESTRICT
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM wiki_contributions WHERE id = ?", contributionId.toString()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Ngăn chặn sự kiện mồ côi: Bác bỏ lưu sự kiện khi contributionId không tồn tại")
    void shouldRejectEventWithNonExistentContribution() {
        UUID nonExistentContributionId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        WikiContributionWorkflowEvent orphanEvent = new WikiContributionWorkflowEvent(
                UUID.randomUUID(),
                nonExistentContributionId,
                WikiContributionEventType.REVIEW_STARTED,
                UUID.randomUUID(),
                null,
                WikiContributionStatus.NEW,
                WikiContributionStatus.REVIEWING,
                1L,
                null,
                "Sự kiện mồ côi",
                now
        );

        assertThatThrownBy(() -> workflowEventAdapter.save(orphanEvent))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Ràng buộc CHECK ở cấp độ MySQL Database: ném ngoại lệ khi vi phạm event_type")
    void shouldEnforceEventTypeCheckConstraint() {
        UUID contributionId = UUID.randomUUID();
        createTestContribution(contributionId);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO wiki_contribution_workflow_events (
                    id, contribution_id, event_type, actor_user_id, from_status, to_status, created_at
                ) VALUES (?, ?, 'INVALID_EVENT_TYPE', ?, 'NEW', 'REVIEWING', NOW())
                """,
                UUID.randomUUID().toString(),
                contributionId.toString(),
                UUID.randomUUID().toString()
        )).hasMessageContaining("chk_wiki_contribution_events_type");
    }

    @Test
    @DisplayName("Ràng buộc CHECK ở cấp độ MySQL Database: ném ngoại lệ khi vi phạm resolution_outcome")
    void shouldEnforceResolutionOutcomeCheckConstraint() {
        UUID contributionId = UUID.randomUUID();
        createTestContribution(contributionId);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO wiki_contribution_workflow_events (
                    id, contribution_id, event_type, actor_user_id, from_status, to_status, resolution_outcome, created_at
                ) VALUES (?, ?, 'RESOLVED', ?, 'REVIEWING', 'RESOLVED', 'INVALID_OUTCOME', NOW())
                """,
                UUID.randomUUID().toString(),
                contributionId.toString(),
                UUID.randomUUID().toString()
        )).hasMessageContaining("chk_wiki_contribution_events_outcome");
    }
}
