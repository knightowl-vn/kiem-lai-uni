package com.universe.media.infrastructure.persistence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Cross-test worker quarantine and lifecycle coordinator (MS-08E3-S4-MYSQL-C6).
 *
 * <p>Enforces a fail-closed 3-state machine:
 * <ul>
 *   <li><b>IDLE:</b> No active or unconfirmed background workers. Mock reset and fixture deletion permitted.</li>
 *   <li><b>RUNNING:</b> Worker thread execution is active or pending confirmation. Mock reset and deletions blocked.</li>
 *   <li><b>QUARANTINED:</b> An executor failed to confirm termination within its bounded wait or was interrupted.
 *       The coordinator is permanently quarantined for the remainder of the test class lifecycle.
 *       All subsequent test setups, mock resets, and database deletions are strictly blocked to protect
 *       shared test infrastructure from active background threads.</li>
 * </ul>
 */
public final class MediaUploadTestLifecycleCoordinator {

    public enum WorkerState {
        IDLE,
        RUNNING,
        QUARANTINED
    }

    private final AtomicReference<WorkerState> workerState = new AtomicReference<>(WorkerState.IDLE);
    private final List<UUID> createdSessionIds = Collections.synchronizedList(new ArrayList<>());
    private final List<UUID> createdAllocationIds = Collections.synchronizedList(new ArrayList<>());
    private final List<UUID> preservedUncleanedSessionIds = Collections.synchronizedList(new ArrayList<>());
    private final List<UUID> preservedUncleanedAllocationIds = Collections.synchronizedList(new ArrayList<>());

    public WorkerState getWorkerState() {
        return workerState.get();
    }

    public boolean isQuarantined() {
        return workerState.get() == WorkerState.QUARANTINED;
    }

    public boolean areWorkersActive() {
        return workerState.get() != WorkerState.IDLE;
    }

    public void markWorkerStarted() {
        WorkerState current = workerState.get();
        if (!workerState.compareAndSet(WorkerState.IDLE, WorkerState.RUNNING)) {
            throw new AssertionError(
                    "WORKER SAFETY BLOCK: Cannot start new workers from state [" + current
                            + "]. An earlier worker lifecycle is unresolved or quarantined."
            );
        }
    }

    public void markWorkerSettled() {
        WorkerState current = workerState.get();
        if (!workerState.compareAndSet(WorkerState.RUNNING, WorkerState.IDLE)) {
            throw new AssertionError(
                    "WORKER SAFETY BLOCK: Cannot mark workers settled from state [" + current
                            + "]. Current state is not RUNNING."
            );
        }
    }

    public void markWorkerQuarantined() {
        workerState.set(WorkerState.QUARANTINED);
    }

    public void registerCreatedSession(UUID sessionId) {
        if (sessionId != null) {
            createdSessionIds.add(sessionId);
        }
    }

    public void registerCreatedAllocation(UUID allocationId) {
        if (allocationId != null) {
            createdAllocationIds.add(allocationId);
        }
    }

    public List<UUID> getCreatedSessionIds() {
        return createdSessionIds;
    }

    public List<UUID> getCreatedAllocationIds() {
        return createdAllocationIds;
    }

    public List<UUID> getPreservedUncleanedSessionIds() {
        return preservedUncleanedSessionIds;
    }

    public List<UUID> getPreservedUncleanedAllocationIds() {
        return preservedUncleanedAllocationIds;
    }

    /**
     * Prepares test setup. Fails closed if the coordinator is in RUNNING or QUARANTINED state.
     * Shared mock reset is strictly forbidden unless the previous test settled into IDLE.
     */
    public void resetForNewTest(Runnable mockResetter) {
        WorkerState current = workerState.get();
        if (current != WorkerState.IDLE) {
            throw new AssertionError(
                    "TEST SETUP BLOCKED: A previous worker lifecycle is unresolved (state="
                            + current + "). Shared mock reset is strictly forbidden. Preserved uncleaned session IDs: "
                            + preservedUncleanedSessionIds + ", allocation IDs: " + preservedUncleanedAllocationIds
            );
        }
        if (mockResetter != null) {
            mockResetter.run();
        }
    }

    /**
     * Executes safe teardown in strict sequence:
     * 1. Inspect worker state FIRST.
     * 2. If workers are RUNNING or QUARANTINED, abort immediately without touching mock or database.
     * 3. Only if state is IDLE, reset mock and perform reverse-order cleanup.
     */
    public void executeSafeTeardown(
            Runnable mockResetter,
            Consumer<UUID> sessionDeleter,
            Consumer<UUID> allocationDeleter
    ) {
        WorkerState current = workerState.get();

        // Step 1: Check worker state BEFORE resetting mock or touching database
        if (current != WorkerState.IDLE) {
            synchronized (createdSessionIds) {
                for (UUID sessionId : createdSessionIds) {
                    if (!preservedUncleanedSessionIds.contains(sessionId)) {
                        preservedUncleanedSessionIds.add(sessionId);
                    }
                }
                createdSessionIds.clear();
            }
            synchronized (createdAllocationIds) {
                for (UUID allocId : createdAllocationIds) {
                    if (!preservedUncleanedAllocationIds.contains(allocId)) {
                        preservedUncleanedAllocationIds.add(allocId);
                    }
                }
                createdAllocationIds.clear();
            }
            // CRITICAL: DO NOT call mockResetter.run() here!
            // Background worker threads may still be accessing the mock. Resetting a mock
            // concurrently causes Mockito state corruption or race conditions.
            // Also SKIP all database DELETE statements to prevent deadlocks and FK violations.
            throw new AssertionError(
                    "TEARDOWN ABORTED FOR SAFETY: Worker lifecycle state is unsafe (state=" + current + "). "
                            + "Skipping mock reset and database DELETE operations to prevent mock race conditions, "
                            + "deadlocks, foreign key violations, or concurrent test database corruption. "
                            + "Preserved uncleaned session IDs: " + preservedUncleanedSessionIds
                            + ", allocation IDs: " + preservedUncleanedAllocationIds
                            + ". Manual review of preserved test-owned fixtures may be required."
            );
        }

        // Step 2: Safe to reset mock only AFTER worker termination is confirmed (state == IDLE)
        if (mockResetter != null) {
            mockResetter.run();
        }

        // Step 3: Safe reverse foreign-key ordered teardown
        List<String> cleanupErrors = new ArrayList<>();

        // 3a. media_upload_sessions (references media_storage_key_allocations)
        synchronized (createdSessionIds) {
            for (UUID sessionId : createdSessionIds) {
                try {
                    sessionDeleter.accept(sessionId);
                } catch (Exception ex) {
                    cleanupErrors.add("Failed to delete session " + sessionId + ": " + ex.getMessage());
                }
            }
            createdSessionIds.clear();
        }

        // 3b. media_storage_key_allocations
        synchronized (createdAllocationIds) {
            for (UUID allocId : createdAllocationIds) {
                try {
                    allocationDeleter.accept(allocId);
                } catch (Exception ex) {
                    cleanupErrors.add("Failed to delete allocation " + allocId + ": " + ex.getMessage());
                }
            }
            createdAllocationIds.clear();
        }

        if (!cleanupErrors.isEmpty()) {
            throw new AssertionError("Test cleanup encountered errors: " + String.join("; ", cleanupErrors));
        }
    }
}
