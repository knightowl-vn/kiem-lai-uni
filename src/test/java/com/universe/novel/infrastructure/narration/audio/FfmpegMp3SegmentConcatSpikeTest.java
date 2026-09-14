package com.universe.novel.infrastructure.narration.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.universe.novel.application.narration.ChapterAudioAssemblyCue;
import com.universe.novel.application.narration.ChapterAudioAssemblyRequest;
import com.universe.novel.application.narration.ChapterAudioAssemblyResult;
import com.universe.novel.application.narration.ChapterAudioEncodingRequest;
import com.universe.novel.application.narration.ChapterAudioEncodingResult;
import com.universe.novel.application.narration.ChapterAudioSegmentSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NR-AUDIO-FMT-01B3: Variable-Length MP3 Boundary Accounting Spike.
 * Tests stream-copy concatenation (-c:a copy) across 5 variable-length segments
 * with different sample counts modulo 1152 to measure exact decoded content boundaries,
 * join padding variability, and candidate cue prediction models.
 */
@DisplayName("NR-AUDIO-FMT-01B3: Variable-Length MP3 Boundary Accounting Spike")
class FfmpegMp3SegmentConcatSpikeTest {

    private static final int SAMPLE_RATE = 48_000;
    private static final int CHANNELS = 1;
    private static final int MP3_FRAME_SAMPLES = 1152;
    private static final double MP3_FRAME_DURATION_MS = 24.0; // 1152 / 48000 * 1000
    private static final double KNOWN_LEADING_SILENCE_MS = 100.0;
    private static final double KNOWN_TRAILING_SILENCE_MS = 150.0;
    private static final double KNOWN_NOMINAL_SILENCE_GAP_MS = KNOWN_LEADING_SILENCE_MS + KNOWN_TRAILING_SILENCE_MS; // 250.0 ms

    private static final double[] CANDIDATE_FREQS = {440.0, 880.0, 660.0, 520.0, 330.0};
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static boolean ffmpegAvailable = false;
    private static boolean ffprobeAvailable = false;

    @BeforeAll
    static void checkTools() {
        ffmpegAvailable = checkCommand("ffmpeg", "-version");
        ffprobeAvailable = checkCommand("ffprobe", "-version");
    }

