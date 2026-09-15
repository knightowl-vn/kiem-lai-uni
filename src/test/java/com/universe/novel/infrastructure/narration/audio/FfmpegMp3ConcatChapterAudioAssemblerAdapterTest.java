package com.universe.novel.infrastructure.narration.audio;

import com.universe.novel.application.narration.ChapterAudioAssemblyCue;
import com.universe.novel.application.narration.ChapterAudioAssemblyRequest;
import com.universe.novel.application.narration.ChapterAudioAssemblyResult;
import com.universe.novel.application.narration.ChapterAudioSegmentBinarySource;
import com.universe.novel.application.narration.ChapterAudioSegmentSource;
import com.universe.novel.infrastructure.narration.audio.FfmpegMp3ConcatChapterAudioAssemblerAdapter.ChapterAudioProbeRunner;
import com.universe.novel.infrastructure.narration.audio.FfmpegMp3ConcatChapterAudioAssemblerAdapter.ChapterProbeData;
import com.universe.novel.infrastructure.narration.audio.FfmpegMp3ConcatChapterAudioAssemblerAdapter.ConcatProcessResult;
import com.universe.novel.infrastructure.narration.audio.FfmpegMp3ConcatChapterAudioAssemblerAdapter.ConcatProcessRunner;
import com.universe.novel.infrastructure.narration.audio.FfmpegMp3ConcatChapterAudioAssemblerAdapter.TempWorkspaceDeleter;
import com.universe.novel.infrastructure.narration.audio.FfmpegMp3ConcatChapterAudioAssemblerAdapter.TempWorkspaceFactory;
import com.universe.novel.infrastructure.narration.concurrency.NarrationFfmpegExecutionGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FFmpeg MP3 Concat Chapter Audio Assembler Adapter Tests")
class FfmpegMp3ConcatChapterAudioAssemblerAdapterTest {

    private static final UUID SEGMENT_1_ID = UUID.fromString("90000000-0000-0000-0000-000000000001");
    private static final UUID SEGMENT_2_ID = UUID.fromString("90000000-0000-0000-0000-000000000002");
    private static final UUID SEGMENT_3_ID = UUID.fromString("90000000-0000-0000-0000-000000000003");
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final byte[] DUMMY_OUTPUT_BYTES = new byte[]{(byte) 0xFF, (byte) 0xFB, 0x10, 0x20};

    private final List<Path> createdWorkspaces = new ArrayList<>();
    private final AtomicReference<Path> lastCreatedWorkspace = new AtomicReference<>();
    private NarrationFfmpegExecutionGate gate;

    @BeforeEach
    void setUp() {
        gate = new NarrationFfmpegExecutionGate(1);
    }

    @AfterEach
    void tearDown() throws IOException {
        for (Path workspace : createdWorkspaces) {
            FfmpegMp3ConcatChapterAudioAssemblerAdapter.deleteRecursively(workspace);
        }
    }

    @Test
    @DisplayName("1: request with 2+ canonical MP3 segments materializes intermediate segment files in exact order")
    void requestWithMultipleSegmentsMaterializesIntermediateFiles() {
        AtomicReference<byte[]> seg0BytesRef = new AtomicReference<>();
        AtomicReference<byte[]> seg1BytesRef = new AtomicReference<>();

        ConcatProcessRunner runner = (command, workingDir, timeout) -> {
            Path seg0 = workingDir.resolve("segment-000000.mp3");
            Path seg1 = workingDir.resolve("segment-000001.mp3");

            assertThat(Files.isRegularFile(seg0)).isTrue();
            assertThat(Files.isRegularFile(seg1)).isTrue();

            seg0BytesRef.set(Files.readAllBytes(seg0));
            seg1BytesRef.set(Files.readAllBytes(seg1));

            writeChapterOutput(workingDir);
            return new ConcatProcessResult(0, "");
        };

        ChapterAudioProbeRunner probeRunner = (audioFile, timeout) ->
                new ChapterProbeData("mp3", 48000, 1, new BigDecimal("0.072"));

        FfmpegMp3ConcatChapterAudioAssemblerAdapter adapter = createAdapter(runner, probeRunner);

        ChapterAudioSegmentSource source1 = canonicalSource(SEGMENT_1_ID, 0, 1152L, new byte[]{1, 2, 3});
        ChapterAudioSegmentSource source2 = canonicalSource(SEGMENT_2_ID, 1, 2304L, new byte[]{4, 5, 6, 7});

        ChapterAudioAssemblyResult result = adapter.assemble(new ChapterAudioAssemblyRequest(List.of(source1, source2)));

        assertThat(seg0BytesRef.get()).containsExactly((byte) 1, (byte) 2, (byte) 3);
        assertThat(seg1BytesRef.get()).containsExactly((byte) 4, (byte) 5, (byte) 6, (byte) 7);

        result.close();
    }

