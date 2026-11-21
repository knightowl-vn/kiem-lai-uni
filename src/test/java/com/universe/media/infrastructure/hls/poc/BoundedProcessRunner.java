package com.universe.media.infrastructure.hls.poc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Local bounded {@link ProcessBuilder} runner for FFmpeg/FFprobe PoC execution.
 * Enforces explicit argument vectors (no shell expansion), bounded output capture, and strict timeouts.
 */
final class BoundedProcessRunner {

    private static final int COPY_BUFFER_SIZE = 8192;
    private static final long TERMINATION_GRACE_MILLIS = 1000L;
    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    record ProcessResult(int exitCode, String output, long elapsedMillis) {
    }

    ProcessResult execute(List<String> command, Path workingDir, Duration timeout, int maxOutputBytes)
            throws IOException, InterruptedException, TimeoutException {
        Objects.requireNonNull(command, "command cannot be null");
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command cannot be empty");
        }
        Objects.requireNonNull(workingDir, "workingDir cannot be null");
        Objects.requireNonNull(timeout, "timeout cannot be null");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be strictly positive");
        }
        if (maxOutputBytes <= 0) {
            throw new IllegalArgumentException("maxOutputBytes must be positive");
        }

        ProcessBuilder pb = new ProcessBuilder(List.copyOf(command));
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(true);

        long startNanos = System.nanoTime();
        Process process = pb.start();

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        AtomicReference<IOException> readerError = new AtomicReference<>();
        ExecutorService pump = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "hls-poc-proc-pump-" + THREAD_SEQ.incrementAndGet());
            t.setDaemon(true);
            return t;
        });

        Future<?> readerFuture = pump.submit(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[COPY_BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    if (read > 0 && captured.size() < maxOutputBytes) {
                        int writable = Math.min(read, maxOutputBytes - captured.size());
                        captured.write(buffer, 0, writable);
                    }
                }
            } catch (IOException e) {
                readerError.set(e);
            }
        });

        long timeoutNanos = timeout.toNanos();
        try {
            boolean finished = process.waitFor(timeoutNanos, TimeUnit.NANOSECONDS);
            if (!finished) {
                throw new TimeoutException("Process exceeded timeout of " + timeout.toMillis() + " ms: " + command.get(0));
            }

            long remainingNanos = Math.max(TimeUnit.MILLISECONDS.toNanos(250), timeoutNanos - (System.nanoTime() - startNanos));
            try {
                readerFuture.get(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (ExecutionException e) {
                throw new IOException("Failed draining process output", e.getCause());
            }

            if (readerError.get() != null) {
                throw new IOException("Process output drain failed", readerError.get());
            }

            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            String output = captured.toString(StandardCharsets.UTF_8);
            return new ProcessResult(process.exitValue(), output, elapsedMillis);
        } catch (TimeoutException | InterruptedException e) {
            process.destroyForcibly();
            try {
                process.waitFor(TERMINATION_GRACE_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw e;
        } finally {
            pump.shutdownNow();
        }
    }
}
