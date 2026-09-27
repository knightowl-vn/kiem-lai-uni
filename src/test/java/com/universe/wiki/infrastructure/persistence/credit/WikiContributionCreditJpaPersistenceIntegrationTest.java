package com.universe.wiki.infrastructure.persistence.credit;

import com.universe.test.TestDatabaseSupport;
import com.universe.wiki.application.exceptions.WikiContributionAlreadyCreditedException;
import com.universe.wiki.application.exceptions.WikiContributionCreditStaleMutationException;
import com.universe.wiki.domain.contribution.WikiContribution;
import com.universe.wiki.domain.contribution.WikiContributionType;
import com.universe.wiki.domain.credit.CreditStatus;
import com.universe.wiki.domain.credit.WikiContributionCredit;
import com.universe.wiki.infrastructure.persistence.contribution.WikiContributionPersistenceAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
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
        WikiContributionCreditPersistenceAdapter.class
})
@DisplayName("WikiContributionCredit JPA Persistence Integration Tests")
class WikiContributionCreditJpaPersistenceIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private WikiContributionPersistenceAdapter contributionAdapter;

    @Autowired
    private WikiContributionCreditPersistenceAdapter creditAdapter;

    @Autowired
    private WikiContributionCreditSpringDataRepository creditRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM wiki_contribution_credits");
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
                "Đóng góp bài viết thử nghiệm cho credit.",
                now
        );
        return contributionAdapter.save(contribution);
    }

    @Test
    @DisplayName("Lưu và truy vấn thành công bản ghi công trạng ACTIVE (save/load mapping)")
    void shouldPersistAndLoadActiveCredit() {
        UUID contributionId = UUID.randomUUID();
        createTestContribution(contributionId);

        UUID creditId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        Instant creditedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        WikiContributionCredit credit = WikiContributionCredit.createActive(
                creditId,
                contributionId,
                adminId,
                creditedAt,
                "Ghi nhận đóng góp xuất sắc."
        );

        creditAdapter.save(credit);

        Optional<WikiContributionCredit> loadedOpt = creditAdapter.findByContributionId(contributionId);
        assertThat(loadedOpt).isPresent();

        WikiContributionCredit loaded = loadedOpt.get();
        assertThat(loaded.getId()).isEqualTo(creditId);
        assertThat(loaded.getContributionId()).isEqualTo(contributionId);
        assertThat(loaded.getStatus()).isEqualTo(CreditStatus.ACTIVE);
        assertThat(loaded.getCreditedByUserId()).isEqualTo(adminId);
        assertThat(loaded.getCreditedAt()).isEqualTo(creditedAt);
        assertThat(loaded.getCreditNote()).isEqualTo("Ghi nhận đóng góp xuất sắc.");
        assertThat(loaded.getRevokedByUserId()).isNull();
        assertThat(loaded.getRevokedAt()).isNull();
        assertThat(loaded.getRevocationReason()).isNull();
    }

    @Test
    @DisplayName("Cập nhật thu hồi công trạng (ACTIVE -> REVOKED) và lưu thành công")
    void shouldPersistRevokedCredit() {
        UUID contributionId = UUID.randomUUID();
        createTestContribution(contributionId);

        UUID creditId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID superAdminId = UUID.randomUUID();
        Instant creditedAt = Instant.now().truncatedTo(ChronoUnit.MICROS).minus(1, ChronoUnit.HOURS);
        Instant revokedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        WikiContributionCredit credit = WikiContributionCredit.createActive(
                creditId,
                contributionId,
                adminId,
                creditedAt,
                "Ghi nhận ban đầu."
        );
        creditAdapter.save(credit);

        credit.revoke(superAdminId, "Phát hiện nội dung có vi phạm bản quyền.", revokedAt);
        creditAdapter.save(credit);

        Optional<WikiContributionCredit> reloadedOpt = creditAdapter.findByContributionId(contributionId);
        assertThat(reloadedOpt).isPresent();

        WikiContributionCredit reloaded = reloadedOpt.get();
        assertThat(reloaded.getStatus()).isEqualTo(CreditStatus.REVOKED);
        assertThat(reloaded.getRevokedByUserId()).isEqualTo(superAdminId);
        assertThat(reloaded.getRevokedAt()).isEqualTo(revokedAt);
        assertThat(reloaded.getRevocationReason()).isEqualTo("Phát hiện nội dung có vi phạm bản quyền.");
    }

    @Test
    @DisplayName("Ràng buộc khóa ngoại: Ghi nhận công trạng với contributionId không tồn tại sẽ bị từ chối")
    void shouldEnforceContributionForeignKeyConstraint() {
        UUID nonExistentContributionId = UUID.randomUUID();
        WikiContributionCredit credit = WikiContributionCredit.createActive(
                UUID.randomUUID(),
                nonExistentContributionId,
                UUID.randomUUID(),
                Instant.now(),
                "Note"
        );

        assertThatThrownBy(() -> creditAdapter.save(credit))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Ràng buộc UNIQUE(contribution_id): Hai lần ghi nhận cho cùng một đóng góp sẽ bị từ chối và ném WikiContributionAlreadyCreditedException")
    void shouldEnforceUniqueContributionIdConstraint() {
        UUID contributionId = UUID.randomUUID();
        createTestContribution(contributionId);

        WikiContributionCredit credit1 = WikiContributionCredit.createActive(
                UUID.randomUUID(),
                contributionId,
                UUID.randomUUID(),
                Instant.now(),
                "First credit"
        );
        creditAdapter.save(credit1);

        WikiContributionCredit credit2 = WikiContributionCredit.createActive(
                UUID.randomUUID(),
                contributionId,
                UUID.randomUUID(),
                Instant.now(),
                "Second credit"
        );

        assertThatThrownBy(() -> creditAdapter.save(credit2))
                .isInstanceOf(WikiContributionAlreadyCreditedException.class);

        // Verify exactly one credit row in database
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wiki_contribution_credits WHERE contribution_id = ?",
                Integer.class,
                contributionId.toString()
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("Ràng buộc CHECK consistency: Database từ chối trạng thái không hợp lệ")
    void shouldEnforceRevocationConsistencyCheckConstraint() {
        UUID contributionId = UUID.randomUUID();
        createTestContribution(contributionId);

        // Case 1: Status ACTIVE but has revocation metadata -> should fail
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO wiki_contribution_credits (id, contribution_id, credit_status, credited_by_user_id, credited_at, revoked_by_user_id, revoked_at, revocation_reason, persistence_version) " +
                        "VALUES (?, ?, 'ACTIVE', ?, NOW(), ?, NOW(), 'Lý do', 0)",
                UUID.randomUUID().toString(),
                contributionId.toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString()
        )).hasMessageContaining("chk_wiki_contribution_credits_status_consistency");

        // Case 2: Status REVOKED but missing revocation metadata -> should fail
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO wiki_contribution_credits (id, contribution_id, credit_status, credited_by_user_id, credited_at, revoked_by_user_id, revoked_at, revocation_reason, persistence_version) " +
                        "VALUES (?, ?, 'REVOKED', ?, NOW(), NULL, NULL, NULL, 0)",
                UUID.randomUUID().toString(),
                contributionId.toString(),
                UUID.randomUUID().toString()
        )).hasMessageContaining("chk_wiki_contribution_credits_status_consistency");
    }

    @Test
    @DisplayName("Khóa lạc quan JPA @Version: Hibernate phát hiện stale detached entity và ném OptimisticLockingFailureException")
    void shouldDetectOptimisticLockConflictOnConcurrentUpdate() {
        UUID contributionId = UUID.randomUUID();
        createTestContribution(contributionId);

        UUID creditId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        WikiContributionCredit credit = WikiContributionCredit.createActive(
                creditId,
                contributionId,
                UUID.randomUUID(),
                now,
                "Ban đầu"
        );
        creditAdapter.save(credit);

        // Fetch two separate detached entity instances
        WikiContributionCreditJpaEntity staleSnapshot = creditRepository.findById(creditId.toString()).orElseThrow();
        WikiContributionCreditJpaEntity winnerSnapshot = creditRepository.findById(creditId.toString()).orElseThrow();

        assertThat(staleSnapshot.getPersistenceVersion()).isEqualTo(0L);
        assertThat(winnerSnapshot.getPersistenceVersion()).isEqualTo(0L);

        // Winner updates and flushes first -> persistenceVersion increments to 1
        winnerSnapshot.setCreditStatus("REVOKED");
        winnerSnapshot.setRevokedByUserId(UUID.randomUUID().toString());
        winnerSnapshot.setRevokedAt(Instant.now());
        winnerSnapshot.setRevocationReason("Thu hồi hợp lệ bởi Winner");

        WikiContributionCreditJpaEntity savedWinner = creditRepository.saveAndFlush(winnerSnapshot);
        assertThat(savedWinner.getPersistenceVersion()).isGreaterThan(0L);

        // Stale snapshot (carrying version 0) tries to update
        staleSnapshot.setCreditNote("Ghi đè bởi Stale");

        assertThatThrownBy(() -> creditRepository.saveAndFlush(staleSnapshot))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    @DisplayName("Khóa lạc quan qua Adapter: Transaction A tải entity (version 0), Transaction B (REQUIRES_NEW) thu hồi trước, Transaction A cố lưu sẽ bị ném WikiContributionCreditStaleMutationException")
    void shouldDetectOptimisticLockConflictThroughRealAdapterPath() {
        // 1. Seed one ACTIVE WikiContributionCredit with persistence_version = 0
        UUID contributionId = UUID.randomUUID();
        createTestContribution(contributionId);

        UUID creditId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID superAdminId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        WikiContributionCredit initialCredit = WikiContributionCredit.createActive(
                creditId,
                contributionId,
                adminId,
                now,
                "Ban đầu"
        );
        creditAdapter.save(initialCredit);

        Long versionInitial = jdbcTemplate.queryForObject(
                "SELECT persistence_version FROM wiki_contribution_credits WHERE id = ?",
                Long.class,
                creditId.toString()
        );
        assertThat(versionInitial).isEqualTo(0L);

        TransactionTemplate txA = new TransactionTemplate(transactionManager);
        txA.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        TransactionTemplate txB = new TransactionTemplate(transactionManager);
        txB.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // 2 & 3. Start transaction A, load credit through REAL adapter (loads entity into Tx A's L1 cache)
        assertThatThrownBy(() -> {
            txA.execute(statusA -> {
                WikiContributionCredit staleDomain = creditAdapter.findByContributionId(contributionId).orElseThrow();

                // 4 & 5 & 6. Suspend Tx A, run independent REQUIRES_NEW transaction B
                txB.execute(statusB -> {
                    WikiContributionCredit winnerDomain = creditAdapter.findByContributionId(contributionId).orElseThrow();
                    winnerDomain.revoke(superAdminId, "Winner transaction revocation", now.plusSeconds(10));
                    creditAdapter.save(winnerDomain);
                    return null;
                });

                // 7. Verify B committed and database persistence_version advanced
                Long versionAfterWinner = jdbcTemplate.queryForObject(
                        "SELECT persistence_version FROM wiki_contribution_credits WHERE id = ? FOR SHARE",
                        Long.class,
                        creditId.toString()
                );
                assertThat(versionAfterWinner).isEqualTo(1L);

                // 8 & 9. Resume Tx A: mutate stale domain aggregate with different reason
                staleDomain.revoke(superAdminId, "Stale transaction revocation", now.plusSeconds(20));

                // 10. Call REAL adapter save inside Tx A -> must fail with optimistic lock exception
                creditAdapter.save(staleDomain);
                return null;
            });
        }).isInstanceOf(WikiContributionCreditStaleMutationException.class);

        // 12. After A rolls back, verify in fresh query
        String finalStatus = jdbcTemplate.queryForObject(
                "SELECT credit_status FROM wiki_contribution_credits WHERE id = ?",
                String.class,
                creditId.toString()
        );
        String finalReason = jdbcTemplate.queryForObject(
                "SELECT revocation_reason FROM wiki_contribution_credits WHERE id = ?",
                String.class,
                creditId.toString()
        );
        Long finalVersion = jdbcTemplate.queryForObject(
                "SELECT persistence_version FROM wiki_contribution_credits WHERE id = ?",
                Long.class,
                creditId.toString()
        );

        assertThat(finalStatus).isEqualTo("REVOKED");
        assertThat(finalReason).isEqualTo("Winner transaction revocation");
        assertThat(finalVersion).isEqualTo(1L);
    }
}
