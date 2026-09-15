package com.universe.novel.infrastructure.narration.concurrency;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;

/**
 * Shared JVM-local concurrency gate for TTS synthesis requests (H.10A2).
 * <p>
 * Regulates concurrent access to external TTS services across all application callers
 * (Admin whole-chapter generation, Reader chapter preparation, Admin single-segment generation/regeneration)
 * using a bounded fair {@link Semaphore}.
 * <p>
 * <strong>Invariants:</strong>
 * <ul>
 *     <li>Configured via property {@code novel.narration.concurrency.max-concurrent-tts} (default 1).</li>
 *     <li>Strictly rejects non-positive capacities (must be &gt;= 1).</li>
 *     <li>Acquires one permit before executing the supplied action; releases in {@code finally}.</li>
 *     <li>Releases permit correctly even when the supplied action throws a runtime exception or error.</li>
 *     <li>Preserves thread interruption status if interrupted while waiting for capacity.</li>
 *     <li>No busy-waiting or spinning.</li>
 *     <li>Infrastructure-only; domain and application layers remain completely unaware of the gate.</li>
 * </ul>
 */
@Component
public class NarrationTtsExecutionGate {

    private final int maxConcurrentTts;
    private final Semaphore semaphore;

    public NarrationTtsExecutionGate(
            @Value("${novel.narration.concurrency.max-concurrent-tts:1}") int maxConcurrentTts
    ) {
        if (maxConcurrentTts < 1) {
            throw new IllegalArgumentException(
                    "novel.narration.concurrency.max-concurrent-tts must be >= 1, but found: " + maxConcurrentTts
            );
        }
        this.maxConcurrentTts = maxConcurrentTts;
        this.semaphore = new Semaphore(maxConcurrentTts, true);
    }

    public int getMaxConcurrentTts() {
        return maxConcurrentTts;
    }

    public int getAvailablePermits() {
        return semaphore.availablePermits();
    }

    public int getQueueLength() {
        return semaphore.getQueueLength();
    }

    /**
     * Executes a supplied action within the bounded concurrency permit.
     *
     * @param action the action to execute within the gate (non-null)
     * @param <T>    the return type of the action
     * @return the result returned by the action
     */
    public <T> T execute(Callable<T> action) {
        if (action == null) {
            throw new IllegalArgumentException("Action must not be null");
        }

        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for TTS execution gate permit", e);
        }

        try {
            return action.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("TTS action failed: " + e.getMessage(), e);
        } finally {
            semaphore.release();
        }
    }
}