    @Test
    @DisplayName("2: concat manifest lists materialized segments with controlled relative filenames in order")
    void concatManifestListsMaterializedSegmentsInOrderWithRelativeFilenames() {
        AtomicReference<String> manifestContentRef = new AtomicReference<>();

        ConcatProcessRunner runner = (command, workingDir, timeout) -> {
            Path manifest = workingDir.resolve("concat.txt");
            assertThat(Files.isRegularFile(manifest)).isTrue();
            manifestContentRef.set(Files.readString(manifest, StandardCharsets.UTF_8));

            writeChapterOutput(workingDir);
            return new ConcatProcessResult(0, "");
        };

        ChapterAudioProbeRunner probeRunner = (audioFile, timeout) ->
                new ChapterProbeData("mp3", 48000, 1, new BigDecimal("0.072"));

        FfmpegMp3ConcatChapterAudioAssemblerAdapter adapter = createAdapter(runner, probeRunner);

        ChapterAudioSegmentSource source1 = canonicalSource(SEGMENT_1_ID, 0, 1152L, new byte[]{1, 2, 3});
        ChapterAudioSegmentSource source2 = canonicalSource(SEGMENT_2_ID, 1, 2304L, new byte[]{4, 5, 6, 7});

        ChapterAudioAssemblyResult result = adapter.assemble(new ChapterAudioAssemblyRequest(List.of(source1, source2)));

        assertThat(manifestContentRef.get()).isEqualTo("file 'segment-000000.mp3'\nfile 'segment-000001.mp3'\n");

        result.close();
    }

    @Test
    @DisplayName("3: command executes ffmpeg concat demuxer with stream copy flags")
    void commandContainsRequiredConcatDemuxerAndStreamCopyFlags() {
        AtomicReference<List<String>> executedCommandRef = new AtomicReference<>();

        ConcatProcessRunner runner = (command, workingDir, timeout) -> {
            executedCommandRef.set(command);
            writeChapterOutput(workingDir);
            return new ConcatProcessResult(0, "");
        };

        ChapterAudioProbeRunner probeRunner = (audioFile, timeout) ->
                new ChapterProbeData("mp3", 48000, 1, new BigDecimal("0.048"));

        FfmpegMp3ConcatChapterAudioAssemblerAdapter adapter = createAdapter(runner, probeRunner);

        ChapterAudioSegmentSource source1 = canonicalSource(SEGMENT_1_ID, 0, 1152L, new byte[]{1});
        ChapterAudioAssemblyResult result = adapter.assemble(new ChapterAudioAssemblyRequest(List.of(source1)));

        List<String> cmd = executedCommandRef.get();
        assertThat(cmd).containsSequence("-f", "concat", "-safe", "0", "-i", "concat.txt");
        assertThat(cmd).containsSequence("-c:a", "copy");
        assertThat(cmd).containsSequence("-vn");
        assertThat(cmd).containsSequence("-id3v2_version", "0");
        assertThat(cmd.get(cmd.size() - 1)).isEqualTo("chapter.mp3");

        result.close();
    }

    @Test
    @DisplayName("4: command does NOT invoke libmp3lame re-encoding")
    void commandDoesNotInvokeLibmp3lame() {
        AtomicReference<List<String>> executedCommandRef = new AtomicReference<>();

        ConcatProcessRunner runner = (command, workingDir, timeout) -> {
            executedCommandRef.set(command);
            writeChapterOutput(workingDir);
            return new ConcatProcessResult(0, "");
        };

        ChapterAudioProbeRunner probeRunner = (audioFile, timeout) ->
                new ChapterProbeData("mp3", 48000, 1, new BigDecimal("0.048"));

        FfmpegMp3ConcatChapterAudioAssemblerAdapter adapter = createAdapter(runner, probeRunner);

        ChapterAudioSegmentSource source1 = canonicalSource(SEGMENT_1_ID, 0, 1152L, new byte[]{1});
        ChapterAudioAssemblyResult result = adapter.assemble(new ChapterAudioAssemblyRequest(List.of(source1)));

        assertThat(executedCommandRef.get()).doesNotContain("libmp3lame");

        result.close();
    }

