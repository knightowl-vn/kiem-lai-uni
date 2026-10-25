package com.universe.media.infrastructure.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure offline unit test suite for MediaUploadTestLifecycleCoordinator (MS-08E3-S4-MYSQL-C6).
 *
 * <p>Proves that:
 * <ol>
 *   <li>The 3-state machine (IDLE, RUNNING, QUARANTINED) strictly prevents unsafe resets.</li>
 *   <li>QUARANTINED state cannot be cleared by setup, teardown, settled calls, or subsequent test methods.</li>
 *   <li>Cross-test isolation blocks subsequent test methods if an earlier test was quarantined.</li>
 *   <li>Mock resets and database deletions are completely aborted in RUNNING or QUARANTINED states.</li>
 * </ol>
 */
@DisplayName("MediaUploadTestLifecycleCoordinator Pure Offline State Machine Tests (C6)")
class MediaUploadTestLifecycleCoordinatorTest {

    // =============================================================================================
    // TEST-C6-01: Normal setup
    // =============================================================================================

    @Test
    @DisplayName("TEST-C6-01: When IDLE, resetForNewTest invokes mock reset exactly once and stays IDLE")
    void testC6_01_normalSetupPermittedWhenIdle() {
        MediaUploadTestLifecycleCoordinator coordinator = new MediaUploadTestLifecycleCoordinator();
        assertThat(coordinator.getWorkerState()).isEqualTo(MediaUploadTestLifecycleCoordinator.WorkerState.IDLE);

        AtomicInteger mockResetCount = new AtomicInteger(0);
        coordinator.resetForNewTest(mockResetCount::incrementAndGet);

        assertThat(mockResetCount.get()).isEqualTo(1);
        assertThat(coordinator.getWorkerState()).isEqualTo(MediaUploadTestLifecycleCoordinator.WorkerState.IDLE);
        assertThat(coordinator.areWorkersActive()).isFalse();
        assertThat(coordinator.isQuarantined()).isFalse();
    }

    // =============================================================================================
    // TEST-C6-02: Active worker blocks setup
    // =============================================================================================

