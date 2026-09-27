package com.universe.wiki.infrastructure.persistence.contribution;

import com.universe.test.TestDatabaseSupport;
import com.universe.wiki.application.article.query.contributor.WikiArticlePublicContributorAggregate;
import com.universe.wiki.domain.contribution.WikiContribution;
import com.universe.wiki.domain.contribution.WikiContributionType;
import com.universe.wiki.domain.credit.CreditStatus;
import com.universe.wiki.domain.credit.WikiContributionCredit;
import com.universe.wiki.infrastructure.persistence.credit.WikiContributionCreditPersistenceAdapter;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
        WikiContributionCreditPersistenceAdapter.class,
        WikiArticlePublicContributorQueryAdapter.class
})
@DisplayName("WikiArticlePublicContributorQueryAdapter Integration Tests")
class WikiArticlePublicContributorQueryAdapterIT {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private WikiContributionPersistenceAdapter contributionAdapter;

    @Autowired
    private WikiContributionCreditPersistenceAdapter creditAdapter;

    @Autowired
    private WikiArticlePublicContributorQueryAdapter queryAdapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM wiki_contribution_credits");
        jdbcTemplate.execute("DELETE FROM wiki_contribution_workflow_events");
        jdbcTemplate.execute("DELETE FROM wiki_contribution_sources");
        jdbcTemplate.execute("DELETE FROM wiki_contributions");
    }

    private WikiContribution createContribution(UUID contributionId, UUID articleId, UUID submitterId) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        WikiContribution contribution = WikiContribution.createGeneral(
                contributionId,
                articleId,
                "CHARACTER",
                "Trần Bình An",
                "tran-binh-an",
                1L,
                submitterId,
                WikiContributionType.MISSING_INFORMATION,
                "Nội dung đóng góp thử nghiệm.",
                now
        );
        return contributionAdapter.save(contribution);
    }

    private WikiContributionCredit createActiveCredit(UUID creditId, UUID contributionId) {
        return createActiveCredit(creditId, contributionId, Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    private WikiContributionCredit createActiveCredit(UUID creditId, UUID contributionId, Instant creditedAt) {
        WikiContributionCredit credit = WikiContributionCredit.createActive(
                creditId,
                contributionId,
                UUID.randomUUID(),
                creditedAt != null ? creditedAt : Instant.now().truncatedTo(ChronoUnit.MICROS),
                "Ghi nhận đóng góp."
        );
        return creditAdapter.save(credit);
    }

    private WikiContributionCredit createRevokedCredit(UUID creditId, UUID contributionId) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        WikiContributionCredit credit = WikiContributionCredit.createActive(
                creditId,
                contributionId,
                UUID.randomUUID(),
                now.minus(1, ChronoUnit.HOURS),
                "Ghi nhận ban đầu."
        );
        credit.revoke(UUID.randomUUID(), "Thu hồi do trùng lặp", now);
        return creditAdapter.save(credit);
    }

    @Test
    @DisplayName("ACTIVE credit appears in query result")
    void shouldIncludeActiveCredit() {
        UUID articleId = UUID.randomUUID();
        UUID userA = UUID.randomUUID();
        UUID contributionId = UUID.randomUUID();

        createContribution(contributionId, articleId, userA);
        createActiveCredit(UUID.randomUUID(), contributionId);

        List<WikiArticlePublicContributorAggregate> results = queryAdapter.findActiveContributorsByArticleId(articleId);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).contributorUserId()).isEqualTo(userA);
        assertThat(results.get(0).activeCreditCount()).isEqualTo(1L);
        assertThat(results.get(0).lastCreditedAt()).isNotNull();
    }

    @Test
    @DisplayName("REVOKED credit is excluded from query result")
    void shouldExcludeRevokedCredit() {
        UUID articleId = UUID.randomUUID();
        UUID userA = UUID.randomUUID();
        UUID contributionId = UUID.randomUUID();

        createContribution(contributionId, articleId, userA);
        createRevokedCredit(UUID.randomUUID(), contributionId);

        List<WikiArticlePublicContributorAggregate> results = queryAdapter.findActiveContributorsByArticleId(articleId);

        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("Uncredited contribution (RESOLVED or other status) is excluded")
    void shouldExcludeUncreditedContribution() {
        UUID articleId = UUID.randomUUID();
        UUID userA = UUID.randomUUID();
        UUID contributionId = UUID.randomUUID();

        createContribution(contributionId, articleId, userA);
        // No credit row created

        List<WikiArticlePublicContributorAggregate> results = queryAdapter.findActiveContributorsByArticleId(articleId);

        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("Two ACTIVE credited contributions from same submitter on same article aggregate to 1 row with count = 2")
    void shouldAggregateMultipleActiveCreditsForSameSubmitter() {
        UUID articleId = UUID.randomUUID();
        UUID userA = UUID.randomUUID();

        UUID c1 = UUID.randomUUID();
        UUID c2 = UUID.randomUUID();
        createContribution(c1, articleId, userA);
        createContribution(c2, articleId, userA);

        Instant t1 = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        Instant t2 = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);

        createActiveCredit(UUID.randomUUID(), c1, t1);
        createActiveCredit(UUID.randomUUID(), c2, t2);

        List<WikiArticlePublicContributorAggregate> results = queryAdapter.findActiveContributorsByArticleId(articleId);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).contributorUserId()).isEqualTo(userA);
        assertThat(results.get(0).activeCreditCount()).isEqualTo(2L);
        assertThat(results.get(0).lastCreditedAt()).isEqualTo(t2);
    }

    @Test
    @DisplayName("Different contributors remain separate and credit on another article is excluded")
    void shouldIsolateDifferentContributorsAndArticles() {
        UUID article1 = UUID.randomUUID();
        UUID article2 = UUID.randomUUID();

        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        UUID userC = UUID.randomUUID();

        UUID c1 = UUID.randomUUID();
        UUID c2 = UUID.randomUUID();
        UUID c3 = UUID.randomUUID();

        createContribution(c1, article1, userA);
        createContribution(c2, article1, userB);
        createContribution(c3, article2, userC); // different article

        createActiveCredit(UUID.randomUUID(), c1);
        createActiveCredit(UUID.randomUUID(), c2);
        createActiveCredit(UUID.randomUUID(), c3);

        List<WikiArticlePublicContributorAggregate> results = queryAdapter.findActiveContributorsByArticleId(article1);

        assertThat(results).hasSize(2);
        List<UUID> contributorIds = results.stream().map(WikiArticlePublicContributorAggregate::contributorUserId).toList();
        assertThat(contributorIds).containsExactlyInAnyOrder(userA, userB);
        assertThat(contributorIds).doesNotContain(userC);
    }

    @Test
    @DisplayName("Results are ordered by lastCreditedAt DESC, then by contributor user ID ascending")
    void shouldOrderByLastCreditedAtDescThenContributorUserIdAscending() {
        UUID articleId = UUID.randomUUID();
        UUID user1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID user2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID user3 = UUID.fromString("00000000-0000-0000-0000-000000000003");

        UUID c1 = UUID.randomUUID();
        UUID c2 = UUID.randomUUID();
        UUID c3 = UUID.randomUUID();

        createContribution(c1, articleId, user1);
        createContribution(c2, articleId, user2);
        createContribution(c3, articleId, user3);

        Instant base = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant t1 = base.minus(3, ChronoUnit.HOURS);
        Instant t2 = base.minus(1, ChronoUnit.HOURS);
        Instant t3 = base.minus(2, ChronoUnit.HOURS);

        createActiveCredit(UUID.randomUUID(), c1, t1);
        createActiveCredit(UUID.randomUUID(), c2, t2);
        createActiveCredit(UUID.randomUUID(), c3, t3);

        List<WikiArticlePublicContributorAggregate> results = queryAdapter.findActiveContributorsByArticleId(articleId);

        assertThat(results).hasSize(3);
        // Recency order: t2 (user2, 1h ago) > t3 (user3, 2h ago) > t1 (user1, 3h ago)
        assertThat(results.get(0).contributorUserId()).isEqualTo(user2);
        assertThat(results.get(1).contributorUserId()).isEqualTo(user3);
        assertThat(results.get(2).contributorUserId()).isEqualTo(user1);
    }

    @Test
    @DisplayName("Database-side hard limit of 51 candidates is enforced when more than 51 distinct contributors exist")
    void shouldEnforceCandidateHardLimitOf51() {
        UUID articleId = UUID.randomUUID();
        List<UUID> userIds = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            UUID userId = UUID.randomUUID();
            userIds.add(userId);
            UUID contributionId = UUID.randomUUID();
            createContribution(contributionId, articleId, userId);
            createActiveCredit(UUID.randomUUID(), contributionId);
        }

        List<WikiArticlePublicContributorAggregate> results = queryAdapter.findActiveContributorsByArticleId(articleId);

        assertThat(results).hasSize(51);
    }
}