    @Test
    @DisplayName("5: assembly result resource reports audio/mpeg base MIME type")
    void assemblyResultResourceReportsAudioMpegBaseMimeType() {
        ConcatProcessRunner runner = (command, workingDir, timeout) -> {
            writeChapterOutput(workingDir, DUMMY_OUTPUT_BYTES);
            return new ConcatProcessResult(0, "");
        };

        ChapterAudioProbeRunner probeRunner = (audioFile, timeout) ->
                new ChapterProbeData("mp3", 48000, 1, new BigDecimal("0.048"));

        FfmpegMp3ConcatChapterAudioAssemblerAdapter adapter = createAdapter(runner, probeRunner);

        ChapterAudioSegmentSource source1 = canonicalSource(SEGMENT_1_ID, 0, 1152L, new byte[]{1});
        ChapterAudioAssemblyResult result = adapter.assemble(new ChapterAudioAssemblyRequest(List.of(source1)));

        assertThat(result.resource().mimeType()).isEqualTo("audio/mpeg");

        result.close();
    }

    @Test
    @DisplayName("6: assembly result resource openStream exposes chapter output binary data")
    void assemblyResultResourceOpenStreamExposesChapterOutputBinaryData() throws IOException {
        ConcatProcessRunner runner = (command, workingDir, timeout) -> {
            writeChapterOutput(workingDir, DUMMY_OUTPUT_BYTES);
            return new ConcatProcessResult(0, "");
        };

        ChapterAudioProbeRunner probeRunner = (audioFile, timeout) ->
                new ChapterProbeData("mp3", 48000, 1, new BigDecimal("0.048"));

        FfmpegMp3ConcatChapterAudioAssemblerAdapter adapter = createAdapter(runner, probeRunner);

        ChapterAudioSegmentSource source1 = canonicalSource(SEGMENT_1_ID, 0, 1152L, new byte[]{1});
        ChapterAudioAssemblyResult result = adapter.assemble(new ChapterAudioAssemblyRequest(List.of(source1)));

        assertThat(result.resource().sizeBytes()).isEqualTo(DUMMY_OUTPUT_BYTES.length);
        try (InputStream in = result.resource().openStream()) {
            assertThat(in.readAllBytes()).containsExactly(DUMMY_OUTPUT_BYTES);
        }

        result.close();
    }

    @Test
    @DisplayName("7: intermediate cue boundaries derive deterministically from contribution samples")
    void intermediateCueBoundariesDeriveDeterministicallyFromContributionSamples() {
        ConcatProcessRunner runner = (command, workingDir, timeout) -> {
            writeChapterOutput(workingDir);
            return new ConcatProcessResult(0, "");
        };

        // 3 segments: 1440 samples (30ms), 2400 samples (50ms), 1920 samples (40ms)
        ChapterAudioProbeRunner probeRunner = (audioFile, timeout) ->
                new ChapterProbeData("mp3", 48000, 1, new BigDecimal("0.125"));

        FfmpegMp3ConcatChapterAudioAssemblerAdapter adapter = createAdapter(runner, probeRunner);

        ChapterAudioSegmentSource s1 = canonicalSource(SEGMENT_1_ID, 0, 1440L, new byte[]{1});
        ChapterAudioSegmentSource s2 = canonicalSource(SEGMENT_2_ID, 1, 2400L, new byte[]{2});
        ChapterAudioSegmentSource s3 = canonicalSource(SEGMENT_3_ID, 2, 1920L, new byte[]{3});

        ChapterAudioAssemblyResult result = adapter.assemble(new ChapterAudioAssemblyRequest(List.of(s1, s2, s3)));

        // s1: start=0, end=30; s2: start=30, end=80; s3: start=80, end=125
        assertThat(result.cues().get(0).startMillis()).isEqualTo(0L);
        assertThat(result.cues().get(0).endMillis()).isEqualTo(30L);
        assertThat(result.cues().get(1).startMillis()).isEqualTo(30L);
        assertThat(result.cues().get(1).endMillis()).isEqualTo(80L);
        assertThat(result.cues().get(2).startMillis()).isEqualTo(80L);

        result.close();
    }

