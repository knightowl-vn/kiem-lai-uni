package com.universe.media.infrastructure.hls;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

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
 * Bounded subprocess runner for FFmpeg/ffprobe execution.
 * Enforces explicit argument lists (no shell expansion), bounded stream capture,
 * strict timeouts, and descendant-inclusive forced termination on timeout or interruption.
 */
@Component
@ConditionalOnProperty(prefix = "media.hls.packaging", name = "enabled", havingValue = "true")
public class BoundedSubprocessRunner {

    private static final int COPY_BUFFER_SIZE = 8192;
    private static final long TERMINATION_GRACE_MILLIS = 2000L;
    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    public record ProcessResult(int exitCode, String output, long elapsedMillis) {
    }

    @FunctionalInterface
    interface ProcessStarter {
        Process start(List<String> command, Path workingDir) throws IOException;
    }

    private final ProcessStarter processStarter;

    public BoundedSubprocessRunner() {
        this((command, workingDir) -> new ProcessBuilder(command)
                .directory(workingDir.toFile())
                .redirectErrorStream(true)
                .start());
    }

    BoundedSubprocessRunner(ProcessStarter processStarter) {
        this.processStarter = Objects.requireNonNull(processStarter, "processStarter cannot be null.");
    }

    public ProcessResult execute(List<String> command, Path workingDir, Duration timeout, int maxOutputBytes)
            throws IOException, InterruptedException, TimeoutException {
        Objects.requireNonNull(command, "command cannot be null.");
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command cannot be empty.");
        }
        Objects.requireNonNull(workingDir, "workingDir cannot be null.");
        Objects.requireNonNull(timeout, "timeout cannot be null.");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be strictly positive.");
        }
        if (maxOutputBytes <= 0) {
            throw new IllegalArgumentException("maxOutputBytes must be positive.");
        }

        long startNanos = System.nanoTime();
        Process process = processStarter.start(List.copyOf(command), workingDir);

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        AtomicReference<IOException> readerError = new AtomicReference<>();
        ExecutorService pump = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "media-hls-subproc-pump-" + THREAD_SEQ.incrementAndGet());
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

            long remainingNanos = Math.max(
                    TimeUnit.MILLISECONDS.toNanos(250),
                    timeoutNanos - (System.nanoTime() - startNanos)
            );
            try {
                readerFuture.get(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (ExecutionException e) {
                throw new IOException("Failed draining subprocess output.", e.getCause());
            }

            if (readerError.get() != null) {
                throw new IOException("Subprocess output drain failed.", readerError.get());
            }

            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            return new ProcessResult(process.exitValue(), captured.toString(StandardCharsets.UTF_8), elapsedMillis);
        } catch (TimeoutException | InterruptedException e) {
            terminateProcessTree(process);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw e;
        } finally {
            pump.shutdownNow();
        }
    }

    private static void terminateProcessTree(Process process) {
        try {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (Exception ignored) {
        }
        process.destroyForcibly();
        try {
            process.waitFor(TERMINATION_GRACE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
