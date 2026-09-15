package com.universe.novel.infrastructure.narration.audio;

import com.universe.novel.application.narration.ChapterAudioAssemblyRequest;
import com.universe.novel.application.narration.ChapterAudioAssemblyResult;
import com.universe.novel.application.narration.ChapterAudioSegmentSource;
import com.universe.novel.application.narration.SegmentAudioEncodingRequest;
import com.universe.novel.application.narration.SegmentAudioEncodingResult;
import com.universe.novel.infrastructure.narration.concurrency.NarrationFfmpegExecutionGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FFmpeg Cross-Adapter Resource Gate Concurrency Tests (H.10A3)")
class NarrationFfmpegCrossAdapterGateTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final byte[] DUMMY_MP3 = new byte[]{'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4};
    private static final byte[] SOURCE_WAV = new byte[]{
            'R', 'I', 'F', 'F', 40, 0, 0, 0, 'W', 'A', 'V', 'E',
            'f', 'm', 't', ' ', 16, 0, 0, 0, 1, 0, 1, 0,
            (byte) 0x80, (byte) 0xBB, 0, 0, 0, 0x77, 1, 0, 2, 0, 16, 0,
            'd', 'a', 't', 'a', 4, 0, 0, 0, 0, 0, 10, 0
    };

    private final List<Path> tempFiles = new ArrayList<>();
    private final List<Path> createdWorkspaces = new ArrayList<>();
    private NarrationFfmpegExecutionGate sharedGate;

    @BeforeEach
    void setUp() {
        sharedGate = new NarrationFfmpegExecutionGate(1);
    }

    @AfterEach
    void tearDown() throws IOException {
        for (Path f : tempFiles) {
            Files.deleteIfExists(f);
        }
        for (Path w : createdWorkspaces) {
            FfmpegMp3ConcatChapterAudioAssemblerAdapter.deleteRecursively(w);
        }
    }

