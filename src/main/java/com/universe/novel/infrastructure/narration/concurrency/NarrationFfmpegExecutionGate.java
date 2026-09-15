package com.universe.novel.infrastructure.narration.concurrency;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;

/**
 * Shared JVM-local concurrency gate for FFmpeg audio operations (H.10A3).
 * <p>
 * Regulates concurrent execution of OS-level FFmpeg and FFprobe processes across all
 * audio adapters (segment MP3 encoder and chapter MP3 concat assembler) using a bounded fair {@link Semaphore}.
 * <p>
 * <strong>Invariants:</strong>
 * <ul>
 *     <li>Configured via property {@code novel.narration.concurrency.max-concurrent-ffmpeg} (default 1).</li>
 *     <li>Strictly rejects non-positive capacities (must be &gt;= 1).</li>
 *     <li>Acquires one permit before executing the supplied action; releases in {@code finally}.</li>
 *     <li>Releases permit correctly even when the supplied action throws a runtime exception or error.</li>
 *     <li>Preserves thread interruption status if interrupted while waiting for capacity.</li>
 *     <li>No busy-waiting, spinning, or acquisition timeouts.</li>
 *     <li>Infrastructure-only; domain and application layers remain completely unaware of the gate.</li>
 * </ul>
 */
@Component
public class NarrationFfmpegExecutionGate {

    private final int maxConcurrentFfmpeg;
    private final Semaphore semaphore;

    public NarrationFfmpegExecutionGate(
            @Value("${novel.narration.concurrency.max-concurrent-ffmpeg:1}") int maxConcurrentFfmpeg
    ) {
        if (maxConcurrentFfmpeg < 1) {
            throw new IllegalArgumentException(
                    "novel.narration.concurrency.max-concurrent-ffmpeg must be >= 1, but found: " + maxConcurrentFfmpeg
            );
        }
        this.maxConcurrentFfmpeg = maxConcurrentFfmpeg;
        this.semaphore = new Semaphore(maxConcurrentFfmpeg, true);
    }

    public int getMaxConcurrentFfmpeg() {
        return maxConcurrentFfmpeg;
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
            throw new IllegalStateException("Interrupted while waiting for FFmpeg execution gate permit", e);
        }

        try {
            return action.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("FFmpeg action failed: " + e.getMessage(), e);
        } finally {
            semaphore.release();
        }
    }
}
