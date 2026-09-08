package com.universe.novel.infrastructure.narration.audio;

import com.universe.novel.application.narration.ChapterAudioAssemblyCue;
import com.universe.novel.application.narration.ChapterAudioAssemblyRequest;
import com.universe.novel.application.narration.ChapterAudioAssemblyResult;
import com.universe.novel.application.narration.ChapterAudioSegmentSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PCM WAV Chapter Audio Assembler Adapter Tests")
class PcmWavChapterAudioAssemblerAdapterTest {

    private static final UUID SEGMENT_1_ID = UUID.fromString("90000000-0000-0000-0000-000000000001");
    private static final UUID SEGMENT_2_ID = UUID.fromString("90000000-0000-0000-0000-000000000002");
    private static final UUID SEGMENT_3_ID = UUID.fromString("90000000-0000-0000-0000-000000000003");

    @Test
    @DisplayName("single PCM16 WAV segment produces valid temporary WAV resource and one cue")
    void shouldAssembleSinglePcm16WavSegment() throws IOException {
        Path tempFile = Files.createTempFile("h9c_single_", ".wav");
        Files.deleteIfExists(tempFile);
        PcmWavChapterAudioAssemblerAdapter adapter = adapterWritingTo(tempFile);

        ChapterAudioAssemblyResult result = adapter.assemble(new ChapterAudioAssemblyRequest(List.of(
                source(SEGMENT_1_ID, 0, wav(1, 1000, new short[]{100, 200, 300}))
        )));

        assertThat(result.durationMillis()).isEqualTo(3L);
        assertThat(result.cues()).containsExactly(new ChapterAudioAssemblyCue(
                0,
                SEGMENT_1_ID,
                0,
                0L,
                3L
        ));
        assertThat(result.resource().mimeType()).isEqualTo("audio/wav");
        assertThat(Files.exists(tempFile)).isTrue();

        byte[] output = readAll(result);
        assertThat(ascii(output, 0, 4)).isEqualTo("RIFF");
        assertThat(ascii(output, 8, 4)).isEqualTo("WAVE");
        assertThat(readShort(output, 20)).isEqualTo(1);
        assertThat(readShort(output, 22)).isEqualTo(1);
        assertThat(readInt(output, 24)).isEqualTo(1000);
        assertThat(readShort(output, 34)).isEqualTo(16);
        assertThat(readInt(output, 40)).isEqualTo(6);
        assertThat(samples(output)).containsExactly((short) 100, (short) 200, (short) 300);

        result.close();
        assertThat(Files.exists(tempFile)).isFalse();
        result.close();
    }

    @Test
    @DisplayName("multiple compatible segments preserve exact PCM frame order without concatenating WAV files")
    void shouldAssembleMultipleSegmentsInExactFrameOrder() throws IOException {
        Path tempFile = Files.createTempFile("h9c_multi_", ".wav");
        Files.deleteIfExists(tempFile);
        PcmWavChapterAudioAssemblerAdapter adapter = adapterWritingTo(tempFile);

        ChapterAudioAssemblyResult result = adapter.assemble(new ChapterAudioAssemblyRequest(List.of(
                source(SEGMENT_1_ID, 2, wav(1, 1000, new short[]{11, 12})),
                source(SEGMENT_2_ID, 5, wav(1, 1000, new short[]{21, 22, 23})),
                source(SEGMENT_3_ID, 8, wav(1, 1000, new short[]{31}))
        )));

        byte[] output = readAll(result);

        assertThat(result.durationMillis()).isEqualTo(6L);
        assertThat(result.cues()).containsExactly(
                new ChapterAudioAssemblyCue(0, SEGMENT_1_ID, 2, 0L, 2L),
                new ChapterAudioAssemblyCue(1, SEGMENT_2_ID, 5, 2L, 5L),
                new ChapterAudioAssemblyCue(2, SEGMENT_3_ID, 8, 5L, 6L)
        );
        assertThat(samples(output)).containsExactly(
                (short) 11,
                (short) 12,
                (short) 21,
                (short) 22,
                (short) 23,
                (short) 31
        );
        assertThat(countAscii(output, "RIFF")).isEqualTo(1);
        assertThat(countAscii(output, "WAVE")).isEqualTo(1);
        assertThat(result.resource().sizeBytes()).isEqualTo(output.length);

        result.close();
        assertThat(Files.exists(tempFile)).isFalse();
    }