    private static boolean checkCommand(String... cmd) {
        try {
            ProcessResult result = runProcess(Duration.ofSeconds(5), cmd);
            return result.exitCode() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    record SegmentFixture(
            UUID id,
            int index,
            double toneFrequency,
            int leadingSilenceFrames,
            int speechFrames,
            int trailingSilenceFrames,
            long totalFrames,
            double totalDurationMs,
            long remainderModulo1152
    ) {}

    record StandaloneSegmentResult(
            SegmentFixture fixture,
            Path wavPath,
            Path mp3Path,
            int packetCount,
            double packetDurationMs,
            double probedDurationSeconds,
            AudioAnalysisResult decodedAnalysis
    ) {}

    record ContentBoundary(
            int index,
            double speechStartMs,
            double speechEndMs,
            double speechDurationMs,
            double contentStartMs,
            double contentEndMs,
            double contentDurationMs
    ) {}

    @Test
    @DisplayName("Spike: Variable-Length MP3 Boundary Accounting (5 segments, distinct mod 1152)")
    void runVariableLengthConcatSpike(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(ffmpegAvailable, "ffmpeg executable must be available in PATH");
        Assumptions.assumeTrue(ffprobeAvailable, "ffprobe executable must be available in PATH");

        // ---------------------------------------------------------------------
        // 1. Define 5 Deterministic Segment Fixtures with Distinct Modulo 1152
        // ---------------------------------------------------------------------
        // Seg 1: 100ms silence (4800) + 800ms speech (38400) + 150ms silence (7200) = 50,400 frames (1050 ms), mod 1152 = 864
        // Seg 2: 100ms silence (4800) + 1500ms speech (72000) + 150ms silence (7200) = 84,000 frames (1750 ms), mod 1152 = 1056
        // Seg 3: 100ms silence (4800) + 500ms speech (24000) + 150ms silence (7200) = 36,000 frames (750 ms) (INTERNAL), mod 1152 = 288
        // Seg 4: 100ms silence (4800) + 950ms speech (45600) + 150ms silence (7200) = 57,600 frames (1200 ms), mod 1152 = 0
        // Seg 5: 100ms silence (4800) + 625ms speech (30000) + 150ms silence (7200) = 42,000 frames (875 ms), mod 1152 = 528
        List<SegmentFixture> fixtures = List.of(
                new SegmentFixture(UUID.fromString("00000000-0000-0000-0000-000000000001"), 0, 440.0, 4800, 38400, 7200, 50400L, 1050.0, 50400L % MP3_FRAME_SAMPLES),
                new SegmentFixture(UUID.fromString("00000000-0000-0000-0000-000000000002"), 1, 880.0, 4800, 72000, 7200, 84000L, 1750.0, 84000L % MP3_FRAME_SAMPLES),
                new SegmentFixture(UUID.fromString("00000000-0000-0000-0000-000000000003"), 2, 660.0, 4800, 24000, 7200, 36000L, 750.0, 36000L % MP3_FRAME_SAMPLES),
                new SegmentFixture(UUID.fromString("00000000-0000-0000-0000-000000000004"), 3, 520.0, 4800, 45600, 7200, 57600L, 1200.0, 57600L % MP3_FRAME_SAMPLES),
                new SegmentFixture(UUID.fromString("00000000-0000-0000-0000-000000000005"), 4, 330.0, 4800, 30000, 7200, 42000L, 875.0, 42000L % MP3_FRAME_SAMPLES)
        );

        long expectedTotalPcmFrames = fixtures.stream().mapToLong(SegmentFixture::totalFrames).sum(); // 270,000 frames
        double expectedTotalPcmDurationMs = fixtures.stream().mapToDouble(SegmentFixture::totalDurationMs).sum(); // 5625.0 ms

        // Verify distinct remainders modulo 1152
        Set<Long> remainders = new LinkedHashSet<>();
        for (SegmentFixture f : fixtures) {
            remainders.add(f.remainderModulo1152());
        }
        assertThat(remainders).hasSize(5);

        // Generate WAV files
        List<byte[]> wavBytes = new ArrayList<>();
        List<Path> wavPaths = new ArrayList<>();
        for (int i = 0; i < fixtures.size(); i++) {
            SegmentFixture f = fixtures.get(i);
            byte[] bytes = createSegmentWav(f.leadingSilenceFrames(), f.speechFrames(), f.trailingSilenceFrames(), f.toneFrequency());
            wavBytes.add(bytes);
            Path p = tempDir.resolve("seg" + (i + 1) + ".wav");
            Files.write(p, bytes);
            wavPaths.add(p);
        }

        // ---------------------------------------------------------------------
        // 2. BASELINE PIPELINE (PCM WAV assemble -> single libmp3lame encode)
        // ---------------------------------------------------------------------
        PcmWavChapterAudioAssemblerAdapter assembler = new PcmWavChapterAudioAssemblerAdapter();
        FfmpegMp3ChapterAudioEncoderAdapter encoder = new FfmpegMp3ChapterAudioEncoderAdapter("ffmpeg", Duration.ofSeconds(60));

        List<ChapterAudioSegmentSource> segmentSources = new ArrayList<>();
        for (int i = 0; i < fixtures.size(); i++) {
            final byte[] b = wavBytes.get(i);
            segmentSources.add(new ChapterAudioSegmentSource(fixtures.get(i).id(), i, "audio/wav", () -> new ByteArrayInputStream(b)));
        }

        Path baselineMp3Path = tempDir.resolve("baseline_chapter.mp3");
        List<ChapterAudioAssemblyCue> baselineCues;
        long baselineAssemblyDurationMs;

        try (ChapterAudioAssemblyResult assemblyResult = assembler.assemble(new ChapterAudioAssemblyRequest(segmentSources));
             ChapterAudioEncodingResult encodingResult = encoder.encode(new ChapterAudioEncodingRequest(assemblyResult.resource()))) {

            baselineCues = assemblyResult.cues();
            baselineAssemblyDurationMs = assemblyResult.durationMillis();

            try (InputStream encodedStream = encodingResult.openStream()) {
                Files.copy(encodedStream, baselineMp3Path);
            }
        }

        ProbeResult baselineProbe = probe(baselineMp3Path);
        int baselinePackets = countPackets(baselineMp3Path);

        Path baselineDecodedPcm = tempDir.resolve("baseline_decoded.raw");
        decodeToRawPcm(baselineMp3Path, baselineDecodedPcm);
        AudioAnalysisResult baselineAnalysis = analyzePcm(baselineDecodedPcm);

        // Derive baseline content boundaries
        List<ContentBoundary> baselineBoundaries = deriveContentBoundaries(baselineAnalysis);

        // ---------------------------------------------------------------------
        // 3. INDEPENDENT MP3 SEGMENT ENCODE & STANDALONE METRICS
        // ---------------------------------------------------------------------
        List<StandaloneSegmentResult> standaloneResults = new ArrayList<>();
        List<Path> candMp3Paths = new ArrayList<>();

        for (int i = 0; i < fixtures.size(); i++) {
            SegmentFixture f = fixtures.get(i);
            Path mp3Path = tempDir.resolve("seg" + (i + 1) + ".mp3");
            encodeSegmentCanonical(wavPaths.get(i), mp3Path);
            candMp3Paths.add(mp3Path);

            int packets = countPackets(mp3Path);
            double packetDurationMs = packets * MP3_FRAME_DURATION_MS;
            ProbeResult probeRes = probe(mp3Path);

            Path standaloneDecodedPcm = tempDir.resolve("seg" + (i + 1) + "_standalone_decoded.raw");
            decodeToRawPcm(mp3Path, standaloneDecodedPcm);
            AudioAnalysisResult standaloneAnalysis = analyzePcm(standaloneDecodedPcm);

            standaloneResults.add(new StandaloneSegmentResult(
                    f, wavPaths.get(i), mp3Path, packets, packetDurationMs, probeRes.durationSeconds(), standaloneAnalysis
            ));
        }

        // ---------------------------------------------------------------------
        // 4. CANDIDATE PIPELINE (Concat Demuxer with -c:a copy)
        // ---------------------------------------------------------------------
        Path concatManifest = tempDir.resolve("concat_manifest.txt");
        writeConcatManifest(concatManifest, candMp3Paths);

        Path candChapterMp3Path = tempDir.resolve("candidate_chapter.mp3");
        concatStreams(concatManifest, candChapterMp3Path);

        ProbeResult candProbe = probe(candChapterMp3Path);
        int candChapterPackets = countPackets(candChapterMp3Path);
        ProcessResult candDecodeVerify = verifyDecode(candChapterMp3Path);

        Path candDecodedPcm = tempDir.resolve("candidate_decoded.raw");
        decodeToRawPcm(candChapterMp3Path, candDecodedPcm);
        AudioAnalysisResult candAnalysis = analyzePcm(candDecodedPcm);

        List<ContentBoundary> candBoundaries = deriveContentBoundaries(candAnalysis);

        // ---------------------------------------------------------------------
        // 5. MEASURE JOIN PADDING ACROSS ALL INTERNAL JOINS
        // ---------------------------------------------------------------------
        // joinPadding = nextSegment.contentStart - previousSegment.contentEnd
        List<Double> joinPaddings = new ArrayList<>();
        for (int j = 0; j < candBoundaries.size() - 1; j++) {
            double padding = candBoundaries.get(j + 1).contentStartMs() - candBoundaries.get(j).contentEndMs();
            joinPaddings.add(padding);
        }

        // Distinct measured join paddings
        Set<Double> distinctPaddings = new LinkedHashSet<>();
        for (Double p : joinPaddings) {
            distinctPaddings.add(Math.round(p * 100.0) / 100.0);
        }

        // ---------------------------------------------------------------------
        // 6. TIMING MODELS & PREDICTION ERROR EVALUATION
        // ---------------------------------------------------------------------
        // Evaluate: Can chapter cue boundaries be predicted without decoding the chapter?
        // Strategy A: Cumulative Packet Duration of preceding segments
        //   predictedStart[i] = sum_{k=0}^{i-1} (packetCount[k] * 24.0 ms)
        // Strategy B: Cumulative Source PCM Duration
        //   predictedStart[i] = sum_{k=0}^{i-1} sourceDuration[k]
        // Strategy C: Cumulative Standalone Decoded Duration
        //   predictedStart[i] = sum_{k=0}^{i-1} standaloneDecodedDuration[k]

        List<Double> packetPredictedStarts = new ArrayList<>();
        List<Double> sourcePredictedStarts = new ArrayList<>();
        List<Double> standalonePredictedStarts = new ArrayList<>();

        double cumPacketMs = 0.0;
        double cumSourceMs = 0.0;
        double cumStandaloneMs = 0.0;

        for (int i = 0; i < fixtures.size(); i++) {
            packetPredictedStarts.add(cumPacketMs);
            sourcePredictedStarts.add(cumSourceMs);
            standalonePredictedStarts.add(cumStandaloneMs);

            cumPacketMs += standaloneResults.get(i).packetDurationMs();
            cumSourceMs += fixtures.get(i).totalDurationMs();
            cumStandaloneMs += standaloneResults.get(i).decodedAnalysis().totalDurationMs();
        }

        List<Double> packetErrors = new ArrayList<>();
        List<Double> sourceErrors = new ArrayList<>();
        List<Double> standaloneErrors = new ArrayList<>();

        double maxPacketAbsError = 0.0;
        double maxSourceAbsError = 0.0;
        double maxStandaloneAbsError = 0.0;

        for (int i = 0; i < fixtures.size(); i++) {
            double actualStart = candBoundaries.get(i).contentStartMs();

            double pErr = packetPredictedStarts.get(i) - actualStart;
            double sErr = sourcePredictedStarts.get(i) - actualStart;
            double saErr = standalonePredictedStarts.get(i) - actualStart;

            packetErrors.add(pErr);
            sourceErrors.add(sErr);
            standaloneErrors.add(saErr);

            maxPacketAbsError = Math.max(maxPacketAbsError, Math.abs(pErr));
            maxSourceAbsError = Math.max(maxSourceAbsError, Math.abs(sErr));
            maxStandaloneAbsError = Math.max(maxStandaloneAbsError, Math.abs(saErr));
        }

        // ---------------------------------------------------------------------
        // 7. PRINT COMPREHENSIVE SPIKE REPORT
        // ---------------------------------------------------------------------
        System.out.println("================================================================================");
        System.out.println("NR-AUDIO-FMT-01B3: VARIABLE-LENGTH MP3 BOUNDARY ACCOUNTING SPIKE REPORT");
        System.out.println("================================================================================");

        System.out.println("1. Fixture Specifications (5 Variable-Length Segments):");
        System.out.println("+-----+-----------+-----------+------------+------------+---------------+");
        System.out.println("| Seg | Frequency | Frames    | DurationMs | Modulo1152 | Speech Len Ms |");
        System.out.println("+-----+-----------+-----------+------------+------------+---------------+");
        for (SegmentFixture f : fixtures) {
            System.out.printf(Locale.ROOT, "| %-3d | %7.1fHz | %9d | %10.2f | %10d | %13.2f |%n",
                    f.index() + 1, f.toneFrequency(), f.totalFrames(), f.totalDurationMs(), f.remainderModulo1152(), (f.speechFrames() * 1000.0) / SAMPLE_RATE);
        }
        System.out.println("+-----+-----------+-----------+------------+------------+---------------+");
        System.out.printf(Locale.ROOT, "Total Fixture PCM: %d frames (%.2f ms)%n%n", expectedTotalPcmFrames, expectedTotalPcmDurationMs);

        System.out.println("2. Standalone MP3 Segment Measurements (Per-Segment Encoded Contribution):");
        System.out.println("+-----+---------+------------+------------+----------------+---------------+");
        System.out.println("| Seg | Packets | PacketDur  | ProbedSec  | StandaloneDec  | Dec vs Source |");
        System.out.println("+-----+---------+------------+------------+----------------+---------------+");
        for (StandaloneSegmentResult r : standaloneResults) {
            System.out.printf(Locale.ROOT, "| %-3d | %7d | %8.2fms | %8.6fs | %12.2fms | %+11.2fms |%n",
                    r.fixture().index() + 1, r.packetCount(), r.packetDurationMs(), r.probedDurationSeconds(),
                    r.decodedAnalysis().totalDurationMs(), r.decodedAnalysis().totalDurationMs() - r.fixture().totalDurationMs());
        }
        System.out.println("+-----+---------+------------+------------+----------------+---------------+");
        System.out.printf(Locale.ROOT, "Sum of Segment Packets: %d packets (%.2f ms)%n%n",
                standaloneResults.stream().mapToInt(StandaloneSegmentResult::packetCount).sum(),
                standaloneResults.stream().mapToDouble(StandaloneSegmentResult::packetDurationMs).sum());

        System.out.println("3. Baseline Assembly vs Candidate Concat High-Level Decoded Results:");
        System.out.printf(Locale.ROOT, "  Baseline Decoded Frames: %d (delta vs source: %+d frames, %+.2f ms)%n",
                baselineAnalysis.totalSamples(), baselineAnalysis.totalSamples() - expectedTotalPcmFrames,
                baselineAnalysis.totalDurationMs() - expectedTotalPcmDurationMs);
        System.out.printf(Locale.ROOT, "  Baseline Total Packets: %d (%d * 1152 = %d samples)%n",
                baselinePackets, baselinePackets, baselinePackets * MP3_FRAME_SAMPLES);
        System.out.printf(Locale.ROOT, "  Candidate Decoded Frames: %d (delta vs source: %+d frames, %+.2f ms)%n",
                candAnalysis.totalSamples(), candAnalysis.totalSamples() - expectedTotalPcmFrames,
                candAnalysis.totalDurationMs() - expectedTotalPcmDurationMs);
        System.out.printf(Locale.ROOT, "  Candidate Total Packets: %d (%d * 1152 = %d samples)%n",
                candChapterPackets, candChapterPackets, candChapterPackets * MP3_FRAME_SAMPLES);
        System.out.printf(Locale.ROOT, "  Sum of Segment Packets == Candidate Chapter Packets: %b (%d vs %d)%n%n",
                standaloneResults.stream().mapToInt(StandaloneSegmentResult::packetCount).sum() == candChapterPackets,
                standaloneResults.stream().mapToInt(StandaloneSegmentResult::packetCount).sum(), candChapterPackets);

        System.out.println("4. Actual Measured Decoded Content Boundaries (Derived from Signal Truth):");
        System.out.println("+-----+-----------+------------------+------------------+------------------+------------------+");
        System.out.println("| Seg | Tone Freq | Baseline SpStart | Baseline SpEnd   | Candidate SpSt   | Candidate SpEnd  |");
        System.out.println("+-----+-----------+------------------+------------------+------------------+------------------+");
        for (int i = 0; i < fixtures.size(); i++) {
            System.out.printf(Locale.ROOT, "| %-3d | %7.1fHz | %14.2fms | %14.2fms | %14.2fms | %14.2fms |%n",
                    i + 1, fixtures.get(i).toneFrequency(),
                    baselineBoundaries.get(i).speechStartMs(), baselineBoundaries.get(i).speechEndMs(),
                    candBoundaries.get(i).speechStartMs(), candBoundaries.get(i).speechEndMs());
        }
        System.out.println("+-----+-----------+------------------+------------------+------------------+------------------+");
        System.out.println();

        System.out.println("5. Derived Source-Content Timeline Comparison:");
        System.out.println("+-----+------------------+------------------+------------------+------------------+--------------------+");
        System.out.println("| Seg | Expected Start   | Baseline ContSt  | Candidate ContSt | Cand - Base (ms) | Cand Speech Len(ms)|");
        System.out.println("+-----+------------------+------------------+------------------+------------------+--------------------+");
        for (int i = 0; i < fixtures.size(); i++) {
            System.out.printf(Locale.ROOT, "| %-3d | %14.2fms | %14.2fms | %14.2fms | %+14.2fms | %16.2fms |%n",
                    i + 1, sourcePredictedStarts.get(i),
                    baselineBoundaries.get(i).contentStartMs(),
                    candBoundaries.get(i).contentStartMs(),
                    candBoundaries.get(i).contentStartMs() - baselineBoundaries.get(i).contentStartMs(),
                    candBoundaries.get(i).speechDurationMs());
        }
        System.out.println("+-----+------------------+------------------+------------------+------------------+--------------------+");
        System.out.println();

        System.out.println("6. Internal Join Padding Measurements:");
        System.out.println("+-------------------+-------------------+--------------------+--------------------+--------------------+");
        System.out.println("| Join Boundary     | Prev Seg Mod 1152 | Silence Gap (ms)   | Measured Padding   | (Packets*24 - Len) |");
        System.out.println("+-------------------+-------------------+--------------------+--------------------+--------------------+");
        for (int j = 0; j < joinPaddings.size(); j++) {
            double gap = candAnalysis.gapDurationsMs().get(j);
            double padding = joinPaddings.get(j);
            double expectedPacketPadding = standaloneResults.get(j).packetDurationMs() - fixtures.get(j).totalDurationMs();
            System.out.printf(Locale.ROOT, "| Join %d (Seg %d->%d) | %17d | %16.2fms | %+16.2fms | %+16.2fms |%n",
                    j + 1, j + 1, j + 2, fixtures.get(j).remainderModulo1152(), gap, padding, expectedPacketPadding);
        }
        System.out.println("+-------------------+-------------------+--------------------+--------------------+--------------------+");
        System.out.printf(Locale.ROOT, "Distinct Measured Join Padding Values: %s%n", distinctPaddings);
        System.out.printf(Locale.ROOT, "Is Join Padding Constant across variable segments? %b%n%n", distinctPaddings.size() == 1);

        System.out.println("7. Cue Timing Prediction Model Comparison (Evaluating Strategy A vs B vs C):");
        System.out.println("+-----+------------------+--------------------+--------------------+--------------------+");
        System.out.println("| Seg | Measured ContSt  | Strat A: PacketDur | Strat B: SourceDur | Strat C: StaloneDec|");
        System.out.println("+-----+------------------+--------------------+--------------------+--------------------+");
        for (int i = 0; i < fixtures.size(); i++) {
            System.out.printf(Locale.ROOT, "| %-3d | %14.2fms | %14.2fms | %14.2fms | %14.2fms |%n",
                    i + 1, candBoundaries.get(i).contentStartMs(),
                    packetPredictedStarts.get(i), sourcePredictedStarts.get(i), standalonePredictedStarts.get(i));
        }
        System.out.println("+-----+------------------+--------------------+--------------------+--------------------+");
        System.out.println();

        System.out.println("8. Prediction Error Comparison Table (Predicted - Actual):");
        System.out.println("+-----+--------------------+--------------------+--------------------+");
        System.out.println("| Seg | Strat A Error(ms)  | Strat B Error(ms)  | Strat C Error(ms)  |");
        System.out.println("+-----+--------------------+--------------------+--------------------+");
        for (int i = 0; i < fixtures.size(); i++) {
            System.out.printf(Locale.ROOT, "| %-3d | %+16.2fms | %+16.2fms | %+16.2fms |%n",
                    i + 1, packetErrors.get(i), sourceErrors.get(i), standaloneErrors.get(i));
        }
        System.out.println("+-----+--------------------+--------------------+--------------------+");
        System.out.printf(Locale.ROOT, "Strategy A (Packet Duration) Max Absolute Error: %.2f ms%n", maxPacketAbsError);
        System.out.printf(Locale.ROOT, "Strategy B (Source Duration) Max Absolute Error: %.2f ms%n", maxSourceAbsError);
        System.out.printf(Locale.ROOT, "Strategy C (Standalone Decoded) Max Absolute Error: %.2f ms%n", maxStandaloneAbsError);
        System.out.println("================================================================================");

        // ---------------------------------------------------------------------
        // 8. STRICT ASSERTIONS
        // ---------------------------------------------------------------------
        // 1. Process & Decoding success
        assertThat(candDecodeVerify.exitCode()).isZero();
        assertThat(candDecodeVerify.output()).doesNotContain("error", "Error", "Invalid");

        // 2. Format invariants
        assertThat(candProbe.codecName()).isEqualTo("mp3");
        assertThat(candProbe.sampleRate()).isEqualTo(SAMPLE_RATE);
        assertThat(candProbe.channels()).isEqualTo(CHANNELS);

        // 3. Baseline matches PCM source exactly (270,000 frames = 5625.0 ms)
        assertThat(baselineAnalysis.totalSamples()).isEqualTo(expectedTotalPcmFrames);

        // 4. Tone detection: exactly 5 regions detected in order
        assertThat(baselineAnalysis.speechRegions()).hasSize(5);
        assertThat(candAnalysis.speechRegions()).hasSize(5);

        for (int i = 0; i < fixtures.size(); i++) {
            assertThat(baselineAnalysis.speechRegions().get(i).detectedFrequency())
                    .isEqualTo(fixtures.get(i).toneFrequency());
            assertThat(candAnalysis.speechRegions().get(i).detectedFrequency())
                    .isEqualTo(fixtures.get(i).toneFrequency());
        }

        // 5. Speech duration invariance (speech is NOT stretched or compressed, delta <= 5.0 ms)
        for (int i = 0; i < fixtures.size(); i++) {
            double baseSpeechLen = baselineBoundaries.get(i).speechDurationMs();
            double candSpeechLen = candBoundaries.get(i).speechDurationMs();
            assertThat(Math.abs(candSpeechLen - baseSpeechLen)).isLessThanOrEqualTo(5.0);
        }

        // 6. Every internal join has measured joinPadding
        assertThat(joinPaddings).hasSize(4);

        // 7. Evidence of variable join padding: at least 2 distinct padding values must be exercised
        assertThat(distinctPaddings.size()).isGreaterThanOrEqualTo(2);

        // 8. Monotonic timestamps across the candidate timeline
        for (int i = 0; i < candBoundaries.size(); i++) {
            ContentBoundary b = candBoundaries.get(i);
            assertThat(b.contentStartMs()).isLessThan(b.speechStartMs());
            assertThat(b.speechStartMs()).isLessThan(b.speechEndMs());
            assertThat(b.speechEndMs()).isLessThan(b.contentEndMs());
            if (i < candBoundaries.size() - 1) {
                assertThat(b.contentEndMs()).isLessThanOrEqualTo(candBoundaries.get(i + 1).contentStartMs());
            }
        }

        // 9. Timing strategy validation: Strategy A (Cumulative Packet Duration) reproduces measured contentStart within <= 5.0 ms
        assertThat(maxPacketAbsError).isLessThanOrEqualTo(5.0);
    }

    private static List<ContentBoundary> deriveContentBoundaries(AudioAnalysisResult analysis) {
        List<ContentBoundary> boundaries = new ArrayList<>();
        for (int i = 0; i < analysis.speechRegions().size(); i++) {
            SpeechRegion r = analysis.speechRegions().get(i);
            double spStart = r.startMs();
            double spEnd = r.endMs();
            double spDuration = spEnd - spStart;

            double contStart = spStart - KNOWN_LEADING_SILENCE_MS;
            double contEnd = spEnd + KNOWN_TRAILING_SILENCE_MS;
            double contDuration = contEnd - contStart;

            boundaries.add(new ContentBoundary(
                    i, spStart, spEnd, spDuration, contStart, contEnd, contDuration
            ));
        }
        return boundaries;
    }

    private static void encodeSegmentCanonical(Path sourceWav, Path outputMp3) throws Exception {
        ProcessResult result = runProcess(
                Duration.ofSeconds(30),
                "ffmpeg",
                "-hide_banner",
                "-loglevel", "error",
                "-nostats",
                "-y",
                "-f", "wav",
                "-i", sourceWav.toAbsolutePath().toString(),
                "-map_metadata", "-1",
                "-map_chapters", "-1",
                "-vn",
                "-c:a", "libmp3lame",
                "-b:a", "96k",
                "-ar", "48000",
                "-ac", "1",
                "-id3v2_version", "0",
                "-f", "mp3",
                outputMp3.toAbsolutePath().toString()
        );
        assertThat(result.exitCode()).isZero();
    }

    private static void writeConcatManifest(Path manifestPath, List<Path> mp3Segments) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Path seg : mp3Segments) {
            sb.append("file '").append(seg.toAbsolutePath().toString().replace('\\', '/')).append("'\n");
        }
        Files.writeString(manifestPath, sb.toString(), StandardCharsets.UTF_8);
    }