    @Test
    @DisplayName("8: final cue end derives from probed artifact duration")
    void finalCueEndDerivesFromProbedArtifactDuration() {
        ConcatProcessRunner runner = (command, workingDir, timeout) -> {
            writeChapterOutput(workingDir);
            return new ConcatProcessResult(0, "");
        };

        ChapterAudioProbeRunner probeRunner = (audioFile, timeout) ->
                new ChapterProbeData("mp3", 48000, 1, new BigDecimal("0.125"));

        FfmpegMp3ConcatChapterAudioAssemblerAdapter adapter = createAdapter(runner, probeRunner);

        ChapterAudioSegmentSource s1 = canonicalSource(SEGMENT_1_ID, 0, 1440L, new byte[]{1});
        ChapterAudioSegmentSource s2 = canonicalSource(SEGMENT_2_ID, 1, 2400L, new byte[]{2});
        ChapterAudioSegmentSource s3 = canonicalSource(SEGMENT_3_ID, 2, 1920L, new byte[]{3});

        ChapterAudioAssemblyResult result = adapter.assemble(new ChapterAudioAssemblyRequest(List.of(s1, s2, s3)));

        assertThat(result.durationMillis()).isEqualTo(125L);
        assertThat(result.cues().get(2).endMillis()).isEqualTo(125L);

        result.close();
    }

    @Test
    @DisplayName("9: one-segment chapter has start=0, end=probed duration")
    void oneSegmentChapterHasStartZeroAndEndProbedDuration() {
        ConcatProcessRunner runner = (command, workingDir, timeout) -> {
            writeChapterOutput(workingDir);
            return new ConcatProcessResult(0, "");
        };

        ChapterAudioProbeRunner probeRunner = (audioFile, timeout) ->
                new ChapterProbeData("mp3", 48000, 1, new BigDecimal("0.050"));

        FfmpegMp3ConcatChapterAudioAssemblerAdapter adapter = createAdapter(runner, probeRunner);

        ChapterAudioSegmentSource s1 = canonicalSource(SEGMENT_1_ID, 0, 1440L, new byte[]{1});
        ChapterAudioAssemblyResult result = adapter.assemble(new ChapterAudioAssemblyRequest(List.of(s1)));

        assertThat(result.durationMillis()).isEqualTo(50L);
        assertThat(result.cues()).containsExactly(
                new ChapterAudioAssemblyCue(0, SEGMENT_1_ID, 0, 0L, 50L)
        );

        result.close();
    }

