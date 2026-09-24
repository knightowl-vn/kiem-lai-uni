package com.universe.wiki.infrastructure.persistence.orphan;

import com.universe.test.TestDatabaseSupport;
import com.universe.wiki.application.ports.WikiCoverOrphanRecord;
import com.universe.wiki.domain.orphan.WikiCoverOrphanStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
        WikiCoverOrphanPersistenceAdapter.class
})
@DisplayName("Wiki Cover Orphan Persistence Adapter Integration Tests")
class WikiCoverOrphanPersistenceAdapterIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private SpringDataWikiCoverOrphanJpaRepository jpaRepository;

    @Autowired
    private WikiCoverOrphanPersistenceAdapter adapter;

    private final List<UUID> createdAssetIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID id : createdAssetIds) {
            jpaRepository.deleteById(id.toString());
        }
        createdAssetIds.clear();
    }

    private UUID createTrackedAssetId() {
        UUID id = UUID.randomUUID();
        createdAssetIds.add(id);
        return id;
    }

    // =========================================================================
    // 1. OBSERVATION
    // =========================================================================

    @Test
    @DisplayName("recordOrphanObservation inserts new PENDING row with initial epoch state")
    void shouldInsertNewPendingRowOnFirstObservation() {
        UUID assetId = createTrackedAssetId();
        Instant t1 = Instant.parse("2026-09-01T10:00:00Z");

        adapter.recordOrphanObservation(assetId, t1);

        Optional<WikiCoverOrphanRecord> opt = adapter.findByMediaAssetId(assetId);
        assertThat(opt).isPresent();
        WikiCoverOrphanRecord record = opt.get();

        assertThat(record.mediaAssetId()).isEqualTo(assetId);
        assertThat(record.status()).isEqualTo(WikiCoverOrphanStatus.PENDING);
        assertThat(record.firstSeenOrphanAt()).isEqualTo(t1);
        assertThat(record.claimToken()).isNull();
        assertThat(record.lockedAt()).isNull();
        assertThat(record.retryCount()).isEqualTo(0);
        assertThat(record.lastError()).isNull();
        assertThat(record.createdAt()).isEqualTo(t1);
        assertThat(record.updatedAt()).isEqualTo(t1);
    }

    @Test
    @DisplayName("duplicate observation preserves existing epoch, status, firstSeen, retryCount, and timestamps")
    void shouldPreserveExistingEpochOnDuplicateObservation() {
        UUID assetId = createTrackedAssetId();
        Instant t1 = Instant.parse("2026-09-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-09-02T15:00:00Z");

        adapter.recordOrphanObservation(assetId, t1);

        // Simulate worker claim and retry count increment
        UUID claimToken = UUID.randomUUID();
        adapter.claimIfEligible(assetId, t1.plusSeconds(3600), claimToken, t1.plusSeconds(60));
        adapter.releaseClaimForRetry(assetId, claimToken, "transient error", t1.plusSeconds(120));

        WikiCoverOrphanRecord beforeDuplicate = adapter.findByMediaAssetId(assetId).orElseThrow();
        assertThat(beforeDuplicate.retryCount()).isEqualTo(1);
        assertThat(beforeDuplicate.lastError()).isEqualTo("transient error");

        // Duplicate observation with newer timestamp T2
        adapter.recordOrphanObservation(assetId, t2);

        WikiCoverOrphanRecord afterDuplicate = adapter.findByMediaAssetId(assetId).orElseThrow();
        assertThat(afterDuplicate.firstSeenOrphanAt()).isEqualTo(t1); // NOT reset to T2!
        assertThat(afterDuplicate.status()).isEqualTo(WikiCoverOrphanStatus.PENDING);
        assertThat(afterDuplicate.retryCount()).isEqualTo(1);
        assertThat(afterDuplicate.lastError()).isEqualTo("transient error");
    }

    @Test
    @DisplayName("duplicate observation does not reset PROCESSING state or clear claim token")
    void shouldNotResetProcessingStateOnDuplicateObservation() {
        UUID assetId = createTrackedAssetId();
        Instant t1 = Instant.parse("2026-09-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-09-02T15:00:00Z");
        UUID token = UUID.randomUUID();

        adapter.recordOrphanObservation(assetId, t1);
        boolean claimed = adapter.claimIfEligible(assetId, t1, token, t1.plusSeconds(60));
        assertThat(claimed).isTrue();

        // Duplicate observation while PROCESSING
        adapter.recordOrphanObservation(assetId, t2);

        WikiCoverOrphanRecord record = adapter.findByMediaAssetId(assetId).orElseThrow();
        assertThat(record.status()).isEqualTo(WikiCoverOrphanStatus.PROCESSING);
        assertThat(record.claimToken()).isEqualTo(token);
        assertThat(record.firstSeenOrphanAt()).isEqualTo(t1);
    }

    // =========================================================================
    // 2. RE-REFERENCE INVALIDATION
    // =========================================================================

    @Test
    @DisplayName("deleteByMediaAssetId removes existing orphan epoch and is no-op when absent")
    void shouldDeleteExistingEpochAndBeNoOpWhenAbsent() {
        UUID assetId = createTrackedAssetId();
        Instant t1 = Instant.parse("2026-09-01T10:00:00Z");

        adapter.recordOrphanObservation(assetId, t1);
        assertThat(adapter.findByMediaAssetId(assetId)).isPresent();

        boolean deleted = adapter.deleteByMediaAssetId(assetId);
        assertThat(deleted).isTrue();
        assertThat(adapter.findByMediaAssetId(assetId)).isEmpty();

        // Second delete is no-op
        boolean secondDelete = adapter.deleteByMediaAssetId(assetId);
        assertThat(secondDelete).isFalse();
    }

    // =========================================================================
    // 3. ELIGIBILITY QUERY
    // =========================================================================

    @Test
    @DisplayName("findEligiblePendingCandidates returns deterministic bounded batch filtered by grace cutoff")
    void shouldFindEligiblePendingCandidatesDeterministically() {
        UUID asset1 = createTrackedAssetId();
        UUID asset2 = createTrackedAssetId();
        UUID asset3 = createTrackedAssetId();
        UUID asset4 = createTrackedAssetId();

        Instant t0 = Instant.parse("2026-08-01T00:00:00Z");
        Instant t1 = Instant.parse("2026-08-02T00:00:00Z");
        Instant t2 = Instant.parse("2026-08-03T00:00:00Z");
        Instant t3 = Instant.parse("2026-08-10T00:00:00Z"); // newer than cutoff

        adapter.recordOrphanObservation(asset1, t0);
        adapter.recordOrphanObservation(asset2, t1);
        adapter.recordOrphanObservation(asset3, t2);
        adapter.recordOrphanObservation(asset4, t3);

        // Claim asset2 so it is in PROCESSING
        adapter.claimIfEligible(asset2, t2, UUID.randomUUID(), Instant.now());

        Instant graceCutoff = Instant.parse("2026-08-05T00:00:00Z");

        // Eligible: asset1 (PENDING, t0), asset3 (PENDING, t2). asset2 is PROCESSING (excluded), asset4 > cutoff (excluded)
        List<WikiCoverOrphanRecord> eligible = adapter.findEligiblePendingCandidates(graceCutoff, 10);

        List<UUID> returnedIds = eligible.stream().map(WikiCoverOrphanRecord::mediaAssetId).toList();
        assertThat(returnedIds).contains(asset1, asset3);
        assertThat(returnedIds).doesNotContain(asset2, asset4);

        // Bounded limit test
        List<WikiCoverOrphanRecord> limited = adapter.findEligiblePendingCandidates(graceCutoff, 1);
        assertThat(limited).hasSize(1);
    }

    // =========================================================================
    // 4. ATOMIC CLAIM
    // =========================================================================

    @Test
    @DisplayName("claimIfEligible atomically transitions eligible PENDING to PROCESSING and rejects ineligible")
    void shouldClaimEligibleOrphanAndRejectIneligible() {
        UUID assetId = createTrackedAssetId();
        Instant t1 = Instant.parse("2026-09-01T10:00:00Z");
        Instant cutoff = Instant.parse("2026-09-01T12:00:00Z");
        Instant now = Instant.parse("2026-09-01T12:05:00Z");
        UUID token = UUID.randomUUID();

        adapter.recordOrphanObservation(assetId, t1);

        // 1. Claim with too-young cutoff fails
        boolean tooYoungClaim = adapter.claimIfEligible(assetId, t1.minusSeconds(1), token, now);
        assertThat(tooYoungClaim).isFalse();
        assertThat(adapter.findByMediaAssetId(assetId).get().status()).isEqualTo(WikiCoverOrphanStatus.PENDING);

        // 2. Eligible claim succeeds
        boolean claimSuccess = adapter.claimIfEligible(assetId, cutoff, token, now);
        assertThat(claimSuccess).isTrue();

        WikiCoverOrphanRecord claimed = adapter.findByMediaAssetId(assetId).orElseThrow();
        assertThat(claimed.status()).isEqualTo(WikiCoverOrphanStatus.PROCESSING);
        assertThat(claimed.claimToken()).isEqualTo(token);
        assertThat(claimed.lockedAt()).isEqualTo(now);
        assertThat(claimed.updatedAt()).isEqualTo(now);

        // 3. Second claim while already PROCESSING fails
        UUID secondToken = UUID.randomUUID();
        boolean secondClaim = adapter.claimIfEligible(assetId, cutoff, secondToken, now.plusSeconds(10));
        assertThat(secondClaim).isFalse();

        // 4. Missing asset claim fails
        boolean missingClaim = adapter.claimIfEligible(UUID.randomUUID(), cutoff, token, now);
        assertThat(missingClaim).isFalse();
    }

    // =========================================================================
    // 5. CLAIM OWNERSHIP VERIFICATION
    // =========================================================================

    @Test
    @DisplayName("isClaimOwned validates exact owner token under PROCESSING and rejects others")
    void shouldVerifyClaimOwnershipCorrectly() {
        UUID assetId = createTrackedAssetId();
        Instant t1 = Instant.parse("2026-09-01T10:00:00Z");
        UUID token = UUID.randomUUID();
        UUID otherToken = UUID.randomUUID();

        adapter.recordOrphanObservation(assetId, t1);

        // When PENDING: not owned by any token
        assertThat(adapter.isClaimOwned(assetId, token)).isFalse();

        // Claim
        adapter.claimIfEligible(assetId, t1, token, Instant.now());

        // When PROCESSING: owned by token, not by otherToken
        assertThat(adapter.isClaimOwned(assetId, token)).isTrue();
        assertThat(adapter.isClaimOwned(assetId, otherToken)).isFalse();

        // Missing asset is false
        assertThat(adapter.isClaimOwned(UUID.randomUUID(), token)).isFalse();
    }

    // =========================================================================
    // 6. CONDITIONAL SUCCESS CLEANUP
    // =========================================================================

    @Test
    @DisplayName("deleteClaimedEpoch only deletes when token matches active PROCESSING epoch")
    void shouldDeleteClaimedEpochOnlyForMatchingToken() {
        UUID assetId = createTrackedAssetId();
        Instant t1 = Instant.parse("2026-09-01T10:00:00Z");
        UUID token = UUID.randomUUID();
        UUID wrongToken = UUID.randomUUID();

        adapter.recordOrphanObservation(assetId, t1);
        adapter.claimIfEligible(assetId, t1, token, Instant.now());

        // Wrong token cannot delete
        boolean wrongDelete = adapter.deleteClaimedEpoch(assetId, wrongToken);
        assertThat(wrongDelete).isFalse();
        assertThat(adapter.findByMediaAssetId(assetId)).isPresent();

        // Exact token deletes
        boolean rightDelete = adapter.deleteClaimedEpoch(assetId, token);
        assertThat(rightDelete).isTrue();
        assertThat(adapter.findByMediaAssetId(assetId)).isEmpty();
    }

    // =========================================================================
    // 7. TRANSIENT FAILURE RELEASE & SAFE ERROR TRUNCATION
    // =========================================================================

    @Test
    @DisplayName("releaseClaimForRetry resets to PENDING, increments retryCount, preserves firstSeen, and safely truncates error")
    void shouldReleaseClaimForRetrySafely() {
        UUID assetId = createTrackedAssetId();
        Instant t1 = Instant.parse("2026-09-01T10:00:00Z");
        UUID token = UUID.randomUUID();
        UUID wrongToken = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-01T11:00:00Z");

        adapter.recordOrphanObservation(assetId, t1);
        adapter.claimIfEligible(assetId, t1, token, t1);

        // Wrong token release fails
        boolean wrongRelease = adapter.releaseClaimForRetry(assetId, wrongToken, "error", now);
        assertThat(wrongRelease).isFalse();

        // Long error text (> 500 chars) to test deterministic truncation policy
        String hugeError = "E".repeat(1500);

        boolean rightRelease = adapter.releaseClaimForRetry(assetId, token, hugeError, now);
        assertThat(rightRelease).isTrue();

        WikiCoverOrphanRecord record = adapter.findByMediaAssetId(assetId).orElseThrow();
        assertThat(record.status()).isEqualTo(WikiCoverOrphanStatus.PENDING);
        assertThat(record.retryCount()).isEqualTo(1);
        assertThat(record.firstSeenOrphanAt()).isEqualTo(t1);
        assertThat(record.claimToken()).isNull();
        assertThat(record.lockedAt()).isNull();
        assertThat(record.updatedAt()).isEqualTo(now);
        assertThat(record.lastError()).hasSize(WikiCoverOrphanPersistenceAdapter.MAX_ERROR_LENGTH);
    }

    // =========================================================================
    // 8. STALE LEASE RECOVERY
    // =========================================================================

    @Test
    @DisplayName("recoverStaleProcessing recovers expired leases while preserving unexpired leases and firstSeen")
    void shouldRecoverStaleProcessingLeases() {
        UUID staleAsset = createTrackedAssetId();
        UUID freshAsset = createTrackedAssetId();

        Instant t0 = Instant.parse("2026-09-01T10:00:00Z");
        adapter.recordOrphanObservation(staleAsset, t0);
        adapter.recordOrphanObservation(freshAsset, t0);

        UUID staleToken = UUID.randomUUID();
        UUID freshToken = UUID.randomUUID();

        Instant staleLockedAt = Instant.parse("2026-09-01T10:05:00Z");
        Instant freshLockedAt = Instant.parse("2026-09-01T10:55:00Z");

        adapter.claimIfEligible(staleAsset, t0, staleToken, staleLockedAt);
        adapter.claimIfEligible(freshAsset, t0, freshToken, freshLockedAt);

        Instant leaseCutoff = Instant.parse("2026-09-01T10:30:00Z");
        Instant now = Instant.parse("2026-09-01T11:00:00Z");

        int recovered = adapter.recoverStaleProcessing(leaseCutoff, now);
        assertThat(recovered).isEqualTo(1);

        // Stale row returned to PENDING
        WikiCoverOrphanRecord recoveredRecord = adapter.findByMediaAssetId(staleAsset).orElseThrow();
        assertThat(recoveredRecord.status()).isEqualTo(WikiCoverOrphanStatus.PENDING);
        assertThat(recoveredRecord.claimToken()).isNull();
        assertThat(recoveredRecord.lockedAt()).isNull();
        assertThat(recoveredRecord.firstSeenOrphanAt()).isEqualTo(t0);
        assertThat(recoveredRecord.updatedAt()).isEqualTo(now);

        // Fresh row remains PROCESSING with its token
        WikiCoverOrphanRecord freshRecord = adapter.findByMediaAssetId(freshAsset).orElseThrow();
        assertThat(freshRecord.status()).isEqualTo(WikiCoverOrphanStatus.PROCESSING);
        assertThat(freshRecord.claimToken()).isEqualTo(freshToken);
    }

    // =========================================================================
    // 9. STALE EPOCH RACE — MANDATORY TEST (Section 18)
    // =========================================================================

    @Test
    @DisplayName("Section 18: Stale epoch race - old worker token cannot own or delete re-created O2 epoch")
    void shouldProtectAgainstStaleEpochRace() {
        UUID assetId = createTrackedAssetId();
        Instant t1 = Instant.parse("2026-09-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-09-10T15:00:00Z");
        UUID tokenX = UUID.randomUUID();

        // 1. O1 created at T1
        adapter.recordOrphanObservation(assetId, t1);

        // 2. Worker A claims O1 using token X
        boolean claimed = adapter.claimIfEligible(assetId, t1, tokenX, t1.plusSeconds(30));
        assertThat(claimed).isTrue();

        // 3. Asset becomes referenced again: deleteByMediaAssetId(assetId)
        boolean invalidated = adapter.deleteByMediaAssetId(assetId);
        assertThat(invalidated).isTrue();

        // 4. Asset becomes orphan again: recordOrphanObservation(assetId, T2)
        adapter.recordOrphanObservation(assetId, t2);

        // 5. O2 exists: first_seen_orphan_at = T2, status = PENDING, claim_token = NULL
        WikiCoverOrphanRecord o2 = adapter.findByMediaAssetId(assetId).orElseThrow();
        assertThat(o2.firstSeenOrphanAt()).isEqualTo(t2);
        assertThat(o2.status()).isEqualTo(WikiCoverOrphanStatus.PENDING);
        assertThat(o2.claimToken()).isNull();

        // 6. Old Worker A calls: isClaimOwned(assetId, X) -> false
        assertThat(adapter.isClaimOwned(assetId, tokenX)).isFalse();

        // 7. Old Worker A calls: deleteClaimedEpoch(assetId, X) -> false
        assertThat(adapter.deleteClaimedEpoch(assetId, tokenX)).isFalse();

        // 8. O2 remains intact with first_seen_orphan_at = T2
        WikiCoverOrphanRecord o2AfterStaleAttempt = adapter.findByMediaAssetId(assetId).orElseThrow();
        assertThat(o2AfterStaleAttempt.firstSeenOrphanAt()).isEqualTo(t2);
        assertThat(o2AfterStaleAttempt.status()).isEqualTo(WikiCoverOrphanStatus.PENDING);
    }

    // =========================================================================
    // 10. STALE LEASE TOKEN INVALIDATION TEST (Section 19)
    // =========================================================================

    @Test
    @DisplayName("Section 19: Stale lease token invalidation allows new worker to claim recovered epoch")
    void shouldInvalidateStaleLeaseAndAllowNewWorkerClaim() {
        UUID assetId = createTrackedAssetId();
        Instant t1 = Instant.parse("2026-09-01T10:00:00Z");
        UUID tokenX = UUID.randomUUID();
        UUID tokenY = UUID.randomUUID();

        // 1. O1 claimed with token X
        adapter.recordOrphanObservation(assetId, t1);
        adapter.claimIfEligible(assetId, t1, tokenX, Instant.parse("2026-09-01T10:05:00Z"));

        // 2. locked_at becomes stale relative to lease cutoff
        Instant leaseCutoff = Instant.parse("2026-09-01T10:30:00Z");
        Instant now = Instant.parse("2026-09-01T10:35:00Z");

        // 3. stale lease recovery runs
        int recovered = adapter.recoverStaleProcessing(leaseCutoff, now);
        assertThat(recovered).isEqualTo(1);

        // 4. row becomes PENDING, 5. claim_token becomes NULL
        WikiCoverOrphanRecord recoveredRecord = adapter.findByMediaAssetId(assetId).orElseThrow();
        assertThat(recoveredRecord.status()).isEqualTo(WikiCoverOrphanStatus.PENDING);
        assertThat(recoveredRecord.claimToken()).isNull();

        // 6. Worker X is no longer owner
        assertThat(adapter.isClaimOwned(assetId, tokenX)).isFalse();

        // 7. A new worker can claim the same epoch with token Y
        boolean claimedByY = adapter.claimIfEligible(assetId, t1, tokenY, now.plusSeconds(10));
        assertThat(claimedByY).isTrue();

        WikiCoverOrphanRecord recordY = adapter.findByMediaAssetId(assetId).orElseThrow();
        assertThat(recordY.status()).isEqualTo(WikiCoverOrphanStatus.PROCESSING);
        assertThat(recordY.claimToken()).isEqualTo(tokenY);
        assertThat(recordY.firstSeenOrphanAt()).isEqualTo(t1); // first_seen_orphan_at must remain unchanged
    }

    // =========================================================================
    // 11. RETRY CONTINUITY TEST (Section 20)
    // =========================================================================

    @Test
    @DisplayName("Section 20: Retry continuity test repeating claim and failure release at least 15 times")
    void shouldSupportAtLeast15RetryCyclesContinuously() {
        UUID assetId = createTrackedAssetId();
        Instant t1 = Instant.parse("2026-09-01T10:00:00Z");

        adapter.recordOrphanObservation(assetId, t1);

        Instant clock = t1;
        for (int cycle = 1; cycle <= 15; cycle++) {
            UUID token = UUID.randomUUID();
            clock = clock.plusSeconds(60);

            // 1. Claim
            boolean claimed = adapter.claimIfEligible(assetId, t1, token, clock);
            assertThat(claimed).as("Claim at cycle %d", cycle).isTrue();

            clock = clock.plusSeconds(30);

            // 2. Release failure
            boolean released = adapter.releaseClaimForRetry(assetId, token, "Failure at cycle " + cycle, clock);
            assertThat(released).as("Release at cycle %d", cycle).isTrue();

            // 3. Verify state
            WikiCoverOrphanRecord current = adapter.findByMediaAssetId(assetId).orElseThrow();
            assertThat(current.status()).isEqualTo(WikiCoverOrphanStatus.PENDING);
            assertThat(current.retryCount()).isEqualTo(cycle);
            assertThat(current.firstSeenOrphanAt()).isEqualTo(t1);
            assertThat(current.claimToken()).isNull();
            assertThat(current.lockedAt()).isNull();
        }

        // Final verification: row remains claimable on cycle 16
        UUID nextToken = UUID.randomUUID();
        boolean finalClaim = adapter.claimIfEligible(assetId, t1, nextToken, clock.plusSeconds(60));
        assertThat(finalClaim).isTrue();
    }
}