    private static void concatStreams(Path manifestPath, Path outputPath) throws Exception {
        ProcessResult result = runProcess(
                Duration.ofSeconds(30),
                "ffmpeg",
                "-hide_banner",
                "-loglevel", "error",
                "-nostats",
                "-y",
                "-f", "concat",
                "-safe", "0",
                "-i", manifestPath.toAbsolutePath().toString(),
                "-c:a", "copy",
                outputPath.toAbsolutePath().toString()
        );
        assertThat(result.exitCode()).isZero();
    }

    private static ProcessResult verifyDecode(Path audioFile) throws Exception {
        return runProcess(
                Duration.ofSeconds(30),
                "ffmpeg",
                "-v", "error",
                "-i", audioFile.toAbsolutePath().toString(),
                "-f", "null",
                "-"
        );
    }

    private static void decodeToRawPcm(Path audioFile, Path rawPcmOutput) throws Exception {
        ProcessResult result = runProcess(
                Duration.ofSeconds(30),
                "ffmpeg",
                "-v", "error",
                "-y",
                "-i", audioFile.toAbsolutePath().toString(),
                "-f", "s16le",
                "-ac", "1",
                "-ar", "48000",
                rawPcmOutput.toAbsolutePath().toString()
        );
        assertThat(result.exitCode()).isZero();
    }

