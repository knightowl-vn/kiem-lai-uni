package com.universe.interaction.application;

import com.universe.interaction.application.query.CommunityPostEngagementCountsDTO;
import com.universe.interaction.application.query.GetCommunityPostEngagementCountsUseCase;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.domain.reaction.Reaction;
import com.universe.interaction.domain.reaction.ReactionTarget;
import com.universe.interaction.domain.reaction.ReactionType;
import com.universe.interaction.infrastructure.persistence.CommentPersistenceAdapter;
import com.universe.interaction.infrastructure.persistence.CommentPersistenceMapper;
import com.universe.interaction.infrastructure.persistence.reaction.ReactionPersistenceAdapter;
import com.universe.interaction.infrastructure.persistence.reaction.ReactionPersistenceMapper;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
        CommentPersistenceAdapter.class,
        CommentPersistenceMapper.class,
        ReactionPersistenceAdapter.class,
        ReactionPersistenceMapper.class,
        GetCommunityPostEngagementCountsUseCase.class
})
@DisplayName("CommunityPostEngagementCountsMySQLTest — Batch Engagement Counts on MySQL")
class CommunityPostEngagementCountsMySQLTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.resetTestDatabase("kiemlai_test");
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private CommentPersistenceAdapter commentAdapter;

    @Autowired
    private ReactionPersistenceAdapter reactionAdapter;

    @Autowired
    private GetCommunityPostEngagementCountsUseCase engagementUseCase;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DELETE FROM interaction_reactions");
        jdbcTemplate.execute("DELETE FROM interaction_comment_revisions");
        jdbcTemplate.execute("DELETE FROM interaction_comments");
    }

    @Test
    @DisplayName("Should accurately compute batch comment and reaction counts without calculating engagement score")
    void shouldComputeBatchEngagementCounts() {
        UUID post1 = UUID.randomUUID();
        UUID post2 = UUID.randomUUID();
        UUID post3 = UUID.randomUUID();
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        UUID userC = UUID.randomUUID();
        Instant now = Instant.now();

        // Post 1: 2 root comments + 1 reply = 3 comments; 2 reactions
        tx.executeWithoutResult(s -> {
            Comment root1 = Comment.createRoot(UUID.randomUUID(), CommentTarget.communityPost(post1), userA, "P1 Comment 1", now);
            Comment root2 = Comment.createRoot(UUID.randomUUID(), CommentTarget.communityPost(post1), userB, "P1 Comment 2", now);
            commentAdapter.save(root1);
            commentAdapter.save(root2);

            Comment reply1 = Comment.createReply(UUID.randomUUID(), root1, userC, "P1 Reply 1", now.plusSeconds(1));
            commentAdapter.save(reply1);

            reactionAdapter.save(Reaction.create(UUID.randomUUID(), userA, ReactionTarget.communityPost(post1), ReactionType.LIKE, now));
            reactionAdapter.save(Reaction.create(UUID.randomUUID(), userB, ReactionTarget.communityPost(post1), ReactionType.FIRE, now));
        });

        // Post 2: 0 comments, 3 reactions
        tx.executeWithoutResult(s -> {
            reactionAdapter.save(Reaction.create(UUID.randomUUID(), userA, ReactionTarget.communityPost(post2), ReactionType.LOVE, now));
            reactionAdapter.save(Reaction.create(UUID.randomUUID(), userB, ReactionTarget.communityPost(post2), ReactionType.HAHA, now));
            reactionAdapter.save(Reaction.create(UUID.randomUUID(), userC, ReactionTarget.communityPost(post2), ReactionType.SAD, now));
        });

        // Post 3: 0 comments, 0 reactions (idle post)

        // Query engagement counts for all 3 posts
        Map<UUID, CommunityPostEngagementCountsDTO> results = engagementUseCase.getEngagementCountsForPosts(List.of(post1, post2, post3));

        assertThat(results).hasSize(3);

        CommunityPostEngagementCountsDTO dto1 = results.get(post1);
        assertThat(dto1).isNotNull();
        assertThat(dto1.postId()).isEqualTo(post1);
        assertThat(dto1.commentCount()).isEqualTo(3L);
        assertThat(dto1.reactionCount()).isEqualTo(2L);

        CommunityPostEngagementCountsDTO dto2 = results.get(post2);
        assertThat(dto2).isNotNull();
        assertThat(dto2.postId()).isEqualTo(post2);
        assertThat(dto2.commentCount()).isEqualTo(0L);
        assertThat(dto2.reactionCount()).isEqualTo(3L);

        CommunityPostEngagementCountsDTO dto3 = results.get(post3);
        assertThat(dto3).isNotNull();
        assertThat(dto3.postId()).isEqualTo(post3);
        assertThat(dto3.commentCount()).isEqualTo(0L);
        assertThat(dto3.reactionCount()).isEqualTo(0L);
    }
}