    @Test
    @DisplayName("10: null timing rejected")
    void rejectsNullTiming() {
        FfmpegMp3ConcatChapterAudioAssemblerAdapter adapter = createDefaultAdapter();

        ChapterAudioSegmentSource nullTiming = new ChapterAudioSegmentSource(
                SEGMENT_1_ID, 0, "audio/mpeg", () -> new ByteArrayInputStream(new byte[]{1})
        );
        assertThatThrownBy(() -> adapter.assemble(new ChapterAudioAssemblyRequest(List.of(nullTiming))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must have encoded timing metadata");
    }

    @Test
    @DisplayName("11: non-48000 timing rejected")
    void rejectsNon48000Timing() {
        FfmpegMp3ConcatChapterAudioAssemblerAdapter adapter = createDefaultAdapter();

        ChapterAudioSegmentSource wrongRate = new ChapterAudioSegmentSource(
                SEGMENT_1_ID, 0, "audio/mpeg", () -> new ByteArrayInputStream(new byte[]{1}), 1152L, 44100
        );
        assertThatThrownBy(() -> adapter.assemble(new ChapterAudioAssemblyRequest(List.of(wrongRate))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("48000 Hz")
                .hasMessageContaining("44100");
    }

    @Test
    @DisplayName("12: contribution not divisible by 48 rejected")
    void rejectsContributionNotDivisibleBy48() {
        FfmpegMp3ConcatChapterAudioAssemblerAdapter adapter = createDefaultAdapter();

        ChapterAudioSegmentSource indivisible = new ChapterAudioSegmentSource(
                SEGMENT_1_ID, 0, "audio/mpeg", () -> new ByteArrayInputStream(new byte[]{1}), 1000L, 48000
        );
        assertThatThrownBy(() -> adapter.assemble(new ChapterAudioAssemblyRequest(List.of(indivisible))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact multiple of 48 samples/ms");
    }

    @Test
    @DisplayName("13: audio/wav rejected")
    void rejectsAudioWav() {
        FfmpegMp3ConcatChapterAudioAssemblerAdapter adapter = createDefaultAdapter();

        ChapterAudioSegmentSource wavSource = new ChapterAudioSegmentSource(
                SEGMENT_1_ID, 0, "audio/wav", () -> new ByteArrayInputStream(new byte[]{1}), 1152L, 48000
        );
        assertThatThrownBy(() -> adapter.assemble(new ChapterAudioAssemblyRequest(List.of(wavSource))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("audio/mpeg")
                .hasMessageContaining("audio/wav");
    }

    @Test
    @DisplayName("14: audio/mp3 alias rejected")
    void rejectsAudioMp3Alias() {
        FfmpegMp3ConcatChapterAudioAssemblerAdapter adapter = createDefaultAdapter();

        ChapterAudioSegmentSource aliasSource = new ChapterAudioSegmentSource(
                SEGMENT_1_ID, 0, "audio/mp3", () -> new ByteArrayInputStream(new byte[]{1}), 1152L, 48000
        );
        assertThatThrownBy(() -> adapter.assemble(new ChapterAudioAssemblyRequest(List.of(aliasSource))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("audio/mpeg")
                .hasMessageContaining("audio/mp3");
    }

    @Test
    @DisplayName("15: empty or null source stream rejected")
    void rejectsEmptyOrNullSourceStream() {
        FfmpegMp3ConcatChapterAudioAssemblerAdapter adapter = createDefaultAdapter();

        ChapterAudioSegmentSource nullStreamSource = new ChapterAudioSegmentSource(
                SEGMENT_1_ID, 0, "audio/mpeg", () -> null, 1152L, 48000
        );
        assertThatThrownBy(() -> adapter.assemble(new ChapterAudioAssemblyRequest(List.of(nullStreamSource))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("returned null stream");

        ChapterAudioSegmentSource emptyStreamSource = new ChapterAudioSegmentSource(
                SEGMENT_1_ID, 0, "audio/mpeg", () -> new ByteArrayInputStream(new byte[0]), 1152L, 48000
        );
        assertThatThrownBy(() -> adapter.assemble(new ChapterAudioAssemblyRequest(List.of(emptyStreamSource))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty file");
    }

    @Test
    @DisplayName("16: FFmpeg non-zero exit cleans workspace")
    void ffmpegNonZeroExitCleansWorkspace() {
        ConcatProcessRunner runner = (command, workingDir, timeout) ->
                new ConcatProcessResult(1, "FFmpeg concat error: corrupt input");

        ChapterAudioProbeRunner probeRunner = (audioFile, timeout) ->
                new ChapterProbeData("mp3", 48000, 1, new BigDecimal("0.048"));

        FfmpegMp3ConcatChapterAudioAssemblerAdapter adapter = createAdapter(runner, probeRunner);
        ChapterAudioSegmentSource s1 = canonicalSource(SEGMENT_1_ID, 0, 1152L, new byte[]{1});

        assertThatThrownBy(() -> adapter.assemble(new ChapterAudioAssemblyRequest(List.of(s1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exit code 1")
                .hasMessageContaining("corrupt input");

        assertThat(Files.exists(lastCreatedWorkspace.get())).isFalse();
    }

    @Test
    @DisplayName("17: FFmpeg timeout cleans workspace")
    void ffmpegTimeoutCleansWorkspace() {
        ConcatProcessRunner runner = (command, workingDir, timeout) -> {
            throw new TimeoutException("Process timeout");
        };

        ChapterAudioProbeRunner probeRunner = (audioFile, timeout) ->
                new ChapterProbeData("mp3", 48000, 1, new BigDecimal("0.048"));

        FfmpegMp3ConcatChapterAudioAssemblerAdapter adapter = createAdapter(runner, probeRunner);
        ChapterAudioSegmentSource s1 = canonicalSource(SEGMENT_1_ID, 0, 1152L, new byte[]{1});

        assertThatThrownBy(() -> adapter.assemble(new ChapterAudioAssemblyRequest(List.of(s1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("timed out");

        assertThat(Files.exists(lastCreatedWorkspace.get())).isFalse();
    }

    @Test
    @DisplayName("18: interruption restores interrupt status and cleans workspace")
    void interruptionRestoresInterruptStatusAndCleansWorkspace() {
        ConcatProcessRunner runner = (command, workingDir, timeout) -> {
            throw new InterruptedException("Thread interrupted");
        };

        ChapterAudioProbeRunner probeRunner = (audioFile, timeout) ->
                new ChapterProbeData("mp3", 48000, 1, new BigDecimal("0.048"));

        FfmpegMp3ConcatChapterAudioAssemblerAdapter adapter = createAdapter(runner, probeRunner);
        ChapterAudioSegmentSource s1 = canonicalSource(SEGMENT_1_ID, 0, 1152L, new byte[]{1});

        try {
            assertThatThrownBy(() -> adapter.assemble(new ChapterAudioAssemblyRequest(List.of(s1))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(Files.exists(lastCreatedWorkspace.get())).isFalse();
        } finally {
            Thread.interrupted(); // clear status
        }
    }

    @Test
    @DisplayName("19: malformed/invalid FFprobe data rejected")
    void malformedOrInvalidFfprobeDataRejected() {
        ConcatProcessRunner runner = (command, workingDir, timeout) -> {
            writeChapterOutput(workingDir);
            return new ConcatProcessResult(0, "");
        };

        ChapterAudioProbeRunner probeRunner = (audioFile, timeout) -> {
            throw new IOException("Malformed JSON output from ffprobe");
        };

        FfmpegMp3ConcatChapterAudioAssemblerAdapter adapter = createAdapter(runner, probeRunner);
        ChapterAudioSegmentSource s1 = canonicalSource(SEGMENT_1_ID, 0, 1152L, new byte[]{1});

        assertThatThrownBy(() -> adapter.assemble(new ChapterAudioAssemblyRequest(List.of(s1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to probe concatenated chapter audio");

        assertThat(Files.exists(lastCreatedWorkspace.get())).isFalse();
    }

    @Test
    @DisplayName("20: wrong codec/rate/channels rejected")
    void wrongCodecRateOrChannelsRejected() {
        ConcatProcessRunner runner = (command, workingDir, timeout) -> {
            writeChapterOutput(workingDir);
            return new ConcatProcessResult(0, "");
        };

        // Wrong codec
        ChapterAudioProbeRunner probeAac = (audioFile, timeout) ->
                new ChapterProbeData("aac", 48000, 1, new BigDecimal("0.048"));
        FfmpegMp3ConcatChapterAudioAssemblerAdapter adapterAac = createAdapter(runner, probeAac);
        ChapterAudioSegmentSource s1 = canonicalSource(SEGMENT_1_ID, 0, 1152L, new byte[]{1});

        assertThatThrownBy(() -> adapterAac.assemble(new ChapterAudioAssemblyRequest(List.of(s1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected mp3");

        // Wrong sample rate
        ChapterAudioProbeRunner probe44100 = (audioFile, timeout) ->
                new ChapterProbeData("mp3", 44100, 1, new BigDecimal("0.048"));
        FfmpegMp3ConcatChapterAudioAssemblerAdapter adapter44100 = createAdapter(runner, probe44100);

        assertThatThrownBy(() -> adapter44100.assemble(new ChapterAudioAssemblyRequest(List.of(s1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("48000 Hz");

        // Wrong channels
        ChapterAudioProbeRunner probeStereo = (audioFile, timeout) ->
                new ChapterProbeData("mp3", 48000, 2, new BigDecimal("0.048"));
        FfmpegMp3ConcatChapterAudioAssemblerAdapter adapterStereo = createAdapter(runner, probeStereo);

        assertThatThrownBy(() -> adapterStereo.assemble(new ChapterAudioAssemblyRequest(List.of(s1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected 1");
    }

    @Test
    @DisplayName("21: final duration <= last cue start rejected")
    void finalDurationLessThanOrEqualToLastCueStartRejected() {
        ConcatProcessRunner runner = (command, workingDir, timeout) -> {
            writeChapterOutput(workingDir);
            return new ConcatProcessResult(0, "");
        };

        // 2 segments: segment 1 starts at 30ms. Probed duration is only 25ms!
        ChapterAudioProbeRunner probeShort = (audioFile, timeout) ->
                new ChapterProbeData("mp3", 48000, 1, new BigDecimal("0.025"));

        FfmpegMp3ConcatChapterAudioAssemblerAdapter adapter = createAdapter(runner, probeShort);

        ChapterAudioSegmentSource s1 = canonicalSource(SEGMENT_1_ID, 0, 1440L, new byte[]{1}); // 30ms
        ChapterAudioSegmentSource s2 = canonicalSource(SEGMENT_2_ID, 1, 1440L, new byte[]{2}); // starts at 30ms

        assertThatThrownBy(() -> adapter.assemble(new ChapterAudioAssemblyRequest(List.of(s1, s2))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be greater than last cue start");

        assertThat(Files.exists(lastCreatedWorkspace.get())).isFalse();
    }

    @Test
    @DisplayName("22: result close removes retained output and workspace")
    void resultCloseRemovesRetainedOutputAndWorkspace() {
        ConcatProcessRunner runner = (command, workingDir, timeout) -> {
            writeChapterOutput(workingDir);
            return new ConcatProcessResult(0, "");
        };

        ChapterAudioProbeRunner probeRunner = (audioFile, timeout) ->
                new ChapterProbeData("mp3", 48000, 1, new BigDecimal("0.048"));

        FfmpegMp3ConcatChapterAudioAssemblerAdapter adapter = createAdapter(runner, probeRunner);
        ChapterAudioSegmentSource s1 = canonicalSource(SEGMENT_1_ID, 0, 1152L, new byte[]{1});

        ChapterAudioAssemblyResult result = adapter.assemble(new ChapterAudioAssemblyRequest(List.of(s1)));
        Path workspace = lastCreatedWorkspace.get();
        assertThat(Files.exists(workspace)).isTrue();
        assertThat(Files.exists(workspace.resolve("chapter.mp3"))).isTrue();

        result.close();

        assertThat(Files.exists(workspace)).isFalse();
    }

    @Test
    @DisplayName("23: openStream can be opened as a fresh readable stream multiple times before close")
    void openStreamCanBeOpenedAsFreshReadableStream() throws IOException {
        ConcatProcessRunner runner = (command, workingDir, timeout) -> {
            writeChapterOutput(workingDir, new byte[]{10, 20, 30});
            return new ConcatProcessResult(0, "");
        };

        ChapterAudioProbeRunner probeRunner = (audioFile, timeout) ->
                new ChapterProbeData("mp3", 48000, 1, new BigDecimal("0.048"));

        FfmpegMp3ConcatChapterAudioAssemblerAdapter adapter = createAdapter(runner, probeRunner);
        ChapterAudioSegmentSource s1 = canonicalSource(SEGMENT_1_ID, 0, 1152L, new byte[]{1});

        ChapterAudioAssemblyResult result = adapter.assemble(new ChapterAudioAssemblyRequest(List.of(s1)));
        Path workspace = lastCreatedWorkspace.get();

        try (InputStream stream1 = result.resource().openStream();
             InputStream stream2 = result.resource().openStream()) {
            assertThat(stream1.readAllBytes()).containsExactly((byte) 10, (byte) 20, (byte) 30);
            assertThat(stream2.readAllBytes()).containsExactly((byte) 10, (byte) 20, (byte) 30);
        }

        result.close();
        assertThat(Files.exists(workspace)).isFalse();
    }

    @Test
    @DisplayName("24: cleanup failure is suppressed beneath primary processing failure")
    void cleanupFailureIsSuppressedBeneathPrimaryProcessingFailure() {
        ConcatProcessRunner runner = (command, workingDir, timeout) ->
                new ConcatProcessResult(1, "Encoding failure");

        ChapterAudioProbeRunner probeRunner = (audioFile, timeout) ->
                new ChapterProbeData("mp3", 48000, 1, new BigDecimal("0.048"));

        IOException cleanupException = new IOException("Disk error during workspace deletion");
        TempWorkspaceDeleter failingDeleter = dir -> {
            throw cleanupException;
        };

        FfmpegMp3ConcatChapterAudioAssemblerAdapter adapter = new FfmpegMp3ConcatChapterAudioAssemblerAdapter(
                "ffmpeg",
                "ffprobe",
                DEFAULT_TIMEOUT,
                () -> {
                    Path dir = Files.createTempDirectory("test_concat_workspace_");
                    createdWorkspaces.add(dir);
                    lastCreatedWorkspace.set(dir);
                    return dir;
                },
                failingDeleter,
                runner,
                probeRunner,
                gate
        );

        ChapterAudioSegmentSource s1 = canonicalSource(SEGMENT_1_ID, 0, 1152L, new byte[]{1});

        assertThatThrownBy(() -> adapter.assemble(new ChapterAudioAssemblyRequest(List.of(s1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Encoding failure")
                .hasSuppressedException(cleanupException);
    }

    private FfmpegMp3ConcatChapterAudioAssemblerAdapter createAdapter(
            ConcatProcessRunner runner,
            ChapterAudioProbeRunner probeRunner
    ) {
        return new FfmpegMp3ConcatChapterAudioAssemblerAdapter(
                "ffmpeg",
                "ffprobe",
                DEFAULT_TIMEOUT,
                () -> {
                    Path dir = Files.createTempDirectory("test_concat_workspace_");
                    createdWorkspaces.add(dir);
                    lastCreatedWorkspace.set(dir);
                    return dir;
                },
                FfmpegMp3ConcatChapterAudioAssemblerAdapter::deleteRecursively,
                runner,
                probeRunner,
                gate
        );
    }

    private FfmpegMp3ConcatChapterAudioAssemblerAdapter createDefaultAdapter() {
        return createAdapter(
                (cmd, dir, to) -> {
                    writeChapterOutput(dir);
                    return new ConcatProcessResult(0, "");
                },
                (audioFile, timeout) ->
                        new ChapterProbeData("mp3", 48000, 1, new BigDecimal("0.024"))
        );
    }

    @Test
    @DisplayName("Chapter concat and probe executes through NarrationFfmpegExecutionGate and releases permit on success")
    void chapterConcatExecutesThroughGateAndReleasesPermitOnSuccess() {
        assertThat(gate.getAvailablePermits()).isEqualTo(1);

        FfmpegMp3ConcatChapterAudioAssemblerAdapter adapter = createDefaultAdapter();
        ChapterAudioSegmentSource s1 = canonicalSource(SEGMENT_1_ID, 0, 1152L, new byte[]{1, 2, 3});

        ChapterAudioAssemblyResult result = adapter.assemble(new ChapterAudioAssemblyRequest(List.of(s1)));
        assertThat(result).isNotNull();
        assertThat(gate.getAvailablePermits()).isEqualTo(1);
    }

    @Test
    @DisplayName("Permit is released when concat process fails with exit code != 0")
    void permitIsReleasedWhenConcatProcessFails() {
        ConcatProcessRunner failingRunner = (cmd, dir, to) -> new ConcatProcessResult(1, "concat failed");
        ChapterAudioProbeRunner dummyProbeRunner = (file, to) -> new ChapterProbeData("mp3", 48000, 1, new BigDecimal("0.024"));

        FfmpegMp3ConcatChapterAudioAssemblerAdapter adapter = createAdapter(failingRunner, dummyProbeRunner);
        ChapterAudioSegmentSource s1 = canonicalSource(SEGMENT_1_ID, 0, 1152L, new byte[]{1});

        assertThat(gate.getAvailablePermits()).isEqualTo(1);

        assertThatThrownBy(() -> adapter.assemble(new ChapterAudioAssemblyRequest(List.of(s1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FFmpeg chapter audio concatenation failed");

        assertThat(gate.getAvailablePermits()).isEqualTo(1);
    }

    @Test
    @DisplayName("Permit is released when chapter probe fails with exception")
    void permitIsReleasedWhenChapterProbeFails() {
        ConcatProcessRunner runner = (cmd, dir, to) -> {
            writeChapterOutput(dir);
            return new ConcatProcessResult(0, "");
        };
        ChapterAudioProbeRunner failingProbeRunner = (file, to) -> {
            throw new IOException("probe io error");
        };

        FfmpegMp3ConcatChapterAudioAssemblerAdapter adapter = createAdapter(runner, failingProbeRunner);
        ChapterAudioSegmentSource s1 = canonicalSource(SEGMENT_1_ID, 0, 1152L, new byte[]{1});

        assertThat(gate.getAvailablePermits()).isEqualTo(1);

        assertThatThrownBy(() -> adapter.assemble(new ChapterAudioAssemblyRequest(List.of(s1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to probe concatenated chapter audio with FFprobe");

        assertThat(gate.getAvailablePermits()).isEqualTo(1);
    }

    private static ChapterAudioSegmentSource canonicalSource(
            UUID segmentId,
            int segmentIndex,
            Long contributionSamples,
            byte[] bytes
    ) {
        return new ChapterAudioSegmentSource(
                segmentId,
                segmentIndex,
                "audio/mpeg",
                () -> new ByteArrayInputStream(bytes),
                contributionSamples,
                48000
        );
    }

    private static void writeChapterOutput(Path workingDir) throws IOException {
        writeChapterOutput(workingDir, DUMMY_OUTPUT_BYTES);
    }

    private static void writeChapterOutput(Path workingDir, byte[] bytes) throws IOException {
        Files.write(workingDir.resolve("chapter.mp3"), bytes);
    }
}