    private static int countPackets(Path audioFile) throws Exception {
        ProcessResult result = runProcess(
                Duration.ofSeconds(30),
                "ffprobe",
                "-v", "error",
                "-select_streams", "a:0",
                "-show_entries", "packet=pts",
                "-of", "csv=p=0",
                audioFile.toAbsolutePath().toString()
        );
        if (result.exitCode() != 0 || result.output().isBlank()) {
            return 0;
        }
        return result.output().trim().split("\\R+").length;
    }

    private static ProbeResult probe(Path audioFile) throws Exception {
        ProcessResult result = runProcess(
                Duration.ofSeconds(30),
                "ffprobe",
                "-v", "error",
                "-show_entries", "format=duration,bit_rate,format_name",
                "-show_entries", "stream=codec_name,sample_rate,channels,channel_layout,duration,nb_frames",
                "-of", "json",
                audioFile.toAbsolutePath().toString()
        );
        assertThat(result.exitCode()).isZero();

        JsonNode root = MAPPER.readTree(result.output());
        JsonNode formatNode = root.path("format");
        JsonNode streamNode = root.path("streams").path(0);

        double duration = formatNode.path("duration").asDouble(0.0);
        if (duration == 0.0) {
            duration = streamNode.path("duration").asDouble(0.0);
        }
        long bitRate = formatNode.path("bit_rate").asLong(0L);
        String codecName = streamNode.path("codec_name").asText("");
        int sampleRate = streamNode.path("sample_rate").asInt(0);
        int channels = streamNode.path("channels").asInt(0);

        return new ProbeResult(codecName, sampleRate, channels, duration, bitRate);
    }

