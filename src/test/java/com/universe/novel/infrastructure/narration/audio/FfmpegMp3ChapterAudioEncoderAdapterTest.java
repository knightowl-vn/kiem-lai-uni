package com.universe.novel.infrastructure.narration.audio;

import com.universe.novel.application.narration.ChapterAudioAssemblyResource;
import com.universe.novel.application.narration.ChapterAudioEncodingRequest;
import com.universe.novel.application.narration.ChapterAudioEncodingResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FFmpeg MP3 Chapter Audio Encoder Adapter Tests")
class FfmpegMp3ChapterAudioEncoderAdapterTest {

    private static final Duration ENCODING_TIMEOUT = Duration.ofSeconds(12);
    private static final byte[] SOURCE_WAV = pcm16Wav(new short[]{0, 120, -120, 0});
    private static final byte[] ENCODED_MP3 = new byte[]{'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0, 1, 2, 3};

    @Test
    @DisplayName("WAV encoding returns caller-owned MP3 resource and preserves source resource ownership")
    void shouldEncodeWavWithExplicitSourceAndOutputOwnership() throws IOException {
        Path outputFile = Files.createTempFile("h9d2_success_", ".mp3");
        TrackingAssemblyResource source = new TrackingAssemblyResource("audio/wav; charset=binary", SOURCE_WAV);
        AtomicReference<List<String>> capturedCommand = new AtomicReference<>();
        AtomicReference<Duration> capturedTimeout = new AtomicReference<>();
        String executable = "C:\\Program Files\\FFmpeg\\ffmpeg.exe";
        FfmpegMp3ChapterAudioEncoderAdapter adapter = adapter(
                outputFile,
                executable,
                (command, sourceStream, timeout) -> {
                    capturedCommand.set(command);
                    capturedTimeout.set(timeout);
                    assertThat(sourceStream.readAllBytes()).isEqualTo(SOURCE_WAV);
                    Files.write(outputFile, ENCODED_MP3);
                    return new FfmpegMp3ChapterAudioEncoderAdapter.EncoderProcessResult(0, "");
                }
        );

        ChapterAudioEncodingResult result = adapter.encode(new ChapterAudioEncodingRequest(source));

        assertThat(result.mimeType()).isEqualTo("audio/mpeg");
        assertThat(result.resource().mimeType()).isEqualTo("audio/mpeg");
        assertThat(result.sizeBytes()).isEqualTo(ENCODED_MP3.length);
        assertThat(source.openCount()).isEqualTo(1);
        assertThat(source.closedStreamCount()).isEqualTo(1);
        assertThat(source.resourceClosed()).isFalse();
        assertThat(capturedTimeout.get()).isEqualTo(ENCODING_TIMEOUT);
        assertThat(capturedCommand.get()).containsExactly(
                executable,
                "-hide_banner",
                "-loglevel", "error",
                "-nostats",
                "-y",
                "-f", "wav",
                "-i", "pipe:0",
                "-map_metadata", "-1",
                "-map_chapters", "-1",
                "-vn",
                "-c:a", "libmp3lame",
                "-b:a", "96k",
                "-f", "mp3",
                outputFile.toAbsolutePath().toString()
        );
        assertThat(capturedCommand.get()).doesNotContain("cmd", "cmd.exe", "sh", "/bin/sh", "-c");

        try (InputStream reusableSourceStream = source.openStream()) {
            assertThat(reusableSourceStream.readAllBytes()).isEqualTo(SOURCE_WAV);
        }
        source.close();
        assertThat(source.resourceClosed()).isTrue();

        InputStream encodedStream = result.openStream();
        assertThat(encodedStream.readAllBytes()).isEqualTo(ENCODED_MP3);
        assertThatThrownBy(result::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("open stream");
        assertThat(Files.exists(outputFile)).isTrue();

        encodedStream.close();
        result.close();
        assertThat(Files.exists(outputFile)).isFalse();
        result.close();
        assertThatThrownBy(result::openStream)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already been closed");
    }

    @Test
    @DisplayName("unsupported source MIME is rejected before temp creation or process invocation")
    void shouldRejectUnsupportedSourceMimeBeforeProcessInvocation() {
        TrackingAssemblyResource source = new TrackingAssemblyResource("audio/mpeg", ENCODED_MP3);
        AtomicInteger tempCreations = new AtomicInteger();
        AtomicInteger processInvocations = new AtomicInteger();
        FfmpegMp3ChapterAudioEncoderAdapter adapter = new FfmpegMp3ChapterAudioEncoderAdapter(
                "ffmpeg",
                ENCODING_TIMEOUT,
                () -> {
                    tempCreations.incrementAndGet();
                    return Path.of("unused.mp3");
                },
                Files::deleteIfExists,
                (command, stream, timeout) -> {
                    processInvocations.incrementAndGet();
                    return new FfmpegMp3ChapterAudioEncoderAdapter.EncoderProcessResult(0, "");
                }
        );

        assertThatThrownBy(() -> adapter.encode(new ChapterAudioEncodingRequest(source)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported")
                .hasMessageContaining("audio/mpeg");
        assertThat(tempCreations).hasValue(0);
        assertThat(processInvocations).hasValue(0);
        assertThat(source.openCount()).isZero();
        assertThat(source.resourceClosed()).isFalse();
    }

    @Test
    @DisplayName("process start failure deletes the temporary output")
    void shouldCleanOutputAfterProcessStartFailure() throws IOException {
        Path outputFile = Files.createTempFile("h9d2_start_failure_", ".mp3");
        FfmpegMp3ChapterAudioEncoderAdapter adapter = adapter(
                outputFile,
                "ffmpeg",
                (command, stream, timeout) -> {
                    throw new IOException("Failed to start FFmpeg encoder process.");
                }
        );

        assertThatThrownBy(() -> adapter.encode(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to start FFmpeg encoder process");
        assertThat(Files.exists(outputFile)).isFalse();
    }

    @Test
    @DisplayName("process I/O failure deletes partial encoded output")
    void shouldCleanPartialOutputAfterProcessIoFailure() throws IOException {
        Path outputFile = Files.createTempFile("h9d2_io_failure_", ".mp3");
        FfmpegMp3ChapterAudioEncoderAdapter adapter = adapter(
                outputFile,
                "ffmpeg",
                (command, stream, timeout) -> {
                    Files.write(outputFile, new byte[]{1, 2});
                    throw new IOException("simulated process I/O failure");
                }
        );

        assertThatThrownBy(() -> adapter.encode(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated process I/O failure");
        assertThat(Files.exists(outputFile)).isFalse();
    }

    @Test
    @DisplayName("non-zero FFmpeg exit reports bounded diagnostics and deletes partial output")
    void shouldCleanPartialOutputAfterNonZeroExit() throws IOException {
        Path outputFile = Files.createTempFile("h9d2_nonzero_", ".mp3");
        FfmpegMp3ChapterAudioEncoderAdapter adapter = adapter(
                outputFile,
                "ffmpeg",
                (command, stream, timeout) -> {
                    Files.write(outputFile, new byte[]{1, 2});
                    return new FfmpegMp3ChapterAudioEncoderAdapter.EncoderProcessResult(7, "invalid WAV input");
                }
        );

        assertThatThrownBy(() -> adapter.encode(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exit code 7")
                .hasMessageContaining("invalid WAV input");
        assertThat(Files.exists(outputFile)).isFalse();
    }

    @Test
    @DisplayName("process timeout deletes partial encoded output")
    void shouldCleanPartialOutputAfterTimeout() throws IOException {
        Path outputFile = Files.createTempFile("h9d2_timeout_", ".mp3");
        FfmpegMp3ChapterAudioEncoderAdapter adapter = adapter(
                outputFile,
                "ffmpeg",
                (command, stream, timeout) -> {
                    Files.write(outputFile, new byte[]{1, 2});
                    throw new TimeoutException("simulated timeout");
                }
        );

        assertThatThrownBy(() -> adapter.encode(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("timed out")
                .hasMessageContaining("12000 ms");
        assertThat(Files.exists(outputFile)).isFalse();
    }

    @Test
    @DisplayName("cleanup failure is suppressed under the primary encoding failure")
    void shouldSuppressCleanupFailureUnderPrimaryFailure() throws IOException {
        Path outputFile = Files.createTempFile("h9d2_cleanup_failure_", ".mp3");
        IOException cleanupFailure = new IOException("simulated cleanup failure");
        FfmpegMp3ChapterAudioEncoderAdapter adapter = new FfmpegMp3ChapterAudioEncoderAdapter(
                "ffmpeg",
                ENCODING_TIMEOUT,
                () -> outputFile,
                ignored -> {
                    throw cleanupFailure;
                },
                (command, stream, timeout) -> {
                    Files.write(outputFile, new byte[]{1, 2});
                    throw new IOException("primary encoding failure");
                }
        );

        try {
            assertThatThrownBy(() -> adapter.encode(request()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("primary encoding failure")
                    .satisfies(failure -> assertThat(failure.getSuppressed()).containsExactly(cleanupFailure));
        } finally {
            Files.deleteIfExists(outputFile);
        }
    }

    @Test
    @DisplayName("missing successful output is rejected and cleanup is attempted")
    void shouldRejectMissingOutputAndAttemptCleanup() throws IOException {
        Path outputFile = Files.createTempFile("h9d2_missing_", ".mp3");
        AtomicBoolean cleanupAttempted = new AtomicBoolean(false);
        FfmpegMp3ChapterAudioEncoderAdapter adapter = new FfmpegMp3ChapterAudioEncoderAdapter(
                "ffmpeg",
                ENCODING_TIMEOUT,
                () -> outputFile,
                path -> {
                    cleanupAttempted.set(true);
                    Files.deleteIfExists(path);
                },
                (command, stream, timeout) -> {
                    Files.deleteIfExists(outputFile);
                    return new FfmpegMp3ChapterAudioEncoderAdapter.EncoderProcessResult(0, "");
                }
        );

        assertThatThrownBy(() -> adapter.encode(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not produce");
        assertThat(cleanupAttempted).isTrue();
        assertThat(Files.exists(outputFile)).isFalse();
    }

    @Test
    @DisplayName("empty successful output is rejected and deleted")
    void shouldRejectAndCleanEmptyOutput() throws IOException {
        Path outputFile = Files.createTempFile("h9d2_empty_", ".mp3");
        FfmpegMp3ChapterAudioEncoderAdapter adapter = adapter(
                outputFile,
                "ffmpeg",
                (command, stream, timeout) -> new FfmpegMp3ChapterAudioEncoderAdapter.EncoderProcessResult(0, "")
        );

        assertThatThrownBy(() -> adapter.encode(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty");
        assertThat(Files.exists(outputFile)).isFalse();
    }

    @Test
    @DisplayName("interruption restores the thread interrupt flag and deletes partial output")
    void shouldRestoreInterruptStatusAndCleanPartialOutput() throws IOException {
        Path outputFile = Files.createTempFile("h9d2_interrupted_", ".mp3");
        FfmpegMp3ChapterAudioEncoderAdapter adapter = adapter(
                outputFile,
                "ffmpeg",
                (command, stream, timeout) -> {
                    Files.write(outputFile, new byte[]{1, 2});
                    throw new InterruptedException("simulated interruption");
                }
        );

        try {
            assertThatThrownBy(() -> adapter.encode(request()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(Files.exists(outputFile)).isFalse();
        } finally {
            Thread.interrupted();
            Files.deleteIfExists(outputFile);
        }
    }

    @Test
    @DisplayName("encoded stream close failure still releases resource stream tracking")
    void shouldReleaseEncodedStreamTrackingWhenUnderlyingCloseFails() throws IOException {
        Path outputFile = Files.createTempFile("h9d2_stream_close_", ".mp3");
        Files.write(outputFile, ENCODED_MP3);
        CloseThrowingInputStream rawStream = new CloseThrowingInputStream(ENCODED_MP3);
        TempFileChapterAudioEncodedResource resource = new TempFileChapterAudioEncodedResource(
                outputFile,
                "audio/mpeg",
                Files.size(outputFile),
                ignored -> rawStream,
                Files::deleteIfExists
        );

        InputStream stream = resource.openStream();

        assertThatThrownBy(stream::close)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("simulated close failure");
        assertThat(rawStream.closeCalls()).isEqualTo(1);
        stream.close();
        assertThat(rawStream.closeCalls()).isEqualTo(1);

        resource.close();
        assertThat(Files.exists(outputFile)).isFalse();
        resource.close();
    }

    @Test
    @DisplayName("JVM process runner streams source, closes stdin, and bounds drained diagnostics")
    void shouldStreamThroughJvmProcessRunnerAndBoundDiagnostics() throws Exception {
        byte[] diagnostics = new byte[6000];
        java.util.Arrays.fill(diagnostics, (byte) 'x');
        StubProcess process = StubProcess.completed(0, diagnostics);
        AtomicReference<List<String>> startedCommand = new AtomicReference<>();
        FfmpegMp3ChapterAudioEncoderAdapter.JvmEncoderProcessRunner runner =
                new FfmpegMp3ChapterAudioEncoderAdapter.JvmEncoderProcessRunner(command -> {
                    startedCommand.set(command);
                    return process;
                });
        List<String> command = List.of("ffmpeg path", "-i", "pipe:0", "output path.mp3");

        FfmpegMp3ChapterAudioEncoderAdapter.EncoderProcessResult result = runner.execute(
                command,
                new ByteArrayInputStream(SOURCE_WAV),
                Duration.ofSeconds(1)
        );

        assertThat(startedCommand.get()).containsExactlyElementsOf(command);
        assertThat(process.stdinBytes()).isEqualTo(SOURCE_WAV);
        assertThat(process.stdinClosed()).isTrue();
        assertThat(result.exitCode()).isZero();
        assertThat(result.diagnostics()).endsWith("... (truncated)");
        assertThat(result.diagnostics().length()).isLessThanOrEqualTo(4112);
    }

    @Test
    @DisplayName("JVM process runner accepts graceful termination after a timed-out process")
    void shouldGracefullyTerminateTimedOutJvmProcess() {
        StubProcess process = StubProcess.gracefullyTerminatesAfterTimeout();
        FfmpegMp3ChapterAudioEncoderAdapter.JvmEncoderProcessRunner runner =
                new FfmpegMp3ChapterAudioEncoderAdapter.JvmEncoderProcessRunner(command -> process);

        assertThatThrownBy(() -> runner.execute(
                List.of("ffmpeg", "output.mp3"),
                new ByteArrayInputStream(SOURCE_WAV),
                Duration.ofMillis(10)
        )).isInstanceOf(TimeoutException.class);
        assertThat(process.destroyCalled()).isTrue();
        assertThat(process.destroyForciblyCalled()).isFalse();
        assertThat(process.waitCallCount()).isEqualTo(2);
        assertThat(process.isAlive()).isFalse();
        assertThat(process.processStreamsClosed()).isTrue();
    }

    @Test
    @DisplayName("JVM process runner bounded-waits for forced termination after graceful termination fails")
    void shouldBoundAndVerifyForcedTermination() {
        StubProcess process = StubProcess.terminatesAfterForcedWait();
        FfmpegMp3ChapterAudioEncoderAdapter.JvmEncoderProcessRunner runner =
                new FfmpegMp3ChapterAudioEncoderAdapter.JvmEncoderProcessRunner(command -> process);

        assertThatThrownBy(() -> runner.execute(
                List.of("ffmpeg", "output.mp3"),
                new ByteArrayInputStream(SOURCE_WAV),
                Duration.ofMillis(10)
        )).isInstanceOf(TimeoutException.class)
                .satisfies(failure -> assertThat(failure.getSuppressed()).isEmpty());
        assertThat(process.destroyCalled()).isTrue();
        assertThat(process.destroyForciblyCalled()).isTrue();
        assertThat(process.waitCallCount()).isEqualTo(3);
        assertThat(process.isAlive()).isFalse();
        assertThat(process.processStreamsClosed()).isTrue();
    }

    @Test
    @DisplayName("forced termination failure is suppressed under the original timeout")
    void shouldPreserveTimeoutWhenForcedTerminationFails() {
        StubProcess process = StubProcess.remainsAliveAfterForcedWait();
        FfmpegMp3ChapterAudioEncoderAdapter.JvmEncoderProcessRunner runner =
                new FfmpegMp3ChapterAudioEncoderAdapter.JvmEncoderProcessRunner(command -> process);

        assertThatThrownBy(() -> runner.execute(
                List.of("ffmpeg", "output.mp3"),
                new ByteArrayInputStream(SOURCE_WAV),
                Duration.ofMillis(10)
        )).isInstanceOf(TimeoutException.class)
                .satisfies(failure -> {
                    assertThat(failure.getSuppressed()).hasSize(1);
                    assertThat(failure.getSuppressed()[0])
                            .isInstanceOf(IOException.class)
                            .hasMessageContaining("remained alive");
                });
        assertThat(process.destroyCalled()).isTrue();
        assertThat(process.destroyForciblyCalled()).isTrue();
        assertThat(process.waitCallCount()).isEqualTo(3);
        assertThat(process.isAlive()).isTrue();
        assertThat(process.processStreamsClosed()).isTrue();
    }

    @Test
    @DisplayName("JVM process runner reports process start failure clearly")
    void shouldReportJvmProcessStartFailure() {
        IOException startFailure = new IOException("executable not found");
        FfmpegMp3ChapterAudioEncoderAdapter.JvmEncoderProcessRunner runner =
                new FfmpegMp3ChapterAudioEncoderAdapter.JvmEncoderProcessRunner(command -> {
                    throw startFailure;
                });

        assertThatThrownBy(() -> runner.execute(
                List.of("ffmpeg", "output.mp3"),
                new ByteArrayInputStream(SOURCE_WAV),
                Duration.ofSeconds(1)
        )).isInstanceOf(IOException.class)
                .hasMessageContaining("Failed to start")
                .hasCause(startFailure);
    }

    @Test
    @DisplayName("JVM process runner preserves interruption while bounded-waiting for forced termination")
    void shouldTerminateJvmProcessWhenInterrupted() {
        StubProcess process = StubProcess.interrupted();
        FfmpegMp3ChapterAudioEncoderAdapter.JvmEncoderProcessRunner runner =
                new FfmpegMp3ChapterAudioEncoderAdapter.JvmEncoderProcessRunner(command -> process);

        assertThatThrownBy(() -> runner.execute(
                List.of("ffmpeg", "output.mp3"),
                new ByteArrayInputStream(SOURCE_WAV),
                Duration.ofSeconds(1)
        )).isInstanceOf(InterruptedException.class);
        assertThat(process.destroyCalled()).isTrue();
        assertThat(process.destroyForciblyCalled()).isTrue();
        assertThat(process.waitCallCount()).isEqualTo(3);
        assertThat(process.isAlive()).isFalse();
        assertThat(process.processStreamsClosed()).isTrue();
    }

    private static FfmpegMp3ChapterAudioEncoderAdapter adapter(
            Path outputFile,
            String executable,
            FfmpegMp3ChapterAudioEncoderAdapter.EncoderProcessRunner runner
    ) {
        return new FfmpegMp3ChapterAudioEncoderAdapter(
                executable,
                ENCODING_TIMEOUT,
                () -> outputFile,
                Files::deleteIfExists,
                runner
        );
    }

    private static ChapterAudioEncodingRequest request() {
        return new ChapterAudioEncodingRequest(new TrackingAssemblyResource("audio/wav", SOURCE_WAV));
    }

    private static byte[] pcm16Wav(short[] samples) {
        int dataSize = samples.length * 2;
        ByteArrayOutputStream output = new ByteArrayOutputStream(44 + dataSize);
        output.writeBytes(new byte[]{'R', 'I', 'F', 'F'});
        writeLittleEndianInt(output, 36 + dataSize);
        output.writeBytes(new byte[]{'W', 'A', 'V', 'E'});
        output.writeBytes(new byte[]{'f', 'm', 't', ' '});
        writeLittleEndianInt(output, 16);
        writeLittleEndianShort(output, 1);
        writeLittleEndianShort(output, 1);
        writeLittleEndianInt(output, 8000);
        writeLittleEndianInt(output, 16000);
        writeLittleEndianShort(output, 2);
        writeLittleEndianShort(output, 16);
        output.writeBytes(new byte[]{'d', 'a', 't', 'a'});
        writeLittleEndianInt(output, dataSize);
        for (short sample : samples) {
            writeLittleEndianShort(output, sample);
        }
        return output.toByteArray();
    }

    private static void writeLittleEndianInt(ByteArrayOutputStream output, int value) {
        output.write(value & 0xFF);
        output.write((value >>> 8) & 0xFF);
        output.write((value >>> 16) & 0xFF);
        output.write((value >>> 24) & 0xFF);
    }

    private static void writeLittleEndianShort(ByteArrayOutputStream output, int value) {
        output.write(value & 0xFF);
        output.write((value >>> 8) & 0xFF);
    }

    private static final class TrackingAssemblyResource implements ChapterAudioAssemblyResource {

        private final String mimeType;
        private final byte[] bytes;
        private int openCount;
        private int activeStreams;
        private int closedStreamCount;
        private boolean resourceClosed;

        private TrackingAssemblyResource(String mimeType, byte[] bytes) {
            this.mimeType = mimeType;
            this.bytes = bytes.clone();
        }

        @Override
        public String mimeType() {
            return mimeType;
        }

        @Override
        public long sizeBytes() {
            return bytes.length;
        }

        @Override
        public synchronized InputStream openStream() {
            if (resourceClosed) {
                throw new IllegalStateException("source resource closed");
            }
            openCount++;
            activeStreams++;
            return new FilterInputStream(new ByteArrayInputStream(bytes)) {
                private boolean closed;

                @Override
                public void close() throws IOException {
                    if (closed) {
                        return;
                    }
                    closed = true;
                    try {
                        super.close();
                    } finally {
                        synchronized (TrackingAssemblyResource.this) {
                            activeStreams--;
                            closedStreamCount++;
                        }
                    }
                }
            };
        }

        @Override
        public synchronized void close() {
            if (activeStreams > 0) {
                throw new IllegalStateException("source has open streams");
            }
            resourceClosed = true;
        }

        private synchronized int openCount() {
            return openCount;
        }

        private synchronized int closedStreamCount() {
            return closedStreamCount;
        }

        private synchronized boolean resourceClosed() {
            return resourceClosed;
        }
    }

    private static final class CloseThrowingInputStream extends ByteArrayInputStream {

        private int closeCalls;

        private CloseThrowingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closeCalls++;
            throw new IOException("simulated close failure");
        }

        private int closeCalls() {
            return closeCalls;
        }
    }

    private static final class StubProcess extends Process {

        private enum WaitOutcome {
            TERMINATED,
            TIMED_OUT,
            INTERRUPTED
        }

        private final TrackingOutputStream stdin = new TrackingOutputStream();
        private final TrackingInputStream diagnostics;
        private final TrackingInputStream errors = new TrackingInputStream(new byte[0]);
        private final int exitCode;
        private final List<WaitOutcome> waitOutcomes;
        private int waitCallCount;
        private boolean alive = true;
        private boolean destroyCalled;
        private boolean destroyForciblyCalled;

        private StubProcess(int exitCode, byte[] diagnostics, List<WaitOutcome> waitOutcomes) {
            this.exitCode = exitCode;
            this.diagnostics = new TrackingInputStream(diagnostics);
            this.waitOutcomes = List.copyOf(waitOutcomes);
        }

        private static StubProcess completed(int exitCode, byte[] diagnostics) {
            return new StubProcess(exitCode, diagnostics, List.of(WaitOutcome.TERMINATED));
        }

        private static StubProcess gracefullyTerminatesAfterTimeout() {
            return new StubProcess(0, new byte[0], List.of(
                    WaitOutcome.TIMED_OUT,
                    WaitOutcome.TERMINATED
            ));
        }

        private static StubProcess terminatesAfterForcedWait() {
            return new StubProcess(0, new byte[0], List.of(
                    WaitOutcome.TIMED_OUT,
                    WaitOutcome.TIMED_OUT,
                    WaitOutcome.TERMINATED
            ));
        }

        private static StubProcess remainsAliveAfterForcedWait() {
            return new StubProcess(0, new byte[0], List.of(
                    WaitOutcome.TIMED_OUT,
                    WaitOutcome.TIMED_OUT,
                    WaitOutcome.TIMED_OUT
            ));
        }

        private static StubProcess interrupted() {
            return new StubProcess(0, new byte[0], List.of(
                    WaitOutcome.INTERRUPTED,
                    WaitOutcome.TIMED_OUT,
                    WaitOutcome.TERMINATED
            ));
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            return diagnostics;
        }

        @Override
        public InputStream getErrorStream() {
            return errors;
        }

        @Override
        public int waitFor() {
            throw new AssertionError("Unbounded Process.waitFor() must not be used.");
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            int outcomeIndex = Math.min(waitCallCount, waitOutcomes.size() - 1);
            WaitOutcome outcome = waitOutcomes.get(outcomeIndex);
            waitCallCount++;
            if (outcome == WaitOutcome.INTERRUPTED) {
                throw new InterruptedException("simulated process wait interruption");
            }
            if (outcome == WaitOutcome.TERMINATED) {
                alive = false;
                return true;
            }
            return false;
        }

        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException("process is still alive");
            }
            return exitCode;
        }

        @Override
        public void destroy() {
            destroyCalled = true;
        }

        @Override
        public Process destroyForcibly() {
            destroyForciblyCalled = true;
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        private byte[] stdinBytes() {
            return stdin.toByteArray();
        }

        private boolean stdinClosed() {
            return stdin.closed();
        }

        private boolean destroyCalled() {
            return destroyCalled;
        }

        private boolean destroyForciblyCalled() {
            return destroyForciblyCalled;
        }

        private int waitCallCount() {
            return waitCallCount;
        }

        private boolean processStreamsClosed() {
            return stdin.closed() && diagnostics.closed() && errors.closed();
        }
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {

        private boolean closed;

        private TrackingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        private boolean closed() {
            return closed;
        }
    }

    private static final class TrackingOutputStream extends ByteArrayOutputStream {

        private boolean closed;

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        private boolean closed() {
            return closed;
        }
    }
}
