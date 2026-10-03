package com.universe.community.infrastructure.persistence;

import com.universe.community.domain.CommunityPost;
import com.universe.community.domain.CommunityPostStatus;
import com.universe.community.domain.moderation.CommunityPostModerationAction;
import com.universe.community.domain.moderation.CommunityPostModerationEvent;
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
        CommunityPostModerationEventPersistenceAdapter.class,
        CommunityPostModerationEventPersistenceMapper.class,
        CommunityPostPersistenceAdapter.class,
        CommunityPostPersistenceMapper.class,
        CommunityPostRevisionPersistenceMapper.class
})
@DisplayName("CommunityPostModerationEvent Real Spring & MySQL Persistence Integration Tests")
class CommunityPostModerationEventPersistenceAdapterMySQLTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private CommunityPostModerationEventPersistenceAdapter eventAdapter;

    @Autowired
    private CommunityPostPersistenceAdapter postAdapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0;");
        jdbcTemplate.execute("TRUNCATE TABLE community_post_moderation_events;");
        jdbcTemplate.execute("TRUNCATE TABLE community_post_revisions;");
        jdbcTemplate.execute("TRUNCATE TABLE community_posts;");
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1;");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0;");
        jdbcTemplate.execute("TRUNCATE TABLE community_post_moderation_events;");
        jdbcTemplate.execute("TRUNCATE TABLE community_post_revisions;");
        jdbcTemplate.execute("TRUNCATE TABLE community_posts;");
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1;");
    }

    @Test
    @DisplayName("Should append event and round-trip exact action/from/to/moderator/reason/createdAt")
    void shouldAppendAndRoundTripModerationEvent() {
        UUID eventId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        UUID moderatorId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        CommunityPostModerationEvent event = new CommunityPostModerationEvent(
                eventId,
                postId,
                CommunityPostModerationAction.APPROVE,
                CommunityPostStatus.PENDING_REVIEW,
                CommunityPostStatus.PUBLISHED,
                moderatorId,
                "Approved after review",
                now
        );

        CommunityPostModerationEvent saved = eventAdapter.save(event);
        assertThat(saved.id()).isEqualTo(eventId);
        assertThat(saved.postId()).isEqualTo(postId);
        assertThat(saved.action()).isEqualTo(CommunityPostModerationAction.APPROVE);
        assertThat(saved.fromStatus()).isEqualTo(CommunityPostStatus.PENDING_REVIEW);
        assertThat(saved.toStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
        assertThat(saved.moderatorUserId()).isEqualTo(moderatorId);
        assertThat(saved.reason()).isEqualTo("Approved after review");
        assertThat(saved.createdAt()).isEqualTo(now);

        List<CommunityPostModerationEvent> events = eventAdapter.findByPostIdOrderByCreatedAtAsc(postId);
        assertThat(events).hasSize(1);
        CommunityPostModerationEvent retrieved = events.get(0);
        assertThat(retrieved.id()).isEqualTo(eventId);
        assertThat(retrieved.postId()).isEqualTo(postId);
        assertThat(retrieved.action()).isEqualTo(CommunityPostModerationAction.APPROVE);
        assertThat(retrieved.fromStatus()).isEqualTo(CommunityPostStatus.PENDING_REVIEW);
        assertThat(retrieved.toStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
        assertThat(retrieved.moderatorUserId()).isEqualTo(moderatorId);
        assertThat(retrieved.reason()).isEqualTo("Approved after review");
        assertThat(retrieved.createdAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("Should preserve append-only history and ordering across multiple transitions")
    void shouldPreserveAppendOnlyHistoryAndOrdering() {
        UUID postId = UUID.randomUUID();
        UUID mod1 = UUID.randomUUID();
        UUID mod2 = UUID.randomUUID();
        Instant t1 = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        Instant t2 = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        Instant t3 = Instant.now().truncatedTo(ChronoUnit.MICROS);

        CommunityPostModerationEvent e1 = new CommunityPostModerationEvent(
                UUID.randomUUID(),
                postId,
                CommunityPostModerationAction.APPROVE,
                CommunityPostStatus.PENDING_REVIEW,
                CommunityPostStatus.PUBLISHED,
                mod1,
                "Initial approval",
                t1
        );
        CommunityPostModerationEvent e2 = new CommunityPostModerationEvent(
                UUID.randomUUID(),
                postId,
                CommunityPostModerationAction.HIDE,
                CommunityPostStatus.PUBLISHED,
                CommunityPostStatus.HIDDEN,
                mod2,
                "Reported violation",
                t2
        );
        CommunityPostModerationEvent e3 = new CommunityPostModerationEvent(
                UUID.randomUUID(),
                postId,
                CommunityPostModerationAction.RESTORE,
                CommunityPostStatus.HIDDEN,
                CommunityPostStatus.PUBLISHED,
                mod1,
                "Appeal accepted",
                t3
        );

        eventAdapter.save(e1);
        eventAdapter.save(e2);
        eventAdapter.save(e3);

        List<CommunityPostModerationEvent> events = eventAdapter.findByPostIdOrderByCreatedAtAsc(postId);
        assertThat(events).hasSize(3);
        assertThat(events.get(0).action()).isEqualTo(CommunityPostModerationAction.APPROVE);
        assertThat(events.get(1).action()).isEqualTo(CommunityPostModerationAction.HIDE);
        assertThat(events.get(2).action()).isEqualTo(CommunityPostModerationAction.RESTORE);
        assertThat(events.get(0).createdAt()).isEqualTo(t1);
        assertThat(events.get(1).createdAt()).isEqualTo(t2);
        assertThat(events.get(2).createdAt()).isEqualTo(t3);
    }

    @Test
    @DisplayName("Moderation history must survive allowed post hard-delete (NO FK cascade dependency)")
    void shouldPreserveModerationHistoryAfterPostHardDelete() {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID moderatorId = UUID.randomUUID();
        Instant t0 = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        Instant t1 = Instant.now().truncatedTo(ChronoUnit.MICROS);

        // 1. Create and persist post
        CommunityPost post = CommunityPost.create(
                postId,
                authorId,
                "Post to be deleted later",
                null,
                CommunityPostStatus.PUBLISHED,
                t0
        );
        postAdapter.save(post);
        assertThat(postAdapter.existsById(postId)).isTrue();

        // 2. Persist moderation event for this post
        CommunityPostModerationEvent event = new CommunityPostModerationEvent(
                UUID.randomUUID(),
                postId,
                CommunityPostModerationAction.APPROVE,
                CommunityPostStatus.PENDING_REVIEW,
                CommunityPostStatus.PUBLISHED,
                moderatorId,
                "Approved",
                t0
        );
        eventAdapter.save(event);

        // 3. Hard-delete the post from database
        postAdapter.deleteById(postId);
        assertThat(postAdapter.existsById(postId)).isFalse();

        // 4. Verify moderation event still exists in database
        List<CommunityPostModerationEvent> events = eventAdapter.findByPostIdOrderByCreatedAtAsc(postId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).postId()).isEqualTo(postId);
        assertThat(events.get(0).action()).isEqualTo(CommunityPostModerationAction.APPROVE);
        assertThat(events.get(0).moderatorUserId()).isEqualTo(moderatorId);

        Integer eventCountInDb = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM community_post_moderation_events WHERE post_id = ?",
                Integer.class,
                postId.toString()
        );
        assertThat(eventCountInDb).isEqualTo(1);
    }
}
