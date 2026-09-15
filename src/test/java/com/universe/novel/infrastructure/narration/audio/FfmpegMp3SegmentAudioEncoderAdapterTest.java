package com.universe.novel.infrastructure.narration.audio;

import com.universe.novel.application.narration.SegmentAudioEncodingRequest;
import com.universe.novel.application.narration.SegmentAudioEncodingResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.universe.novel.infrastructure.narration.concurrency.NarrationFfmpegExecutionGate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FFmpeg MP3 Segment Audio Encoder Adapter Tests")
class FfmpegMp3SegmentAudioEncoderAdapterTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final byte[] SOURCE_WAV = pcm16Wav(new short[]{0, 100, -100, 0});
    private static final byte[] ENCODED_MP3 = new byte[]{'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4};
    private final NarrationFfmpegExecutionGate gate = new NarrationFfmpegExecutionGate(1);

    @Test
    @DisplayName("1 & 3 & 4 & 5 & 6 & 17: canonical FFmpeg command, output MIME, ownership, samples and sampleRate, no shell wrapper")
    void shouldEncodeWithCanonicalCommandAndDeriveContributionSamples() throws IOException {
        Path outputFile = Files.createTempFile("seg_test_success_", ".mp3");
        AtomicReference<List<String>> capturedCommand = new AtomicReference<>();
        AtomicReference<Duration> capturedTimeout = new AtomicReference<>();
        String ffmpegPath = "C:\\Tools\\ffmpeg.exe";
        String ffprobePath = "C:\\Tools\\ffprobe.exe";

        TrackingBinarySource source = new TrackingBinarySource(SOURCE_WAV);
        SegmentAudioEncodingRequest request = new SegmentAudioEncodingRequest("audio/wav; charset=binary", source);

        FfmpegMp3SegmentAudioEncoderAdapter adapter = new FfmpegMp3SegmentAudioEncoderAdapter(
                ffmpegPath,
                ffprobePath,
                TIMEOUT,
                () -> outputFile,
                Files::deleteIfExists,
                (command, stream, timeout) -> {
                    capturedCommand.set(command);
                    capturedTimeout.set(timeout);
                    assertThat(stream.readAllBytes()).isEqualTo(SOURCE_WAV);
                    Files.write(outputFile, ENCODED_MP3);
                    return new FfmpegMp3SegmentAudioEncoderAdapter.EncoderProcessResult(0, "");
                },
                (audioFile, timeout) -> {
                    assertThat(audioFile).isEqualTo(outputFile);
                    assertThat(timeout).isEqualTo(TIMEOUT);
                    return new FfmpegMp3SegmentAudioEncoderAdapter.SegmentProbeData("mp3", 48000, 1, 45);
                },
                gate
        );

        SegmentAudioEncodingResult result = adapter.encode(request);

        // MIME & dimensions
        assertThat(result.mimeType()).isEqualTo("audio/mpeg");
        assertThat(result.resource().mimeType()).isEqualTo("audio/mpeg");
        assertThat(result.sizeBytes()).isEqualTo(ENCODED_MP3.length);
        assertThat(result.sampleRateHz()).isEqualTo(48000);
        assertThat(result.resource().sampleRateHz()).isEqualTo(48000);
        assertThat(result.encodedContributionSamples()).isEqualTo(45L * 1152L); // 51840 samples
        assertThat(result.resource().encodedContributionSamples()).isEqualTo(51840L);

        // Stream and source tracking
        assertThat(source.openCount()).isEqualTo(1);
        assertThat(source.closedStreamCount()).isEqualTo(1);
        assertThat(capturedTimeout.get()).isEqualTo(TIMEOUT);

        // Verify exact canonical command arguments
        assertThat(capturedCommand.get()).containsExactly(
                ffmpegPath,
                "-hide_banner",
                "-loglevel", "error",
                "-nostats",
                "-y",
                "-f", "wav",
                "-i", "pipe:0",
                "-map_metadata", "-1",
                "-map_chapters", "-1",
                "-id3v2_version", "0",
                "-vn",
                "-c:a", "libmp3lame",
                "-b:a", "96k",
                "-ar", "48000",
                "-ac", "1",
                "-f", "mp3",
                outputFile.toAbsolutePath().toString()
        );

        // No shell invocation
        assertThat(capturedCommand.get()).doesNotContain("cmd", "cmd.exe", "powershell", "powershell.exe", "sh", "/bin/sh", "-c");

        // Open stream and verify resource ownership
        try (InputStream stream = result.openStream()) {
            assertThat(stream.readAllBytes()).isEqualTo(ENCODED_MP3);
        }
        result.close();
        assertThat(Files.exists(outputFile)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"audio/wav", "audio/x-wav", "audio/wave", "audio/wav; charset=binary", "AUDIO/WAV"})
    @DisplayName("2: supported WAV MIME aliases are accepted")
    void shouldAcceptSupportedWavMimeAliases(String mimeType) throws IOException {
        Path outputFile = Files.createTempFile("seg_test_mime_", ".mp3");
        FfmpegMp3SegmentAudioEncoderAdapter adapter = adapterWithProbe(
                outputFile,
                new FfmpegMp3SegmentAudioEncoderAdapter.SegmentProbeData("mp3", 48000, 1, 10)
        );

        SegmentAudioEncodingResult result = adapter.encode(SegmentAudioEncodingRequest.of(mimeType, SOURCE_WAV));
        assertThat(result.mimeType()).isEqualTo("audio/mpeg");
        result.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"audio/mpeg", "audio/mp3", "audio/aac", "audio/ogg", "text/plain", "video/mp4", "application/json"})
    @DisplayName("2: unsupported non-blank source MIME is rejected before temp file creation or process execution")
    void shouldRejectUnsupportedSourceMime(String mimeType) {
        AtomicInteger tempCreations = new AtomicInteger();
        AtomicInteger processInvocations = new AtomicInteger();

        FfmpegMp3SegmentAudioEncoderAdapter adapter = new FfmpegMp3SegmentAudioEncoderAdapter(
                "ffmpeg",
                "ffprobe",
                TIMEOUT,
                () -> {
                    tempCreations.incrementAndGet();
                    return Path.of("unused.mp3");
                },
                Files::deleteIfExists,
                (cmd, stream, to) -> {
                    processInvocations.incrementAndGet();
                    return new FfmpegMp3SegmentAudioEncoderAdapter.EncoderProcessResult(0, "");
                },
                (file, to) -> new FfmpegMp3SegmentAudioEncoderAdapter.SegmentProbeData("mp3", 48000, 1, 10),
                gate
        );

        assertThatThrownBy(() -> adapter.encode(new SegmentAudioEncodingRequest(
                mimeType,
                () -> new ByteArrayInputStream(SOURCE_WAV)
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported segment audio encoding source MIME type: " + mimeType);

        assertThat(tempCreations).hasValue(0);
        assertThat(processInvocations).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("2b: SegmentAudioEncodingRequest rejects null or blank MIME explicitly")
    void shouldRejectBlankMimeInRequest(String blankMime) {
        assertThatThrownBy(() -> new SegmentAudioEncodingRequest(blankMime, () -> new ByteArrayInputStream(SOURCE_WAV)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mimeType must not be blank");
    }

    @Test
    @DisplayName("7: non-MP3 probe output is rejected and temp file cleaned")
    void shouldRejectNonMp3ProbeOutput() throws IOException {
        Path outputFile = Files.createTempFile("seg_test_non_mp3_", ".mp3");
        FfmpegMp3SegmentAudioEncoderAdapter adapter = adapterWithProbe(
                outputFile,
                new FfmpegMp3SegmentAudioEncoderAdapter.SegmentProbeData("aac", 48000, 1, 10)
        );

        assertThatThrownBy(() -> adapter.encode(SegmentAudioEncodingRequest.of("audio/wav", SOURCE_WAV)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unexpected audio codec: aac");
        assertThat(Files.exists(outputFile)).isFalse();
    }

    @Test
    @DisplayName("7: wrong sample rate probe output is rejected and temp file cleaned")
    void shouldRejectWrongSampleRateProbeOutput() throws IOException {
        Path outputFile = Files.createTempFile("seg_test_wrong_rate_", ".mp3");
        FfmpegMp3SegmentAudioEncoderAdapter adapter = adapterWithProbe(
                outputFile,
                new FfmpegMp3SegmentAudioEncoderAdapter.SegmentProbeData("mp3", 44100, 1, 10)
        );

        assertThatThrownBy(() -> adapter.encode(SegmentAudioEncodingRequest.of("audio/wav", SOURCE_WAV)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unexpected sample rate: 44100 Hz");
        assertThat(Files.exists(outputFile)).isFalse();
    }

    @Test
    @DisplayName("7: wrong channel count probe output is rejected and temp file cleaned")
    void shouldRejectWrongChannelsProbeOutput() throws IOException {
        Path outputFile = Files.createTempFile("seg_test_wrong_channels_", ".mp3");
        FfmpegMp3SegmentAudioEncoderAdapter adapter = adapterWithProbe(
                outputFile,
                new FfmpegMp3SegmentAudioEncoderAdapter.SegmentProbeData("mp3", 48000, 2, 10)
        );

        assertThatThrownBy(() -> adapter.encode(SegmentAudioEncodingRequest.of("audio/wav", SOURCE_WAV)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unexpected channel count: 2");
        assertThat(Files.exists(outputFile)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -5})
    @DisplayName("8: zero or negative packet count is rejected and temp file cleaned")
    void shouldRejectZeroOrNegativePacketCount(int invalidPackets) throws IOException {
        Path outputFile = Files.createTempFile("seg_test_invalid_packets_", ".mp3");
        FfmpegMp3SegmentAudioEncoderAdapter adapter = adapterWithProbe(
                outputFile,
                new FfmpegMp3SegmentAudioEncoderAdapter.SegmentProbeData("mp3", 48000, 1, invalidPackets)
        );

        assertThatThrownBy(() -> adapter.encode(SegmentAudioEncodingRequest.of("audio/wav", SOURCE_WAV)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid packet count");
        assertThat(Files.exists(outputFile)).isFalse();
    }

    @Test
    @DisplayName("9: FFmpeg non-zero exit reports diagnostics and cleans partial output")
    void shouldCleanOutputAfterNonZeroExit() throws IOException {
        Path outputFile = Files.createTempFile("seg_test_nonzero_", ".mp3");
        FfmpegMp3SegmentAudioEncoderAdapter adapter = new FfmpegMp3SegmentAudioEncoderAdapter(
                "ffmpeg",
                "ffprobe",
                TIMEOUT,
                () -> outputFile,
                Files::deleteIfExists,
                (cmd, stream, to) -> {
                    Files.write(outputFile, new byte[]{1, 2});
                    return new FfmpegMp3SegmentAudioEncoderAdapter.EncoderProcessResult(1, "pcm_s16le decoding error");
                },
                (file, to) -> new FfmpegMp3SegmentAudioEncoderAdapter.SegmentProbeData("mp3", 48000, 1, 10),
                gate
        );

        assertThatThrownBy(() -> adapter.encode(SegmentAudioEncodingRequest.of("audio/wav", SOURCE_WAV)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exit code 1")
                .hasMessageContaining("pcm_s16le decoding error");
        assertThat(Files.exists(outputFile)).isFalse();
    }

    @Test
    @DisplayName("10: FFmpeg process start failure cleans output")
    void shouldCleanOutputAfterProcessStartFailure() throws IOException {
        Path outputFile = Files.createTempFile("seg_test_start_fail_", ".mp3");
        FfmpegMp3SegmentAudioEncoderAdapter adapter = new FfmpegMp3SegmentAudioEncoderAdapter(
                "ffmpeg",
                "ffprobe",
                TIMEOUT,
                () -> outputFile,
                Files::deleteIfExists,
                (cmd, stream, to) -> {
                    throw new IOException("Cannot run program ffmpeg: No such file");
                },
                (file, to) -> new FfmpegMp3SegmentAudioEncoderAdapter.SegmentProbeData("mp3", 48000, 1, 10),
                gate
        );

        assertThatThrownBy(() -> adapter.encode(SegmentAudioEncodingRequest.of("audio/wav", SOURCE_WAV)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot run program ffmpeg");
        assertThat(Files.exists(outputFile)).isFalse();
    }

    @Test
    @DisplayName("11: FFmpeg timeout deletes partial output and throws IllegalStateException")
    void shouldCleanOutputAfterTimeout() throws IOException {
        Path outputFile = Files.createTempFile("seg_test_timeout_", ".mp3");
        FfmpegMp3SegmentAudioEncoderAdapter adapter = new FfmpegMp3SegmentAudioEncoderAdapter(
                "ffmpeg",
                "ffprobe",
                TIMEOUT,
                () -> outputFile,
                Files::deleteIfExists,
                (cmd, stream, to) -> {
                    Files.write(outputFile, new byte[]{1, 2, 3});
                    throw new TimeoutException("simulated FFmpeg timeout");
                },
                (file, to) -> new FfmpegMp3SegmentAudioEncoderAdapter.SegmentProbeData("mp3", 48000, 1, 10),
                gate
        );

        assertThatThrownBy(() -> adapter.encode(SegmentAudioEncodingRequest.of("audio/wav", SOURCE_WAV)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("timed out");
        assertThat(Files.exists(outputFile)).isFalse();
    }

    @Test
    @DisplayName("12: Interruption restores thread interrupt flag and cleans temp file")
    void shouldRestoreInterruptFlagOnInterruption() throws IOException {
        Path outputFile = Files.createTempFile("seg_test_interrupted_", ".mp3");
        FfmpegMp3SegmentAudioEncoderAdapter adapter = new FfmpegMp3SegmentAudioEncoderAdapter(
                "ffmpeg",
                "ffprobe",
                TIMEOUT,
                () -> outputFile,
                Files::deleteIfExists,
                (cmd, stream, to) -> {
                    throw new InterruptedException("simulated interrupt");
                },
                (file, to) -> new FfmpegMp3SegmentAudioEncoderAdapter.SegmentProbeData("mp3", 48000, 1, 10),
                gate
        );

        assertThatThrownBy(() -> adapter.encode(SegmentAudioEncodingRequest.of("audio/wav", SOURCE_WAV)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("interrupted");
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted(); // clear interrupted state for test runner
        assertThat(Files.exists(outputFile)).isFalse();
    }

    @Test
    @DisplayName("13: Partial output cleanup on missing produced file or empty file")
    void shouldCleanOnEmptyProducedFile() throws IOException {
        Path outputFile = Files.createTempFile("seg_test_empty_", ".mp3");
        // Leave file empty (0 bytes)
        FfmpegMp3SegmentAudioEncoderAdapter adapter = new FfmpegMp3SegmentAudioEncoderAdapter(
                "ffmpeg",
                "ffprobe",
                TIMEOUT,
                () -> outputFile,
                Files::deleteIfExists,
                (cmd, stream, to) -> new FfmpegMp3SegmentAudioEncoderAdapter.EncoderProcessResult(0, ""),
                (file, to) -> new FfmpegMp3SegmentAudioEncoderAdapter.SegmentProbeData("mp3", 48000, 1, 10),
                gate
        );

        assertThatThrownBy(() -> adapter.encode(SegmentAudioEncodingRequest.of("audio/wav", SOURCE_WAV)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty encoded segment audio file");
        assertThat(Files.exists(outputFile)).isFalse();
    }

    @Test
    @DisplayName("14: Cleanup failure is added as suppressed exception")
    void shouldSuppressCleanupFailure() throws IOException {
        Path outputFile = Files.createTempFile("seg_test_cleanup_fail_", ".mp3");
        IOException cleanupError = new IOException("Disk permission error during delete");

        FfmpegMp3SegmentAudioEncoderAdapter adapter = new FfmpegMp3SegmentAudioEncoderAdapter(
                "ffmpeg",
                "ffprobe",
                TIMEOUT,
                () -> outputFile,
                path -> {
                    throw cleanupError;
                },
                (cmd, stream, to) -> {
                    throw new IOException("FFmpeg process execution failed");
                },
                (file, to) -> new FfmpegMp3SegmentAudioEncoderAdapter.SegmentProbeData("mp3", 48000, 1, 10),
                gate
        );

        assertThatThrownBy(() -> adapter.encode(SegmentAudioEncodingRequest.of("audio/wav", SOURCE_WAV)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FFmpeg")
                .satisfies(ex -> assertThat(ex.getSuppressed()).contains(cleanupError));

        Files.deleteIfExists(outputFile);
    }

    @Test
    @DisplayName("15: FFprobe failure cleans temp file and reports failure")
    void shouldCleanOutputAfterFfprobeFailure() throws IOException {
        Path outputFile = Files.createTempFile("seg_test_probe_fail_", ".mp3");
        FfmpegMp3SegmentAudioEncoderAdapter adapter = new FfmpegMp3SegmentAudioEncoderAdapter(
                "ffmpeg",
                "ffprobe",
                TIMEOUT,
                () -> outputFile,
                Files::deleteIfExists,
                (cmd, stream, to) -> {
                    Files.write(outputFile, ENCODED_MP3);
                    return new FfmpegMp3SegmentAudioEncoderAdapter.EncoderProcessResult(0, "");
                },
                (file, to) -> {
                    throw new IOException("ffprobe stream parse failure");
                },
                gate
        );

        assertThatThrownBy(() -> adapter.encode(SegmentAudioEncodingRequest.of("audio/wav", SOURCE_WAV)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to probe encoded segment audio with FFprobe");
        assertThat(Files.exists(outputFile)).isFalse();
    }

    @Test
    @DisplayName("16: Resource stream and close lifecycle: cannot close with open stream, idempotent close, closed rejection")
    void shouldEnforceResourceStreamAndCloseLifecycle() throws IOException {
        Path tempFile = Files.createTempFile("seg_lifecycle_", ".mp3");
        Files.write(tempFile, ENCODED_MP3);

        TempFileSegmentAudioEncodedResource resource = new TempFileSegmentAudioEncodedResource(
                tempFile,
                "audio/mpeg",
                ENCODED_MP3.length,
                51840L,
                48000
        );

        InputStream s1 = resource.openStream();
        InputStream s2 = resource.openStream();

        // Cannot close while streams are active
        assertThatThrownBy(resource::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("open stream(s)");
        assertThat(Files.exists(tempFile)).isTrue();

        s1.close();
        // Still 1 open stream
        assertThatThrownBy(resource::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1 open stream(s)");

        s2.close();

        // Now can close
        resource.close();
        assertThat(Files.exists(tempFile)).isFalse();

        // Idempotent close
        resource.close();

        // Cannot open stream after closing
        assertThatThrownBy(resource::openStream)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already been closed");
    }

    // =========================================================================
    // BLOCKER 2 TESTS: JvmSegmentAudioProbeRunner & JvmProbeProcessRunner Tests
    // =========================================================================

    @Test
    @DisplayName("Probe 1 & 2: exact ffprobe command args, no shell wrapper, successful JSON parsing")
    void jvmProbeRunner_shouldExecuteExactCommandAndParseSuccessfulJson() throws Exception {
        Path sampleAudio = Files.createTempFile("probe_test_sample_", ".mp3");
        AtomicReference<List<String>> capturedCommand = new AtomicReference<>();
        AtomicReference<Duration> capturedTimeout = new AtomicReference<>();
        String ffprobePath = "C:\\Tools\\ffprobe.exe";

        String validJson = "{\n"
                + "  \"streams\": [\n"
                + "    {\n"
                + "      \"codec_name\": \"mp3\",\n"
                + "      \"sample_rate\": \"48000\",\n"
                + "      \"channels\": 1,\n"
                + "      \"nb_read_packets\": \"45\"\n"
                + "    }\n"
                + "  ]\n"
                + "}";

        FfmpegMp3SegmentAudioEncoderAdapter.JvmSegmentAudioProbeRunner runner =
                new FfmpegMp3SegmentAudioEncoderAdapter.JvmSegmentAudioProbeRunner(
                        ffprobePath,
                        (command, timeout) -> {
                            capturedCommand.set(command);
                            capturedTimeout.set(timeout);
                            return new FfmpegMp3SegmentAudioEncoderAdapter.ProbeProcessResult(0, validJson);
                        }
                );

        FfmpegMp3SegmentAudioEncoderAdapter.SegmentProbeData probeData = runner.probe(sampleAudio, TIMEOUT);

        assertThat(capturedTimeout.get()).isEqualTo(TIMEOUT);
        assertThat(capturedCommand.get()).containsExactly(
                ffprobePath,
                "-hide_banner",
                "-v", "error",
                "-select_streams", "a:0",
                "-count_packets",
                "-show_entries", "stream=codec_name,sample_rate,channels,nb_read_packets",
                "-of", "json",
                sampleAudio.toAbsolutePath().toString()
        );
        assertThat(capturedCommand.get()).doesNotContain("cmd", "cmd.exe", "powershell", "sh", "/bin/sh", "-c");

        assertThat(probeData.codecName()).isEqualTo("mp3");
        assertThat(probeData.sampleRateHz()).isEqualTo(48000);
        assertThat(probeData.channels()).isEqualTo(1);
        assertThat(probeData.packetCount()).isEqualTo(45);

        Files.deleteIfExists(sampleAudio);
    }

    @Test
    @DisplayName("Probe 3: missing streams array fails clearly")
    void jvmProbeRunner_shouldFailOnMissingStreamsArray() throws IOException {
        Path sampleAudio = Files.createTempFile("probe_test_missing_stream_", ".mp3");
        FfmpegMp3SegmentAudioEncoderAdapter.JvmSegmentAudioProbeRunner runner =
                new FfmpegMp3SegmentAudioEncoderAdapter.JvmSegmentAudioProbeRunner(
                        "ffprobe",
                        (command, timeout) -> new FfmpegMp3SegmentAudioEncoderAdapter.ProbeProcessResult(0, "{\"programs\": []}")
                );

        assertThatThrownBy(() -> runner.probe(sampleAudio, TIMEOUT))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("contains no audio streams");

        Files.deleteIfExists(sampleAudio);
    }

    @Test
    @DisplayName("Probe 4: malformed JSON fails clearly")
    void jvmProbeRunner_shouldFailOnMalformedJson() throws IOException {
        Path sampleAudio = Files.createTempFile("probe_test_malformed_", ".mp3");
        FfmpegMp3SegmentAudioEncoderAdapter.JvmSegmentAudioProbeRunner runner =
                new FfmpegMp3SegmentAudioEncoderAdapter.JvmSegmentAudioProbeRunner(
                        "ffprobe",
                        (command, timeout) -> new FfmpegMp3SegmentAudioEncoderAdapter.ProbeProcessResult(0, "{ not valid json")
                );

        assertThatThrownBy(() -> runner.probe(sampleAudio, TIMEOUT))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not valid JSON");

        Files.deleteIfExists(sampleAudio);
    }

    @Test
    @DisplayName("Probe 5: missing nb_read_packets fails clearly without fallback")
    void jvmProbeRunner_shouldFailOnMissingNbReadPackets() throws IOException {
        Path sampleAudio = Files.createTempFile("probe_test_missing_pkts_", ".mp3");
        String jsonMissingPackets = "{\"streams\": [{\"codec_name\": \"mp3\", \"sample_rate\": 48000, \"channels\": 1}]}";
        FfmpegMp3SegmentAudioEncoderAdapter.JvmSegmentAudioProbeRunner runner =
                new FfmpegMp3SegmentAudioEncoderAdapter.JvmSegmentAudioProbeRunner(
                        "ffprobe",
                        (command, timeout) -> new FfmpegMp3SegmentAudioEncoderAdapter.ProbeProcessResult(0, jsonMissingPackets)
                );

        assertThatThrownBy(() -> runner.probe(sampleAudio, TIMEOUT))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("missing nb_read_packets");

        Files.deleteIfExists(sampleAudio);
    }

    @Test
    @DisplayName("Probe 6: nb_read_packets = 0 fails clearly")
    void jvmProbeRunner_shouldFailOnZeroNbReadPackets() throws IOException {
        Path sampleAudio = Files.createTempFile("probe_test_zero_pkts_", ".mp3");
        String jsonZeroPackets = "{\"streams\": [{\"codec_name\": \"mp3\", \"sample_rate\": 48000, \"channels\": 1, \"nb_read_packets\": \"0\"}]}";
        FfmpegMp3SegmentAudioEncoderAdapter.JvmSegmentAudioProbeRunner runner =
                new FfmpegMp3SegmentAudioEncoderAdapter.JvmSegmentAudioProbeRunner(
                        "ffprobe",
                        (command, timeout) -> new FfmpegMp3SegmentAudioEncoderAdapter.ProbeProcessResult(0, jsonZeroPackets)
                );

        assertThatThrownBy(() -> runner.probe(sampleAudio, TIMEOUT))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("invalid packet count: 0");

        Files.deleteIfExists(sampleAudio);
    }

    @ParameterizedTest
    @ValueSource(strings = {"N/A", "unknown", "invalid", "", "   "})
    @DisplayName("Probe 7: invalid or non-numeric nb_read_packets fails clearly")
    void jvmProbeRunner_shouldFailOnNonNumericNbReadPackets(String nonNumericValue) throws IOException {
        Path sampleAudio = Files.createTempFile("probe_test_non_numeric_", ".mp3");
        String json = "{\"streams\": [{\"codec_name\": \"mp3\", \"sample_rate\": 48000, \"channels\": 1, \"nb_read_packets\": \"" + nonNumericValue + "\"}]}";
        FfmpegMp3SegmentAudioEncoderAdapter.JvmSegmentAudioProbeRunner runner =
                new FfmpegMp3SegmentAudioEncoderAdapter.JvmSegmentAudioProbeRunner(
                        "ffprobe",
                        (command, timeout) -> new FfmpegMp3SegmentAudioEncoderAdapter.ProbeProcessResult(0, json)
                );

        assertThatThrownBy(() -> runner.probe(sampleAudio, TIMEOUT))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("non-numeric nb_read_packets");

        Files.deleteIfExists(sampleAudio);
    }

    @Test
    @DisplayName("Probe 8: ffprobe non-zero exit fails clearly")
    void jvmProbeRunner_shouldFailOnNonZeroExit() throws IOException {
        Path sampleAudio = Files.createTempFile("probe_test_exit_err_", ".mp3");
        FfmpegMp3SegmentAudioEncoderAdapter.JvmSegmentAudioProbeRunner runner =
                new FfmpegMp3SegmentAudioEncoderAdapter.JvmSegmentAudioProbeRunner(
                        "ffprobe",
                        (command, timeout) -> new FfmpegMp3SegmentAudioEncoderAdapter.ProbeProcessResult(1, "moov atom not found")
                );

        assertThatThrownBy(() -> runner.probe(sampleAudio, TIMEOUT))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exit code 1")
                .hasMessageContaining("moov atom not found");

        Files.deleteIfExists(sampleAudio);
    }

    @Test
    @DisplayName("Probe 9: ffprobe process start failure propagates IOException")
    void jvmProbeRunner_shouldPropagateStartFailure() throws IOException {
        Path sampleAudio = Files.createTempFile("probe_test_start_err_", ".mp3");
        FfmpegMp3SegmentAudioEncoderAdapter.JvmSegmentAudioProbeRunner runner =
                new FfmpegMp3SegmentAudioEncoderAdapter.JvmSegmentAudioProbeRunner(
                        "ffprobe",
                        (command, timeout) -> {
                            throw new IOException("Cannot run program ffprobe");
                        }
                );

        assertThatThrownBy(() -> runner.probe(sampleAudio, TIMEOUT))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Cannot run program ffprobe");

        Files.deleteIfExists(sampleAudio);
    }

    @Test
    @DisplayName("Probe 10: ffprobe timeout propagates TimeoutException and outer adapter wraps in IllegalStateException")
    void jvmProbeRunner_shouldPropagateTimeoutAndCleanInAdapter() throws IOException {
        Path outputFile = Files.createTempFile("probe_test_timeout_outer_", ".mp3");
        FfmpegMp3SegmentAudioEncoderAdapter adapter = new FfmpegMp3SegmentAudioEncoderAdapter(
                "ffmpeg",
                "ffprobe",
                TIMEOUT,
                () -> outputFile,
                Files::deleteIfExists,
                (cmd, stream, to) -> {
                    Files.write(outputFile, ENCODED_MP3);
                    return new FfmpegMp3SegmentAudioEncoderAdapter.EncoderProcessResult(0, "");
                },
                new FfmpegMp3SegmentAudioEncoderAdapter.JvmSegmentAudioProbeRunner(
                        "ffprobe",
                        (cmd, to) -> {
                            throw new TimeoutException("simulated FFprobe timeout");
                        }
                ),
                gate
        );

        assertThatThrownBy(() -> adapter.encode(SegmentAudioEncodingRequest.of("audio/wav", SOURCE_WAV)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FFprobe segment audio inspection timed out");
        assertThat(Files.exists(outputFile)).isFalse();
    }

    @Test
    @DisplayName("Probe 11: ffprobe interruption restores thread interrupt state and cleans temp file")
    void jvmProbeRunner_shouldRestoreInterruptStateOnProbeInterruption() throws IOException {
        Path outputFile = Files.createTempFile("probe_test_interrupt_outer_", ".mp3");
        FfmpegMp3SegmentAudioEncoderAdapter adapter = new FfmpegMp3SegmentAudioEncoderAdapter(
                "ffmpeg",
                "ffprobe",
                TIMEOUT,
                () -> outputFile,
                Files::deleteIfExists,
                (cmd, stream, to) -> {
                    Files.write(outputFile, ENCODED_MP3);
                    return new FfmpegMp3SegmentAudioEncoderAdapter.EncoderProcessResult(0, "");
                },
                new FfmpegMp3SegmentAudioEncoderAdapter.JvmSegmentAudioProbeRunner(
                        "ffprobe",
                        (cmd, to) -> {
                            throw new InterruptedException("simulated probe interrupt");
                        }
                ),
                gate
        );

        assertThatThrownBy(() -> adapter.encode(SegmentAudioEncodingRequest.of("audio/wav", SOURCE_WAV)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FFprobe segment audio inspection was interrupted");
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted(); // clear for test runner
        assertThat(Files.exists(outputFile)).isFalse();
    }

    // =========================================================================
    // JvmProbeProcessRunner tests (Process lifecycle, timeout & stream safety)
    // =========================================================================

    @Test
    @DisplayName("ProcessRunner: start failure throws IOException")
    void jvmProbeProcessRunner_shouldThrowOnStartFailure() {
        FfmpegMp3SegmentAudioEncoderAdapter.JvmProbeProcessRunner runner =
                new FfmpegMp3SegmentAudioEncoderAdapter.JvmProbeProcessRunner(
                        cmd -> {
                            throw new IOException("Failed to fork");
                        }
                );

        assertThatThrownBy(() -> runner.execute(List.of("ffprobe"), Duration.ofSeconds(1)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Failed to start FFprobe process");
    }

    @Test
    @DisplayName("ProcessRunner: timeout terminates process and throws TimeoutException")
    void jvmProbeProcessRunner_shouldTimeoutAndTerminateProcess() {
        AtomicBoolean destroyed = new AtomicBoolean();
        FfmpegMp3SegmentAudioEncoderAdapter.JvmProbeProcessRunner runner =
                new FfmpegMp3SegmentAudioEncoderAdapter.JvmProbeProcessRunner(
                        cmd -> new FakeProcess() {
                            private boolean alive = true;

                            @Override
                            public boolean isAlive() {
                                return alive;
                            }

                            @Override
                            public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
                                return !alive; // returns true once destroyed
                            }

                            @Override
                            public void destroy() {
                                alive = false;
                                destroyed.set(true);
                            }

                            @Override
                            public Process destroyForcibly() {
                                alive = false;
                                destroyed.set(true);
                                return this;
                            }
                        }
                );

        assertThatThrownBy(() -> runner.execute(List.of("ffprobe"), Duration.ofMillis(50)))
                .isInstanceOf(TimeoutException.class)
                .hasMessageContaining("exceeded its timeout");
        assertThat(destroyed.get()).isTrue();
    }

    @Test
    @DisplayName("ProcessRunner: reader error is not silently ignored")
    void jvmProbeProcessRunner_shouldPropagateReaderError() {
        FfmpegMp3SegmentAudioEncoderAdapter.JvmProbeProcessRunner runner =
                new FfmpegMp3SegmentAudioEncoderAdapter.JvmProbeProcessRunner(
                        cmd -> new FakeProcess() {
                            @Override
                            public InputStream getInputStream() {
                                return new InputStream() {
                                    @Override
                                    public int read() throws IOException {
                                        throw new IOException("Pipe broken during read");
                                    }
                                };
                            }
                        }
                );

        assertThatThrownBy(() -> runner.execute(List.of("ffprobe"), Duration.ofSeconds(2)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Failed to read FFprobe process output");
    }

    @Test
    @DisplayName("ProcessRunner: interruption while waiting for reader future propagates InterruptedException")
    void jvmProbeProcessRunner_shouldPropagateInterruptedExceptionDuringReaderWait() throws Exception {
        CountDownLatch pumpRunning = new CountDownLatch(1);
        AtomicBoolean destroyed = new AtomicBoolean();
        FakeProcess fakeProcess = new FakeProcess() {
            private boolean alive = true;

            @Override
            public boolean isAlive() {
                return alive;
            }

            @Override
            public boolean waitFor(long timeout, TimeUnit unit) {
                return true;
            }

            @Override
            public InputStream getInputStream() {
                return new InputStream() {
                    @Override
                    public int read() throws IOException {
                        pumpRunning.countDown();
                        try {
                            Thread.sleep(10_000);
                        } catch (InterruptedException e) {
                            return -1;
                        }
                        return -1;
                    }
                };
            }

            @Override
            public void destroy() {
                alive = false;
                destroyed.set(true);
            }

            @Override
            public Process destroyForcibly() {
                alive = false;
                destroyed.set(true);
                return this;
            }
        };

        FfmpegMp3SegmentAudioEncoderAdapter.JvmProbeProcessRunner runner =
                new FfmpegMp3SegmentAudioEncoderAdapter.JvmProbeProcessRunner(cmd -> fakeProcess);

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);

        Thread thread = new Thread(() -> {
            try {
                runner.execute(List.of("ffprobe"), Duration.ofSeconds(5));
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                finished.countDown();
            }
        }, "test-probe-interrupter");

        thread.start();
        assertThat(pumpRunning.await(2, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(50);
        thread.interrupt();

        assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(thrown.get())
                .isInstanceOf(InterruptedException.class);
        assertThat(destroyed.get()).isTrue();
    }

    @Test
    @DisplayName("ProcessRunner: exhausted timeout before reader completion throws TimeoutException")
    void jvmProbeProcessRunner_shouldThrowTimeoutExceptionWhenTimeoutExhaustedBeforeReaderDone() {
        AtomicBoolean destroyed = new AtomicBoolean();
        FakeProcess fakeProcess = new FakeProcess() {
            private boolean alive = true;

            @Override
            public boolean isAlive() {
                return alive;
            }

            @Override
            public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
                Thread.sleep(unit.toMillis(timeout) + 10);
                return true;
            }

            @Override
            public InputStream getInputStream() {
                return new InputStream() {
                    @Override
                    public int read() throws IOException {
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException e) {
                            return -1;
                        }
                        return -1;
                    }
                };
            }

            @Override
            public void destroy() {
                alive = false;
                destroyed.set(true);
            }

            @Override
            public Process destroyForcibly() {
                alive = false;
                destroyed.set(true);
                return this;
            }
        };

        FfmpegMp3SegmentAudioEncoderAdapter.JvmProbeProcessRunner runner =
                new FfmpegMp3SegmentAudioEncoderAdapter.JvmProbeProcessRunner(cmd -> fakeProcess);

        assertThatThrownBy(() -> runner.execute(List.of("ffprobe"), Duration.ofMillis(30)))
                .isInstanceOf(TimeoutException.class)
                .hasMessageContaining("draining output");
        assertThat(destroyed.get()).isTrue();
    }

    @Test
    @DisplayName("ProcessRunner: destroyForcibly is invoked when destroy fails to stop process")
    void jvmProbeProcessRunner_shouldForciblyDestroyWhenGracefulDestroyFails() {
        AtomicBoolean destroyCalled = new AtomicBoolean();
        AtomicBoolean destroyForciblyCalled = new AtomicBoolean();
        FakeProcess fakeProcess = new FakeProcess() {
            private boolean alive = true;

            @Override
            public boolean isAlive() {
                return alive;
            }

            @Override
            public boolean waitFor(long timeout, TimeUnit unit) {
                return !alive;
            }

            @Override
            public void destroy() {
                destroyCalled.set(true);
            }

            @Override
            public Process destroyForcibly() {
                destroyForciblyCalled.set(true);
                alive = false;
                return this;
            }
        };

        FfmpegMp3SegmentAudioEncoderAdapter.JvmProbeProcessRunner runner =
                new FfmpegMp3SegmentAudioEncoderAdapter.JvmProbeProcessRunner(cmd -> fakeProcess);

        assertThatThrownBy(() -> runner.execute(List.of("ffprobe"), Duration.ofMillis(30)))
                .isInstanceOf(TimeoutException.class)
                .hasMessageContaining("exceeded its timeout")
                .satisfies(thrown -> assertThat(thrown.getSuppressed()).isEmpty());

        assertThat(destroyCalled.get()).isTrue();
        assertThat(destroyForciblyCalled.get()).isTrue();
    }

    @Test
    @DisplayName("ProcessRunner: process remaining alive after forced termination attaches suppressed exception")
    void jvmProbeProcessRunner_shouldAttachSuppressedWhenForcedTerminationFails() {
        AtomicBoolean destroyCalled = new AtomicBoolean();
        AtomicBoolean destroyForciblyCalled = new AtomicBoolean();
        FakeProcess fakeProcess = new FakeProcess() {
            @Override
            public boolean isAlive() {
                return true;
            }

            @Override
            public boolean waitFor(long timeout, TimeUnit unit) {
                return false;
            }

            @Override
            public void destroy() {
                destroyCalled.set(true);
            }

            @Override
            public Process destroyForcibly() {
                destroyForciblyCalled.set(true);
                return this;
            }
        };

        FfmpegMp3SegmentAudioEncoderAdapter.JvmProbeProcessRunner runner =
                new FfmpegMp3SegmentAudioEncoderAdapter.JvmProbeProcessRunner(cmd -> fakeProcess);

        assertThatThrownBy(() -> runner.execute(List.of("ffprobe"), Duration.ofMillis(30)))
                .isInstanceOf(TimeoutException.class)
                .hasMessageContaining("exceeded its timeout")
                .satisfies(thrown -> {
                    assertThat(thrown.getSuppressed()).hasSize(1);
                    assertThat(thrown.getSuppressed()[0])
                            .isInstanceOf(IOException.class)
                            .hasMessageContaining("FFprobe process remained alive after forced termination");
                });

        assertThat(destroyCalled.get()).isTrue();
        assertThat(destroyForciblyCalled.get()).isTrue();
    }

    @Test
    @DisplayName("ProcessRunner: captured output is bounded to 64 KB")
    void jvmProbeProcessRunner_shouldBoundCapturedOutputTo64Kb() throws Exception {
        byte[] largeOutput = new byte[100 * 1024];
        Arrays.fill(largeOutput, (byte) 'A');

        FakeProcess fakeProcess = new FakeProcess() {
            @Override
            public InputStream getInputStream() {
                return new ByteArrayInputStream(largeOutput);
            }
        };

        FfmpegMp3SegmentAudioEncoderAdapter.JvmProbeProcessRunner runner =
                new FfmpegMp3SegmentAudioEncoderAdapter.JvmProbeProcessRunner(cmd -> fakeProcess);
        FfmpegMp3SegmentAudioEncoderAdapter.ProbeProcessResult result =
                runner.execute(List.of("ffprobe"), Duration.ofSeconds(2));

        assertThat(result.output().length()).isEqualTo(64 * 1024);
    }

    private static FfmpegMp3SegmentAudioEncoderAdapter adapterWithProbe(
            Path outputFile,
            FfmpegMp3SegmentAudioEncoderAdapter.SegmentProbeData probeData
    ) {
        return new FfmpegMp3SegmentAudioEncoderAdapter(
                "ffmpeg",
                "ffprobe",
                TIMEOUT,
                () -> outputFile,
                Files::deleteIfExists,
                (cmd, stream, to) -> {
                    Files.write(outputFile, ENCODED_MP3);
                    return new FfmpegMp3SegmentAudioEncoderAdapter.EncoderProcessResult(0, "");
                },
                (file, to) -> probeData,
                new NarrationFfmpegExecutionGate(1)
        );
    }

    private static byte[] pcm16Wav(short[] samples) {
        int channels = 1;
        int sampleRate = 48000;
        int bitsPerSample = 16;
        int blockAlign = channels * (bitsPerSample / 8);
        int byteRate = sampleRate * blockAlign;
        int dataSize = samples.length * 2;

        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + dataSize);
        try {
            out.write("RIFF".getBytes(StandardCharsets.US_ASCII));
            writeInt(out, 36 + dataSize);
            out.write("WAVE".getBytes(StandardCharsets.US_ASCII));
            out.write("fmt ".getBytes(StandardCharsets.US_ASCII));
            writeInt(out, 16);
            writeShort(out, 1);
            writeShort(out, channels);
            writeInt(out, sampleRate);
            writeInt(out, byteRate);
            writeShort(out, blockAlign);
            writeShort(out, bitsPerSample);
            out.write("data".getBytes(StandardCharsets.US_ASCII));
            writeInt(out, dataSize);
            for (short s : samples) {
                out.write(s & 0xFF);
                out.write((s >>> 8) & 0xFF);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static final class TrackingBinarySource implements com.universe.novel.application.narration.SegmentAudioBinarySource {
        private final byte[] data;
        private final AtomicInteger openCount = new AtomicInteger();
        private final AtomicInteger closedStreamCount = new AtomicInteger();

        TrackingBinarySource(byte[] data) {
            this.data = data;
        }

        @Override
        public InputStream openStream() {
            openCount.incrementAndGet();
            return new FilterInputStream(new ByteArrayInputStream(data)) {
                private final AtomicBoolean closed = new AtomicBoolean();

                @Override
                public void close() throws IOException {
                    if (closed.compareAndSet(false, true)) {
                        closedStreamCount.incrementAndGet();
                    }
                    super.close();
                }
            };
        }

        int openCount() {
            return openCount.get();
        }

        int closedStreamCount() {
            return closedStreamCount.get();
        }
    }

    private static class FakeProcess extends Process {
        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return true;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
        }

        @Override
        public Process destroyForcibly() {
            return this;
        }

        @Override
        public boolean isAlive() {
            return false;
        }
    }

    @Test
    @DisplayName("Segment encode executes through NarrationFfmpegExecutionGate and releases permit on success")
    void segmentEncodeExecutesThroughGateAndReleasesPermitOnSuccess() throws IOException {
        Path outputFile = Files.createTempFile("seg_test_gate_success_", ".mp3");
        FfmpegMp3SegmentAudioEncoderAdapter adapter = new FfmpegMp3SegmentAudioEncoderAdapter(
                "ffmpeg",
                "ffprobe",
                TIMEOUT,
                () -> outputFile,
                Files::deleteIfExists,
                (cmd, stream, to) -> {
                    Files.write(outputFile, ENCODED_MP3);
                    return new FfmpegMp3SegmentAudioEncoderAdapter.EncoderProcessResult(0, "");
                },
                (file, to) -> new FfmpegMp3SegmentAudioEncoderAdapter.SegmentProbeData("mp3", 48000, 1, 10),
                gate
        );

        assertThat(gate.getAvailablePermits()).isEqualTo(1);

        SegmentAudioEncodingResult result = adapter.encode(SegmentAudioEncodingRequest.of("audio/wav", SOURCE_WAV));
        assertThat(result).isNotNull();
        assertThat(gate.getAvailablePermits()).isEqualTo(1);

        Files.deleteIfExists(outputFile);
    }

    @Test
    @DisplayName("Permit is released when segment encode process fails")
    void permitIsReleasedWhenSegmentEncodeProcessFails() throws IOException {
        Path outputFile = Files.createTempFile("seg_test_gate_fail_", ".mp3");
        FfmpegMp3SegmentAudioEncoderAdapter adapter = new FfmpegMp3SegmentAudioEncoderAdapter(
                "ffmpeg",
                "ffprobe",
                TIMEOUT,
                () -> outputFile,
                Files::deleteIfExists,
                (cmd, stream, to) -> new FfmpegMp3SegmentAudioEncoderAdapter.EncoderProcessResult(1, "encoder crashed"),
                (file, to) -> new FfmpegMp3SegmentAudioEncoderAdapter.SegmentProbeData("mp3", 48000, 1, 10),
                gate
        );

        assertThat(gate.getAvailablePermits()).isEqualTo(1);

        assertThatThrownBy(() -> adapter.encode(SegmentAudioEncodingRequest.of("audio/wav", SOURCE_WAV)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FFmpeg segment audio encoding failed");

        assertThat(gate.getAvailablePermits()).isEqualTo(1);
    }

    @Test
    @DisplayName("Permit is released when segment probe fails")
    void permitIsReleasedWhenSegmentProbeFails() throws IOException {
        Path outputFile = Files.createTempFile("seg_test_gate_probe_fail_", ".mp3");
        FfmpegMp3SegmentAudioEncoderAdapter adapter = new FfmpegMp3SegmentAudioEncoderAdapter(
                "ffmpeg",
                "ffprobe",
                TIMEOUT,
                () -> outputFile,
                Files::deleteIfExists,
                (cmd, stream, to) -> {
                    Files.write(outputFile, ENCODED_MP3);
                    return new FfmpegMp3SegmentAudioEncoderAdapter.EncoderProcessResult(0, "");
                },
                (file, to) -> {
                    throw new IOException("probe parsing error");
                },
                gate
        );

        assertThat(gate.getAvailablePermits()).isEqualTo(1);

        assertThatThrownBy(() -> adapter.encode(SegmentAudioEncodingRequest.of("audio/wav", SOURCE_WAV)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to probe encoded segment audio");

        assertThat(gate.getAvailablePermits()).isEqualTo(1);
    }

    @Test
    @DisplayName("Two concurrent segment encode operations with capacity=1 never execute simultaneously")
    void concurrentSegmentEncodesAreSerializedByGate() throws Exception {
        AtomicInteger activeCalls = new AtomicInteger(0);
        AtomicInteger maxConcurrentCalls = new AtomicInteger(0);
        CountDownLatch firstCallEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstCall = new CountDownLatch(1);

        FfmpegMp3SegmentAudioEncoderAdapter adapter = new FfmpegMp3SegmentAudioEncoderAdapter(
                "ffmpeg",
                "ffprobe",
                TIMEOUT,
                () -> Files.createTempFile("seg_concurrent_", ".mp3"),
                Files::deleteIfExists,
                (cmd, stream, to) -> {
                    int current = activeCalls.incrementAndGet();
                    maxConcurrentCalls.accumulateAndGet(current, Math::max);
                    firstCallEntered.countDown();
                    try {
                        releaseFirstCall.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    Path out = Path.of(cmd.get(cmd.size() - 1));
                    Files.write(out, ENCODED_MP3);
                    activeCalls.decrementAndGet();
                    return new FfmpegMp3SegmentAudioEncoderAdapter.EncoderProcessResult(0, "");
                },
                (file, to) -> new FfmpegMp3SegmentAudioEncoderAdapter.SegmentProbeData("mp3", 48000, 1, 10),
                gate
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<SegmentAudioEncodingResult> f1 = executor.submit(() ->
                    adapter.encode(SegmentAudioEncodingRequest.of("audio/wav", SOURCE_WAV)));

            assertThat(firstCallEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(gate.getAvailablePermits()).isEqualTo(0);

            Future<SegmentAudioEncodingResult> f2 = executor.submit(() ->
                    adapter.encode(SegmentAudioEncodingRequest.of("audio/wav", SOURCE_WAV)));

            Thread.sleep(100);
            assertThat(gate.getQueueLength()).isEqualTo(1);
            assertThat(maxConcurrentCalls.get()).isEqualTo(1);

            releaseFirstCall.countDown();

            SegmentAudioEncodingResult res1 = f1.get(5, TimeUnit.SECONDS);
            SegmentAudioEncodingResult res2 = f2.get(5, TimeUnit.SECONDS);

            assertThat(res1).isNotNull();
            assertThat(res2).isNotNull();
            assertThat(maxConcurrentCalls.get()).isEqualTo(1);
            assertThat(gate.getAvailablePermits()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }
}