    @Test
    @DisplayName("cue boundaries and duration are derived from cumulative frame offsets")
    void shouldDeriveCueTimelineFromFrameOffsets() throws IOException {
        Path tempFile = Files.createTempFile("h9c_frame_clock_", ".wav");
        Files.deleteIfExists(tempFile);
        PcmWavChapterAudioAssemblerAdapter adapter = adapterWritingTo(tempFile);

        ChapterAudioAssemblyResult result = adapter.assemble(new ChapterAudioAssemblyRequest(List.of(
                source(SEGMENT_1_ID, 0, wav(1, 3, new short[]{10})),
                source(SEGMENT_2_ID, 1, wav(1, 3, new short[]{20}))
        )));

        assertThat(result.cues()).containsExactly(
                new ChapterAudioAssemblyCue(0, SEGMENT_1_ID, 0, 0L, 333L),
                new ChapterAudioAssemblyCue(1, SEGMENT_2_ID, 1, 333L, 666L)
        );
        assertThat(result.durationMillis()).isEqualTo(666L);

        result.close();
    }

    @Test
    @DisplayName("H.9C v1 adds no inter-segment pause and preserves source edge frames")
    void shouldAddNoInterSegmentPause() throws IOException {
        Path tempFile = Files.createTempFile("h9c_zero_pause_", ".wav");
        Files.deleteIfExists(tempFile);
        PcmWavChapterAudioAssemblerAdapter adapter = adapterWritingTo(tempFile);

        ChapterAudioAssemblyResult result = adapter.assemble(new ChapterAudioAssemblyRequest(List.of(
                source(SEGMENT_1_ID, 0, wav(1, 1000, new short[]{0, 100, 0})),
                source(SEGMENT_2_ID, 1, wav(1, 1000, new short[]{0, 200, 0}))
        )));

        assertThat(result.durationMillis()).isEqualTo(6L);
        assertThat(result.cues()).containsExactly(
                new ChapterAudioAssemblyCue(0, SEGMENT_1_ID, 0, 0L, 3L),
                new ChapterAudioAssemblyCue(1, SEGMENT_2_ID, 1, 3L, 6L)
        );
        assertThat(samples(readAll(result))).containsExactly(
                (short) 0,
                (short) 100,
                (short) 0,
                (short) 0,
                (short) 200,
                (short) 0
        );

        result.close();
    }