    @Test
    @DisplayName("TEST-C6-02: When RUNNING, resetForNewTest throws and does NOT invoke mock reset")
    void testC6_02_runningWorkerBlocksSetup() {
        MediaUploadTestLifecycleCoordinator coordinator = new MediaUploadTestLifecycleCoordinator();
        coordinator.markWorkerStarted();
        assertThat(coordinator.getWorkerState()).isEqualTo(MediaUploadTestLifecycleCoordinator.WorkerState.RUNNING);

        AtomicBoolean mockResetInvoked = new AtomicBoolean(false);
        assertThatThrownBy(() -> coordinator.resetForNewTest(() -> mockResetInvoked.set(true)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("TEST SETUP BLOCKED")
                .hasMessageContaining("state=RUNNING")
                .hasMessageContaining("Shared mock reset is strictly forbidden");

        assertThat(mockResetInvoked.get()).isFalse();
        assertThat(coordinator.getWorkerState()).isEqualTo(MediaUploadTestLifecycleCoordinator.WorkerState.RUNNING);
    }

    // =============================================================================================
    // TEST-C6-03: Quarantine blocks setup
    // =============================================================================================

    @Test
    @DisplayName("TEST-C6-03: When QUARANTINED, resetForNewTest throws and does NOT invoke mock reset")
    void testC6_03_quarantineBlocksSetup() {
        MediaUploadTestLifecycleCoordinator coordinator = new MediaUploadTestLifecycleCoordinator();
        coordinator.markWorkerQuarantined();
        assertThat(coordinator.getWorkerState()).isEqualTo(MediaUploadTestLifecycleCoordinator.WorkerState.QUARANTINED);

        AtomicBoolean mockResetInvoked = new AtomicBoolean(false);
        assertThatThrownBy(() -> coordinator.resetForNewTest(() -> mockResetInvoked.set(true)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("TEST SETUP BLOCKED")
                .hasMessageContaining("state=QUARANTINED")
                .hasMessageContaining("Shared mock reset is strictly forbidden");

        assertThat(mockResetInvoked.get()).isFalse();
        assertThat(coordinator.getWorkerState()).isEqualTo(MediaUploadTestLifecycleCoordinator.WorkerState.QUARANTINED);
    }

    // =============================================================================================
    // TEST-C6-04: Quarantine cannot be cleared
    // =============================================================================================

    @Test
    @DisplayName("TEST-C6-04: Once QUARANTINED, transitions to IDLE or RUNNING are strictly rejected")
    void testC6_04_quarantineCannotBeCleared() {
        MediaUploadTestLifecycleCoordinator coordinator = new MediaUploadTestLifecycleCoordinator();
        coordinator.markWorkerQuarantined();
        assertThat(coordinator.isQuarantined()).isTrue();

        // 1. markWorkerSettled fails closed
        assertThatThrownBy(coordinator::markWorkerSettled)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("WORKER SAFETY BLOCK: Cannot mark workers settled from state [QUARANTINED]");
        assertThat(coordinator.isQuarantined()).isTrue();

        // 2. markWorkerStarted fails closed
        assertThatThrownBy(coordinator::markWorkerStarted)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("WORKER SAFETY BLOCK: Cannot start new workers from state [QUARANTINED]");
        assertThat(coordinator.isQuarantined()).isTrue();

        // 3. resetForNewTest fails closed and does not clear state
        assertThatThrownBy(() -> coordinator.resetForNewTest(() -> {}))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("TEST SETUP BLOCKED");
        assertThat(coordinator.isQuarantined()).isTrue();
    }

    // =============================================================================================
    // TEST-C6-05: Active worker blocks teardown
    // =============================================================================================

    @Test
    @DisplayName("TEST-C6-05: When RUNNING, executeSafeTeardown rejects without mock reset or fixture DELETE")
    void testC6_05_runningWorkerBlocksTeardown() {
        MediaUploadTestLifecycleCoordinator coordinator = new MediaUploadTestLifecycleCoordinator();
        UUID session = UUID.randomUUID();
        UUID alloc = UUID.randomUUID();
        coordinator.registerCreatedSession(session);
        coordinator.registerCreatedAllocation(alloc);

        coordinator.markWorkerStarted();

        AtomicBoolean mockResetInvoked = new AtomicBoolean(false);
        List<UUID> deletedSessions = new ArrayList<>();
        List<UUID> deletedAllocs = new ArrayList<>();

        assertThatThrownBy(() -> coordinator.executeSafeTeardown(
                () -> mockResetInvoked.set(true),
                deletedSessions::add,
                deletedAllocs::add
        ))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("TEARDOWN ABORTED FOR SAFETY: Worker lifecycle state is unsafe (state=RUNNING)")
                .hasMessageContaining(session.toString())
                .hasMessageContaining(alloc.toString());

        assertThat(mockResetInvoked.get()).isFalse();
        assertThat(deletedSessions).isEmpty();
        assertThat(deletedAllocs).isEmpty();
        assertThat(coordinator.getPreservedUncleanedSessionIds()).containsExactly(session);
        assertThat(coordinator.getPreservedUncleanedAllocationIds()).containsExactly(alloc);
    }

    // =============================================================================================
    // TEST-C6-06: Quarantine blocks teardown
    // =============================================================================================

    @Test
    @DisplayName("TEST-C6-06: When QUARANTINED, executeSafeTeardown rejects without mock reset or fixture DELETE")
    void testC6_06_quarantineBlocksTeardown() {
        MediaUploadTestLifecycleCoordinator coordinator = new MediaUploadTestLifecycleCoordinator();
        UUID session = UUID.randomUUID();
        UUID alloc = UUID.randomUUID();
        coordinator.registerCreatedSession(session);
        coordinator.registerCreatedAllocation(alloc);

        coordinator.markWorkerQuarantined();

        AtomicBoolean mockResetInvoked = new AtomicBoolean(false);
        List<UUID> deletedSessions = new ArrayList<>();
        List<UUID> deletedAllocs = new ArrayList<>();

        assertThatThrownBy(() -> coordinator.executeSafeTeardown(
                () -> mockResetInvoked.set(true),
                deletedSessions::add,
                deletedAllocs::add
        ))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("TEARDOWN ABORTED FOR SAFETY: Worker lifecycle state is unsafe (state=QUARANTINED)");

        assertThat(mockResetInvoked.get()).isFalse();
        assertThat(deletedSessions).isEmpty();
        assertThat(deletedAllocs).isEmpty();
        assertThat(coordinator.getPreservedUncleanedSessionIds()).containsExactly(session);
        assertThat(coordinator.getPreservedUncleanedAllocationIds()).containsExactly(alloc);
    }

    // =============================================================================================
    // TEST-C6-07: Previous failed test blocks next test (Cross-Test Quarantine Proof)
    // =============================================================================================

    @Test
    @DisplayName("TEST-C6-07: Quarantine in Test A permanently blocks setup and teardown of Test B on same coordinator")
    void testC6_07_previousFailedTestBlocksNextTestAcrossClassLifecycle() {
        // Shared class-scoped coordinator simulating @TestInstance(PER_CLASS)
        MediaUploadTestLifecycleCoordinator coordinator = new MediaUploadTestLifecycleCoordinator();

        // --- Step 1: Logical Test A starts cleanly ---
        AtomicBoolean mockResetA = new AtomicBoolean(false);
        coordinator.resetForNewTest(() -> mockResetA.set(true));
        assertThat(mockResetA.get()).isTrue();

        // --- Step 2: Test A spawns workers and registers fixtures ---
        coordinator.markWorkerStarted();
        UUID sessionA = UUID.randomUUID();
        UUID allocA = UUID.randomUUID();
        coordinator.registerCreatedSession(sessionA);
        coordinator.registerCreatedAllocation(allocA);

        // --- Step 3: Worker termination unconfirmed in Test A -> marks QUARANTINED ---
        coordinator.markWorkerQuarantined();

        // --- Step 4: Test A @AfterEach runs ---
        AtomicBoolean mockResetAfterA = new AtomicBoolean(false);
        List<UUID> deletedInA = new ArrayList<>();
        assertThatThrownBy(() -> coordinator.executeSafeTeardown(
                () -> mockResetAfterA.set(true),
                deletedInA::add,
                deletedInA::add
        ))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("TEARDOWN ABORTED FOR SAFETY");

        assertThat(mockResetAfterA.get()).isFalse();
        assertThat(deletedInA).isEmpty();
        assertThat(coordinator.getPreservedUncleanedSessionIds()).contains(sessionA);

        // --- Step 5: Logical Test B starts on same coordinator instance ---
        AtomicBoolean mockResetB = new AtomicBoolean(false);
        assertThatThrownBy(() -> coordinator.resetForNewTest(() -> mockResetB.set(true)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("TEST SETUP BLOCKED")
                .hasMessageContaining("state=QUARANTINED")
                .hasMessageContaining("Shared mock reset is strictly forbidden");

        // Assert shared mock was NEVER reset by Test B setup
        assertThat(mockResetB.get()).isFalse();

        // --- Step 6: Test B @AfterEach runs ---
        AtomicBoolean mockResetAfterB = new AtomicBoolean(false);
        List<UUID> deletedInB = new ArrayList<>();
        assertThatThrownBy(() -> coordinator.executeSafeTeardown(
                () -> mockResetAfterB.set(true),
                deletedInB::add,
                deletedInB::add
        ))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("TEARDOWN ABORTED FOR SAFETY: Worker lifecycle state is unsafe (state=QUARANTINED)");

        assertThat(mockResetAfterB.get()).isFalse();
        assertThat(deletedInB).isEmpty();

        // Preserved fixtures remain recorded and observable
        assertThat(coordinator.getPreservedUncleanedSessionIds()).containsExactly(sessionA);
        assertThat(coordinator.getPreservedUncleanedAllocationIds()).containsExactly(allocA);
        assertThat(coordinator.isQuarantined()).isTrue();
    }

    // =============================================================================================
    // TEST-C6-08: Successful worker completion
    // =============================================================================================

    @Test
    @DisplayName("TEST-C6-08: Confirmed worker termination transitions RUNNING to IDLE and permits teardown and next test")
    void testC6_08_confirmedWorkerTerminationPermitsCleanLifecycle() {
        MediaUploadTestLifecycleCoordinator coordinator = new MediaUploadTestLifecycleCoordinator();

        // Test 1: starts, runs worker, confirms termination
        coordinator.resetForNewTest(() -> {});
        coordinator.markWorkerStarted();
        UUID session1 = UUID.randomUUID();
        UUID alloc1 = UUID.randomUUID();
        coordinator.registerCreatedSession(session1);
        coordinator.registerCreatedAllocation(alloc1);

        // Worker settles cleanly
        coordinator.markWorkerSettled();
        assertThat(coordinator.getWorkerState()).isEqualTo(MediaUploadTestLifecycleCoordinator.WorkerState.IDLE);

        // Teardown executes cleanly
        List<UUID> deletedSessions = new ArrayList<>();
        List<UUID> deletedAllocs = new ArrayList<>();
        coordinator.executeSafeTeardown(
                () -> {},
                deletedSessions::add,
                deletedAllocs::add
        );

        assertThat(deletedSessions).containsExactly(session1);
        assertThat(deletedAllocs).containsExactly(alloc1);
        assertThat(coordinator.getCreatedSessionIds()).isEmpty();
        assertThat(coordinator.getCreatedAllocationIds()).isEmpty();

        // Test 2 setup proceeds cleanly
        AtomicBoolean mockResetTest2 = new AtomicBoolean(false);
        coordinator.resetForNewTest(() -> mockResetTest2.set(true));
        assertThat(mockResetTest2.get()).isTrue();
    }

    // =============================================================================================
    // TEST-C6-09: Repeated teardown remains safe
    // =============================================================================================

    @Test
    @DisplayName("TEST-C6-09: Repeated executeSafeTeardown in QUARANTINED state does not duplicate preserved IDs")
    void testC6_09_repeatedTeardownDoesNotDuplicateEvidence() {
        MediaUploadTestLifecycleCoordinator coordinator = new MediaUploadTestLifecycleCoordinator();
        UUID session = UUID.randomUUID();
        UUID alloc = UUID.randomUUID();
        coordinator.registerCreatedSession(session);
        coordinator.registerCreatedAllocation(alloc);

        coordinator.markWorkerQuarantined();

        // First teardown
        assertThatThrownBy(() -> coordinator.executeSafeTeardown(() -> {}, id -> {}, id -> {}))
                .isInstanceOf(AssertionError.class);
        assertThat(coordinator.getPreservedUncleanedSessionIds()).containsExactly(session);
        assertThat(coordinator.getPreservedUncleanedAllocationIds()).containsExactly(alloc);

        // Second teardown (e.g. from outer error handling or framework invocation)
        assertThatThrownBy(() -> coordinator.executeSafeTeardown(() -> {}, id -> {}, id -> {}))
                .isInstanceOf(AssertionError.class);

        // Assert list contains exactly the single ID (no duplicates)
        assertThat(coordinator.getPreservedUncleanedSessionIds()).containsExactly(session);
        assertThat(coordinator.getPreservedUncleanedAllocationIds()).containsExactly(alloc);
    }

    // =============================================================================================
    // TEST-C6-10: Deletion callback fails
    // =============================================================================================

    @Test
    @DisplayName("TEST-C6-10: Deletion error during normal teardown surfaces failed ID and throws AssertionError")
    void testC6_10_teardownFailureSurfacesFailedFixtureId() {
        MediaUploadTestLifecycleCoordinator coordinator = new MediaUploadTestLifecycleCoordinator();
        UUID session = UUID.randomUUID();
        coordinator.registerCreatedSession(session);

        assertThatThrownBy(() -> coordinator.executeSafeTeardown(
                () -> {},
                id -> { throw new RuntimeException("Simulated DB lock timeout for session " + id); },
                id -> {}
        ))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Test cleanup encountered errors")
                .hasMessageContaining("Simulated DB lock timeout for session " + session);
    }

    // =============================================================================================
    // TEST-C6-11: No forced reset from invalid transitions
    // =============================================================================================

    @Test
    @DisplayName("TEST-C6-11: Invalid state transitions throw AssertionError and preserve safety")
    void testC6_11_invalidStateTransitionsFailClosed() {
        MediaUploadTestLifecycleCoordinator coordinator = new MediaUploadTestLifecycleCoordinator();

        // 1. IDLE -> settled (cannot settle when not running)
        assertThatThrownBy(coordinator::markWorkerSettled)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Cannot mark workers settled from state [IDLE]");

        // 2. IDLE -> RUNNING -> RUNNING (cannot double start)
        coordinator.markWorkerStarted();
        assertThatThrownBy(coordinator::markWorkerStarted)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Cannot start new workers from state [RUNNING]");

        // 3. RUNNING -> QUARANTINED -> settled (cannot settle from quarantine)
        coordinator.markWorkerQuarantined();
        assertThatThrownBy(coordinator::markWorkerSettled)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Cannot mark workers settled from state [QUARANTINED]");
    }

    // =============================================================================================
    // TEST-C6-12: Pure offline in-memory execution guarantee
    // =============================================================================================

    @Test
    @DisplayName("TEST-C6-12: Verification uses purely in-memory callbacks without Spring or DB infrastructure")
    void testC6_12_pureOfflineInMemoryGuarantees() {
        MediaUploadTestLifecycleCoordinator coordinator = new MediaUploadTestLifecycleCoordinator();
        assertThat(coordinator.getWorkerState()).isEqualTo(MediaUploadTestLifecycleCoordinator.WorkerState.IDLE);
        assertThat(coordinator.getCreatedSessionIds()).isEmpty();
        assertThat(coordinator.getCreatedAllocationIds()).isEmpty();
        assertThat(coordinator.getPreservedUncleanedSessionIds()).isEmpty();
        assertThat(coordinator.getPreservedUncleanedAllocationIds()).isEmpty();
    }
}
