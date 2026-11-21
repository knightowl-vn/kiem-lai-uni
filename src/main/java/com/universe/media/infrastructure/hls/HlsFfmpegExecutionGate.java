package com.universe.media.infrastructure.hls;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;

/**
 * Bounded Media-owned semaphore gate regulating concurrent local FFmpeg HLS encodings.
 * Default capacity is 1 concurrent encode to protect CPU and scratch disk I/O.
 */
@Component
@ConditionalOnProperty(prefix = "media.hls.packaging", name = "enabled", havingValue = "true")
public class HlsFfmpegExecutionGate {

    private final int maxConcurrentEncodes;
    private final Semaphore semaphore;

    @Autowired
    public HlsFfmpegExecutionGate(HlsPackagingProperties properties) {
        this(Objects.requireNonNull(properties, "HlsPackagingProperties cannot be null.").maxConcurrentEncodes());
    }

    public HlsFfmpegExecutionGate(int maxConcurrentEncodes) {
        if (maxConcurrentEncodes < 1) {
            throw new IllegalArgumentException("maxConcurrentEncodes must be >= 1, got: " + maxConcurrentEncodes);
        }
        this.maxConcurrentEncodes = maxConcurrentEncodes;
        this.semaphore = new Semaphore(maxConcurrentEncodes, true);
    }

    public int getMaxConcurrentEncodes() {
        return maxConcurrentEncodes;
    }

    public int getAvailablePermits() {
        return semaphore.availablePermits();
    }

    public <T> T execute(Callable<T> action) throws Exception {
        Objects.requireNonNull(action, "action cannot be null.");
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
        try {
            return action.call();
        } finally {
            semaphore.release();
        }
    }
}