    @Test
    @DisplayName("request rejects empty, unsorted, duplicate segment index, and duplicate segment ID inputs")
    void shouldRejectMalformedInputOrder() {
        assertThatThrownBy(() -> new ChapterAudioAssemblyRequest(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");

        assertThatThrownBy(() -> new ChapterAudioAssemblyRequest(List.of(
                source(SEGMENT_1_ID, 2, wav(1, 1000, new short[]{1})),
                source(SEGMENT_2_ID, 1, wav(1, 1000, new short[]{2}))
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly increasing");

        assertThatThrownBy(() -> new ChapterAudioAssemblyRequest(List.of(
                source(SEGMENT_1_ID, 1, wav(1, 1000, new short[]{1})),
                source(SEGMENT_2_ID, 1, wav(1, 1000, new short[]{2}))
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate segmentIndex");

        assertThatThrownBy(() -> new ChapterAudioAssemblyRequest(List.of(
                source(SEGMENT_1_ID, 1, wav(1, 1000, new short[]{1})),
                source(SEGMENT_1_ID, 2, wav(1, 1000, new short[]{2}))
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate segmentId");
    }

    @Test
    @DisplayName("malformed WAV and non-WAV/compressed sources are rejected")
    void shouldRejectMalformedAndCompressedSources() {
        PcmWavChapterAudioAssemblerAdapter adapter = new PcmWavChapterAudioAssemblerAdapter();

        assertThatThrownBy(() -> adapter.assemble(new ChapterAudioAssemblyRequest(List.of(
                source(SEGMENT_1_ID, 0, "audio/wav", new byte[]{1, 2, 3})
        )))).isInstanceOf(RuntimeException.class);

        assertThatThrownBy(() -> adapter.assemble(new ChapterAudioAssemblyRequest(List.of(
                source(SEGMENT_1_ID, 0, "audio/mpeg", new byte[]{1, 2, 3})
        )))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported source MIME type");
    }

    @Test
    @DisplayName("unsupported WAV encoding and bit depth are rejected")
    void shouldRejectUnsupportedWavFormats() {
        PcmWavChapterAudioAssemblerAdapter adapter = new PcmWavChapterAudioAssemblerAdapter();

        assertThatThrownBy(() -> adapter.assemble(new ChapterAudioAssemblyRequest(List.of(
                source(SEGMENT_1_ID, 0, wav(1, 1000, 3, 16, new byte[]{1, 2}))
        )))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported WAV encoding");

        assertThatThrownBy(() -> adapter.assemble(new ChapterAudioAssemblyRequest(List.of(
                source(SEGMENT_1_ID, 0, wav(1, 1000, 1, 8, new byte[]{(byte) 128}))
        )))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported WAV bit depth");
    }

    @Test
    @DisplayName("too-short PCM contributions are rejected when they cannot be represented as millisecond cues")
    void shouldRejectCueThatCannotBeRepresentedInMilliseconds() {
        PcmWavChapterAudioAssemblerAdapter adapter = new PcmWavChapterAudioAssemblerAdapter();

        assertThatThrownBy(() -> adapter.assemble(new ChapterAudioAssemblyRequest(List.of(
                source(SEGMENT_1_ID, 0, wav(1, 48_000, new short[]{1}))
        )))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too short for millisecond cue representation");
    }

    @Test
    @DisplayName("incompatible sample rate and channel count are rejected and partial output is deleted")
    void shouldRejectIncompatibleFormatsAndDeletePartialOutput() throws IOException {
        Path tempFile = Files.createTempFile("h9c_partial_failure_", ".wav");
        Files.deleteIfExists(tempFile);
        PcmWavChapterAudioAssemblerAdapter adapter = adapterWritingTo(tempFile);

        assertThatThrownBy(() -> adapter.assemble(new ChapterAudioAssemblyRequest(List.of(
                source(SEGMENT_1_ID, 0, wav(1, 1000, new short[]{1})),
                source(SEGMENT_2_ID, 1, wav(1, 2000, new short[]{2}))
        )))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Incompatible PCM WAV format");
        assertThat(Files.exists(tempFile)).isFalse();

        Path secondTempFile = Files.createTempFile("h9c_channel_failure_", ".wav");
        Files.deleteIfExists(secondTempFile);
        PcmWavChapterAudioAssemblerAdapter secondAdapter = adapterWritingTo(secondTempFile);

        assertThatThrownBy(() -> secondAdapter.assemble(new ChapterAudioAssemblyRequest(List.of(
                source(SEGMENT_1_ID, 0, wav(1, 1000, new short[]{1})),
                source(SEGMENT_2_ID, 1, wav(2, 1000, new short[]{2, 3}))
        )))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Incompatible PCM WAV format");
        assertThat(Files.exists(secondTempFile)).isFalse();
    }

    @Test
    @DisplayName("cleanup failure is suppressed under the original assembly failure")
    void shouldSuppressCleanupFailureUnderOriginalAssemblyFailure() throws IOException {
        Path tempFile = Files.createTempFile("h9c_cleanup_failure_", ".wav");
        Files.deleteIfExists(tempFile);
        IOException cleanupFailure = new IOException("simulated cleanup failure");
        PcmWavChapterAudioAssemblerAdapter adapter = new PcmWavChapterAudioAssemblerAdapter(
                () -> tempFile,
                ignored -> {
                    throw cleanupFailure;
                }
        );

        try {
            assertThatThrownBy(() -> adapter.assemble(new ChapterAudioAssemblyRequest(List.of(
                    source(SEGMENT_1_ID, 0, wav(1, 1000, new short[]{1})),
                    source(SEGMENT_2_ID, 1, wav(1, 2000, new short[]{2}))
            )))).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Incompatible PCM WAV format")
                    .satisfies(failure -> assertThat(failure.getSuppressed()).containsExactly(cleanupFailure));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    @DisplayName("assembler opens and closes source streams it consumes")
    void shouldCloseOpenedSourceStreams() throws IOException {
        PcmWavChapterAudioAssemblerAdapter adapter = new PcmWavChapterAudioAssemblerAdapter();
        CloseTrackingInputStream first = new CloseTrackingInputStream(wav(1, 1000, new short[]{1}));
        CloseTrackingInputStream second = new CloseTrackingInputStream(wav(1, 1000, new short[]{2}));

        ChapterAudioAssemblyResult result = adapter.assemble(new ChapterAudioAssemblyRequest(List.of(
                source(SEGMENT_1_ID, 0, "audio/wav", () -> first),
                source(SEGMENT_2_ID, 1, "audio/wav", () -> second)
        )));

        assertThat(first.closed()).isTrue();
        assertThat(second.closed()).isTrue();
        result.close();
    }

    @Test
    @DisplayName("temporary resource refuses close while returned stream is open, then deletes after stream close")
    void shouldManageReturnedStreamOwnership() throws IOException {
        Path tempFile = Files.createTempFile("h9c_resource_", ".wav");
        Files.write(tempFile, wav(1, 1000, new short[]{7}));
        TempFileChapterAudioAssemblyResource resource =
                new TempFileChapterAudioAssemblyResource(tempFile, "audio/wav", Files.size(tempFile));

        InputStream stream = resource.openStream();

        assertThatThrownBy(resource::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("open stream");
        assertThat(Files.exists(tempFile)).isTrue();

        stream.close();
        resource.close();
        assertThat(Files.exists(tempFile)).isFalse();
        resource.close();
    }

    @Test
    @DisplayName("tracked stream close decrements when underlying stream close throws")
    void shouldReleaseTrackedStreamWhenUnderlyingCloseThrows() throws IOException {
        Path tempFile = Files.createTempFile("h9c_resource_close_failure_", ".wav");
        Files.write(tempFile, wav(1, 1000, new short[]{7}));
        CloseThrowingInputStream rawStream = new CloseThrowingInputStream(wav(1, 1000, new short[]{7}));
        TempFileChapterAudioAssemblyResource resource = new TempFileChapterAudioAssemblyResource(
                tempFile,
                "audio/wav",
                Files.size(tempFile),
                ignored -> rawStream
        );

        InputStream stream = resource.openStream();

        assertThatThrownBy(stream::close)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("simulated close failure");
        assertThat(rawStream.closeCalls()).isEqualTo(1);

        stream.close();
        assertThat(rawStream.closeCalls()).isEqualTo(1);

        resource.close();
        assertThat(Files.exists(tempFile)).isFalse();
        resource.close();
    }

    private static PcmWavChapterAudioAssemblerAdapter adapterWritingTo(Path tempFile) {
        return new PcmWavChapterAudioAssemblerAdapter(() -> tempFile);
    }

    private static ChapterAudioSegmentSource source(UUID segmentId, int segmentIndex, byte[] audioBytes) {
        return source(segmentId, segmentIndex, "audio/wav", audioBytes);
    }

    private static ChapterAudioSegmentSource source(UUID segmentId, int segmentIndex, String mimeType, byte[] audioBytes) {
        return new ChapterAudioSegmentSource(
                segmentId,
                segmentIndex,
                mimeType,
                () -> new ByteArrayInputStream(audioBytes)
        );
    }

    private static ChapterAudioSegmentSource source(
            UUID segmentId,
            int segmentIndex,
            String mimeType,
            com.universe.novel.application.narration.ChapterAudioSegmentBinarySource binarySource
    ) {
        return new ChapterAudioSegmentSource(segmentId, segmentIndex, mimeType, binarySource);
    }

    private static byte[] readAll(ChapterAudioAssemblyResult result) throws IOException {
        try (InputStream inputStream = result.resource().openStream()) {
            return inputStream.readAllBytes();
        }
    }

    private static byte[] wav(int channels, int sampleRate, short[] samples) {
        byte[] data = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            data[i * 2] = (byte) (samples[i] & 0xFF);
            data[i * 2 + 1] = (byte) ((samples[i] >>> 8) & 0xFF);
        }
        return wav(channels, sampleRate, 1, 16, data);
    }

    private static byte[] wav(int channels, int sampleRate, int audioFormat, int bitsPerSample, byte[] data) {
        int blockAlign = channels * Math.max(1, bitsPerSample / 8);
        int byteRate = sampleRate * blockAlign;
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + data.length);
        try {
            out.write(new byte[]{'R', 'I', 'F', 'F'});
            writeInt(out, 36 + data.length);
            out.write(new byte[]{'W', 'A', 'V', 'E'});
            out.write(new byte[]{'f', 'm', 't', ' '});
            writeInt(out, 16);
            writeShort(out, audioFormat);
            writeShort(out, channels);
            writeInt(out, sampleRate);
            writeInt(out, byteRate);
            writeShort(out, blockAlign);
            writeShort(out, bitsPerSample);
            out.write(new byte[]{'d', 'a', 't', 'a'});
            writeInt(out, data.length);
            out.write(data);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    private static List<Short> samples(byte[] wav) {
        int dataSize = readInt(wav, 40);
        java.util.ArrayList<Short> samples = new java.util.ArrayList<>();
        for (int offset = 44; offset < 44 + dataSize; offset += 2) {
            samples.add((short) ((wav[offset] & 0xFF) | ((wav[offset + 1] & 0xFF) << 8)));
        }
        return samples;
    }

    private static int countAscii(byte[] bytes, String value) {
        byte[] needle = value.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int count = 0;
        for (int i = 0; i <= bytes.length - needle.length; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (bytes[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                count++;
            }
        }
        return count;
    }

    private static String ascii(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static int readInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) |
                ((bytes[offset + 1] & 0xFF) << 8) |
                ((bytes[offset + 2] & 0xFF) << 16) |
                ((bytes[offset + 3] & 0xFF) << 24);
    }

    private static int readShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    private static void writeShort(ByteArrayOutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static final class CloseTrackingInputStream extends FilterInputStream {

        private final AtomicBoolean closed = new AtomicBoolean(false);

        private CloseTrackingInputStream(byte[] bytes) {
            super(new ByteArrayInputStream(bytes));
        }

        @Override
        public void close() throws IOException {
            closed.set(true);
            super.close();
        }

        private boolean closed() {
            return closed.get();
        }
    }

    private static final class CloseThrowingInputStream extends ByteArrayInputStream {

        private int closeCalls = 0;

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
}