    private record ProbeResult(
            String codecName,
            int sampleRate,
            int channels,
            double durationSeconds,
            long bitRate
    ) {}

    private static ProcessResult runProcess(Duration timeout, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread readerThread = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (out.size() < 64 * 1024) {
                        out.write(buf, 0, Math.min(n, 64 * 1024 - out.size()));
                    }
                }
            } catch (IOException ignored) {
            }
        }, "process-stdout-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            readerThread.interrupt();
            throw new IllegalStateException("Process timed out after " + timeout.toSeconds() + "s: " + String.join(" ", command));
        }
        readerThread.join(TimeUnit.SECONDS.toMillis(2));

        return new ProcessResult(process.exitValue(), out.toString(StandardCharsets.UTF_8));
    }

    private record ProcessResult(int exitCode, String output) {}

    private record SpeechRegion(
            int index,
            double detectedFrequency,
            long startSample,
            long endSample,
            double startMs,
            double endMs,
            double durationMs
    ) {}

    private record AudioAnalysisResult(
            long totalSamples,
            double totalDurationMs,
            List<SpeechRegion> speechRegions,
            List<Double> gapDurationsMs,
            double leadingSilenceMs,
            double trailingSilenceMs
    ) {}

    private static AudioAnalysisResult analyzePcm(Path pcmPath) throws IOException {
        byte[] bytes = Files.readAllBytes(pcmPath);
        short[] samples = new short[bytes.length / 2];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);

        int totalSamples = samples.length;
        double totalDurationMs = (totalSamples * 1000.0) / SAMPLE_RATE;

        // Window size: 192 samples (4 ms), hop: 48 samples (1 ms)
        int windowSize = 192;
        int hopSize = 48;
        int numFrames = (totalSamples - windowSize) / hopSize + 1;

        boolean[] isSpeech = new boolean[numFrames];
        for (int f = 0; f < numFrames; f++) {
            int start = f * hopSize;
            long sumSq = 0;
            for (int i = 0; i < windowSize; i++) {
                long s = samples[start + i];
                sumSq += s * s;
            }
            double rms = Math.sqrt((double) sumSq / windowSize);
            isSpeech[f] = rms >= 200.0;
        }

        // Debounce/bridge small gaps < 20 ms (20 frames)
        for (int f = 0; f < numFrames; f++) {
            if (!isSpeech[f]) {
                int gapStart = f;
                while (f < numFrames && !isSpeech[f]) {
                    f++;
                }
                int gapLength = f - gapStart;
                if (gapLength < 20 && gapStart > 0 && f < numFrames) {
                    for (int g = gapStart; g < f; g++) {
                        isSpeech[g] = true;
                    }
                }
            }
        }

        // Identify contiguous speech segments
        List<SpeechRegion> regions = new ArrayList<>();
        int f = 0;
        while (f < numFrames) {
            if (isSpeech[f]) {
                int startFrame = f;
                while (f < numFrames && isSpeech[f]) {
                    f++;
                }
                int endFrame = f - 1;

                // Coarse window boundaries
                int coarseStartSample = startFrame * hopSize;
                int coarseEndSample = Math.min(totalSamples - 1, (endFrame * hopSize) + windowSize);

                // Sample-level refinement
                int startSample = coarseStartSample;
                while (startSample < coarseEndSample && Math.abs(samples[startSample]) < 100) {
                    startSample++;
                }

                int endSample = coarseEndSample;
                while (endSample > startSample && Math.abs(samples[endSample]) < 100) {
                    endSample--;
                }

                double startMs = (startSample * 1000.0) / SAMPLE_RATE;
                double endMs = (endSample * 1000.0) / SAMPLE_RATE;
                double durationMs = endMs - startMs;

                // Frequency identification using Goertzel in middle 100ms
                int centerSample = (startSample + endSample) / 2;
                int goertzelLen = Math.min(4800, endSample - startSample);
                int goertzelStart = Math.max(startSample, centerSample - (goertzelLen / 2));

                double detectedFreq = CANDIDATE_FREQS[0];
                double maxPower = -1.0;
                for (double targetFreq : CANDIDATE_FREQS) {
                    double p = goertzelPower(samples, goertzelStart, goertzelLen, targetFreq, SAMPLE_RATE);
                    if (p > maxPower) {
                        maxPower = p;
                        detectedFreq = targetFreq;
                    }
                }

                regions.add(new SpeechRegion(
                        regions.size(),
                        detectedFreq,
                        startSample,
                        endSample,
                        startMs,
                        endMs,
                        durationMs
                ));
            } else {
                f++;
            }
        }

        double leadingSilenceMs = regions.isEmpty() ? 0.0 : regions.get(0).startMs();
        double trailingSilenceMs = regions.isEmpty() ? 0.0 : totalDurationMs - regions.get(regions.size() - 1).endMs();

        List<Double> gaps = new ArrayList<>();
        for (int r = 0; r < regions.size() - 1; r++) {
            double gap = regions.get(r + 1).startMs() - regions.get(r).endMs();
            gaps.add(gap);
        }

        return new AudioAnalysisResult(
                totalSamples,
                totalDurationMs,
                regions,
                gaps,
                leadingSilenceMs,
                trailingSilenceMs
        );
    }

    private static double goertzelPower(short[] samples, int start, int length, double targetFreq, int sampleRate) {
        double omega = 2.0 * Math.PI * targetFreq / sampleRate;
        double coeff = 2.0 * Math.cos(omega);
        double s0 = 0.0;
        double s1 = 0.0;
        double s2 = 0.0;

        int end = Math.min(samples.length, start + length);
        int count = end - start;
        for (int i = start; i < end; i++) {
            s0 = samples[i] + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        double power = s1 * s1 + s2 * s2 - coeff * s1 * s2;
        return power / ((double) count * count);
    }

    private static byte[] createSegmentWav(int leadingSilenceFrames, int speechFrames, int trailingSilenceFrames, double frequency) {
        int totalFrames = leadingSilenceFrames + speechFrames + trailingSilenceFrames;
        short[] samples = new short[totalFrames];

        int offset = leadingSilenceFrames;
        double angularFreq = 2.0 * Math.PI * frequency / SAMPLE_RATE;
        for (int i = 0; i < speechFrames; i++) {
            samples[offset + i] = (short) (Math.sin(i * angularFreq) * 2000.0);
        }

        return wavPcm16(CHANNELS, SAMPLE_RATE, samples);
    }

    private static byte[] wavPcm16(int channels, int sampleRate, short[] samples) {
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
}