    @Test
    @DisplayName("Direction 1: Segment encoder holds shared gate -> Chapter assembler cannot enter FFmpeg concat until released")
    void segmentEncoderHoldsGate_chapterAssemblerWaits() throws Exception {
        CountDownLatch segmentEncoderEntered = new CountDownLatch(1);
        CountDownLatch releaseSegmentEncoder = new CountDownLatch(1);
        AtomicBoolean chapterEnteredWhileSegmentRunning = new AtomicBoolean(false);

        Path segOutputFile = Files.createTempFile("cross_seg_out_", ".mp3");
        tempFiles.add(segOutputFile);

        FfmpegMp3SegmentAudioEncoderAdapter segmentEncoder = new FfmpegMp3SegmentAudioEncoderAdapter(
                "ffmpeg",
                "ffprobe",
                TIMEOUT,
                () -> segOutputFile,
                Files::deleteIfExists,
                (cmd, stream, to) -> {
                    segmentEncoderEntered.countDown();
                    try {
                        releaseSegmentEncoder.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    Files.write(segOutputFile, DUMMY_MP3);
                    return new FfmpegMp3SegmentAudioEncoderAdapter.EncoderProcessResult(0, "");
                },
                (file, to) -> new FfmpegMp3SegmentAudioEncoderAdapter.SegmentProbeData("mp3", 48000, 1, 10),
                sharedGate
        );

        FfmpegMp3ConcatChapterAudioAssemblerAdapter chapterAssembler = new FfmpegMp3ConcatChapterAudioAssemblerAdapter(
                "ffmpeg",
                "ffprobe",
                TIMEOUT,
                () -> {
                    Path dir = Files.createTempDirectory("cross_concat_ws_");
                    createdWorkspaces.add(dir);
                    return dir;
                },
                FfmpegMp3ConcatChapterAudioAssemblerAdapter::deleteRecursively,
                (cmd, dir, to) -> {
                    chapterEnteredWhileSegmentRunning.set(releaseSegmentEncoder.getCount() > 0);
                    Files.write(dir.resolve("chapter.mp3"), DUMMY_MP3);
                    return new FfmpegMp3ConcatChapterAudioAssemblerAdapter.ConcatProcessResult(0, "");
                },
                (file, to) -> new FfmpegMp3ConcatChapterAudioAssemblerAdapter.ChapterProbeData("mp3", 48000, 1, new BigDecimal("0.024")),
                sharedGate
        );

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<SegmentAudioEncodingResult> segFuture = pool.submit(() ->
                    segmentEncoder.encode(SegmentAudioEncodingRequest.of("audio/wav", SOURCE_WAV)));

            assertThat(segmentEncoderEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(sharedGate.getAvailablePermits()).isEqualTo(0);

            ChapterAudioSegmentSource s1 = new ChapterAudioSegmentSource(
                    UUID.randomUUID(), 0, "audio/mpeg",
                    () -> new ByteArrayInputStream(DUMMY_MP3), 1152L, 48000
            );

            Future<ChapterAudioAssemblyResult> concatFuture = pool.submit(() ->
                    chapterAssembler.assemble(new ChapterAudioAssemblyRequest(List.of(s1))));

            Thread.sleep(100);
            assertThat(sharedGate.getQueueLength()).isEqualTo(1);
            assertThat(chapterEnteredWhileSegmentRunning.get()).isFalse();

            // Release segment encoder
            releaseSegmentEncoder.countDown();

            SegmentAudioEncodingResult segResult = segFuture.get(5, TimeUnit.SECONDS);
            ChapterAudioAssemblyResult concatResult = concatFuture.get(5, TimeUnit.SECONDS);

            assertThat(segResult).isNotNull();
            assertThat(concatResult).isNotNull();
            assertThat(chapterEnteredWhileSegmentRunning.get()).isFalse();
            assertThat(sharedGate.getAvailablePermits()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Direction 2: Chapter assembler holds shared gate -> Segment encoder cannot enter FFmpeg encode until released")
    void chapterAssemblerHoldsGate_segmentEncoderWaits() throws Exception {
        CountDownLatch chapterAssemblerEntered = new CountDownLatch(1);
        CountDownLatch releaseChapterAssembler = new CountDownLatch(1);
        AtomicBoolean segmentEnteredWhileChapterRunning = new AtomicBoolean(false);

        Path segOutputFile = Files.createTempFile("cross_seg_out2_", ".mp3");
        tempFiles.add(segOutputFile);

        FfmpegMp3ConcatChapterAudioAssemblerAdapter chapterAssembler = new FfmpegMp3ConcatChapterAudioAssemblerAdapter(
                "ffmpeg",
                "ffprobe",
                TIMEOUT,
                () -> {
                    Path dir = Files.createTempDirectory("cross_concat_ws2_");
                    createdWorkspaces.add(dir);
                    return dir;
                },
                FfmpegMp3ConcatChapterAudioAssemblerAdapter::deleteRecursively,
                (cmd, dir, to) -> {
                    chapterAssemblerEntered.countDown();
                    try {
                        releaseChapterAssembler.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    Files.write(dir.resolve("chapter.mp3"), DUMMY_MP3);
                    return new FfmpegMp3ConcatChapterAudioAssemblerAdapter.ConcatProcessResult(0, "");
                },
                (file, to) -> new FfmpegMp3ConcatChapterAudioAssemblerAdapter.ChapterProbeData("mp3", 48000, 1, new BigDecimal("0.024")),
                sharedGate
        );

        FfmpegMp3SegmentAudioEncoderAdapter segmentEncoder = new FfmpegMp3SegmentAudioEncoderAdapter(
                "ffmpeg",
                "ffprobe",
                TIMEOUT,
                () -> segOutputFile,
                Files::deleteIfExists,
                (cmd, stream, to) -> {
                    segmentEnteredWhileChapterRunning.set(releaseChapterAssembler.getCount() > 0);
                    Files.write(segOutputFile, DUMMY_MP3);
                    return new FfmpegMp3SegmentAudioEncoderAdapter.EncoderProcessResult(0, "");
                },
                (file, to) -> new FfmpegMp3SegmentAudioEncoderAdapter.SegmentProbeData("mp3", 48000, 1, 10),
                sharedGate
        );

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            ChapterAudioSegmentSource s1 = new ChapterAudioSegmentSource(
                    UUID.randomUUID(), 0, "audio/mpeg",
                    () -> new ByteArrayInputStream(DUMMY_MP3), 1152L, 48000
            );

            Future<ChapterAudioAssemblyResult> concatFuture = pool.submit(() ->
                    chapterAssembler.assemble(new ChapterAudioAssemblyRequest(List.of(s1))));

            assertThat(chapterAssemblerEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(sharedGate.getAvailablePermits()).isEqualTo(0);

            Future<SegmentAudioEncodingResult> segFuture = pool.submit(() ->
                    segmentEncoder.encode(SegmentAudioEncodingRequest.of("audio/wav", SOURCE_WAV)));

            Thread.sleep(100);
            assertThat(sharedGate.getQueueLength()).isEqualTo(1);
            assertThat(segmentEnteredWhileChapterRunning.get()).isFalse();

            // Release chapter assembler
            releaseChapterAssembler.countDown();

            ChapterAudioAssemblyResult concatResult = concatFuture.get(5, TimeUnit.SECONDS);
            SegmentAudioEncodingResult segResult = segFuture.get(5, TimeUnit.SECONDS);

            assertThat(concatResult).isNotNull();
            assertThat(segResult).isNotNull();
            assertThat(segmentEnteredWhileChapterRunning.get()).isFalse();
            assertThat(sharedGate.getAvailablePermits()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
