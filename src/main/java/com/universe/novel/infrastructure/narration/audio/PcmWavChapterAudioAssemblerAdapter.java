package com.universe.novel.infrastructure.narration.audio;

import com.universe.novel.application.narration.ChapterAudioAssemblyCue;
import com.universe.novel.application.narration.ChapterAudioAssemblyRequest;
import com.universe.novel.application.narration.ChapterAudioAssemblyResult;
import com.universe.novel.application.narration.ChapterAudioSegmentSource;
import com.universe.novel.application.ports.ChapterAudioAssemblerPort;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * File-backed assembler for compatible PCM16 WAV narration segments.
 */
public class PcmWavChapterAudioAssemblerAdapter implements ChapterAudioAssemblerPort {

    private static final String OUTPUT_MIME_TYPE = "audio/wav";
    private static final int RIFF_HEADER_SIZE = 12;
    private static final int CHUNK_HEADER_SIZE = 8;
    private static final int PCM_FORMAT = 1;
    private static final int BITS_PER_SAMPLE_16 = 16;
    private static final long UINT32_MAX = 0xFFFFFFFFL;
    private static final int COPY_BUFFER_SIZE = 8192;

    @FunctionalInterface
    interface TempFileFactory {
        Path create() throws IOException;
    }

    @FunctionalInterface
    interface TempFileDeleter {
        void deleteIfExists(Path path) throws IOException;
    }

    private final TempFileFactory tempFileFactory;
    private final TempFileDeleter tempFileDeleter;

    public PcmWavChapterAudioAssemblerAdapter() {
        this(() -> Files.createTempFile("novel_chapter_audio_", ".wav"));
    }

    PcmWavChapterAudioAssemblerAdapter(TempFileFactory tempFileFactory) {
        this(tempFileFactory, Files::deleteIfExists);
    }

    PcmWavChapterAudioAssemblerAdapter(TempFileFactory tempFileFactory, TempFileDeleter tempFileDeleter) {
        this.tempFileFactory = Objects.requireNonNull(tempFileFactory, "tempFileFactory must not be null");
        this.tempFileDeleter = Objects.requireNonNull(tempFileDeleter, "tempFileDeleter must not be null");
    }

    @Override
    public ChapterAudioAssemblyResult assemble(ChapterAudioAssemblyRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        validateRequestOrder(request.segments());

        Path tempFile = createTempFile();

        try {
            List<ChapterAudioAssemblyCue> cues = new ArrayList<>(request.segments().size());
            PcmFormat commonFormat = null;
            long cumulativeFrames = 0L;
            long totalDataBytes = 0L;

            try (OutputStream outputStream = Files.newOutputStream(tempFile)) {
                writePlaceholderHeader(outputStream);

                for (int i = 0; i < request.segments().size(); i++) {
                    ChapterAudioSegmentSource source = request.segments().get(i);
                    requireWavMimeType(source);

                    try (InputStream inputStream = source.binarySource().openStream()) {
                        if (inputStream == null) {
                            throw new IllegalArgumentException("Segment binary source returned null stream: " + source.segmentId());
                        }
                        ParsedWav wav = copyPcmData(source, inputStream, outputStream);
                        if (commonFormat == null) {
                            commonFormat = wav.format();
                        } else if (!commonFormat.equals(wav.format())) {
                            throw new IllegalArgumentException("Incompatible PCM WAV format for segment: " + source.segmentId());
                        }

                        long segmentFrames = wav.frameCount();
                        long startFrame = cumulativeFrames;
                        long endFrame = cumulativeFrames + segmentFrames;
                        if (endFrame < cumulativeFrames) {
                            throw new IllegalArgumentException("Assembled audio frame count overflow.");
                        }
                        long startMillis = framesToMillis(startFrame, commonFormat.sampleRate());
                        long endMillis = framesToMillis(endFrame, commonFormat.sampleRate());
                        if (endMillis <= startMillis) {
                            throw new IllegalArgumentException(
                                    "Segment PCM contribution is too short for millisecond cue representation: " + source.segmentId()
                            );
                        }

                        cues.add(new ChapterAudioAssemblyCue(
                                i,
                                source.segmentId(),
                                source.segmentIndex(),
                                startMillis,
                                endMillis
                        ));

                        cumulativeFrames = endFrame;
                        totalDataBytes = checkedAddDataBytes(totalDataBytes, wav.dataSizeBytes());
                    } catch (IOException e) {
                        throw new IllegalStateException("Failed to read segment audio source: " + source.segmentId(), e);
                    }
                }
            }

            if (commonFormat == null || cumulativeFrames <= 0) {
                throw new IllegalArgumentException("Assembly requires at least one non-empty PCM WAV segment.");
            }

            writeFinalHeader(tempFile, commonFormat, totalDataBytes);
            long fileSize = Files.size(tempFile);
            long durationMillis = framesToMillis(cumulativeFrames, commonFormat.sampleRate());
            ChapterAudioAssemblyResult result = new ChapterAudioAssemblyResult(
                    new TempFileChapterAudioAssemblyResource(tempFile, OUTPUT_MIME_TYPE, fileSize),
                    durationMillis,
                    cues
            );
            return result;
        } catch (IOException e) {
            IllegalStateException failure = new IllegalStateException("Failed to assemble chapter audio.", e);
            deleteTempFileAfterFailure(tempFile, failure);
            throw failure;
        } catch (RuntimeException e) {
            deleteTempFileAfterFailure(tempFile, e);
            throw e;
        }
    }

    private static void validateRequestOrder(List<ChapterAudioSegmentSource> segments) {
        Set<java.util.UUID> seenIds = new HashSet<>();
        Set<Integer> seenIndexes = new HashSet<>();
        int previousIndex = -1;
        for (ChapterAudioSegmentSource segment : segments) {
            if (!seenIds.add(segment.segmentId())) {
                throw new IllegalArgumentException("Duplicate segmentId in assembly request: " + segment.segmentId());
            }
            if (!seenIndexes.add(segment.segmentIndex())) {
                throw new IllegalArgumentException("Duplicate segmentIndex in assembly request: " + segment.segmentIndex());
            }
            if (segment.segmentIndex() <= previousIndex) {
                throw new IllegalArgumentException("segmentIndex values must be strictly increasing in input order");
            }
            previousIndex = segment.segmentIndex();
        }
    }

    private static ParsedWav copyPcmData(
            ChapterAudioSegmentSource source,
            InputStream inputStream,
            OutputStream outputStream
    ) throws IOException {
        byte[] riffHeader = readExact(inputStream, RIFF_HEADER_SIZE, "Missing RIFF/WAVE header.");
        if (!asciiEquals(riffHeader, 0, "RIFF") || !asciiEquals(riffHeader, 8, "WAVE")) {
            throw new IllegalArgumentException("Malformed WAV source for segment: " + source.segmentId());
        }

        PcmFormat format = null;
        while (true) {
            byte[] chunkHeader = readExactOrNull(inputStream, CHUNK_HEADER_SIZE);
            if (chunkHeader == null) {
                throw new IllegalArgumentException("WAV data chunk not found for segment: " + source.segmentId());
            }

            String chunkId = new String(chunkHeader, 0, 4, StandardCharsets.US_ASCII);
            long chunkSize = readUnsignedInt(chunkHeader, 4);

            if ("fmt ".equals(chunkId)) {
                format = readPcmFormat(source, inputStream, chunkSize);
            } else if ("data".equals(chunkId)) {
                if (format == null) {
                    throw new IllegalArgumentException("WAV fmt chunk must precede data chunk for segment: " + source.segmentId());
                }
                validateDataChunk(source, format, chunkSize);
                copyExact(inputStream, outputStream, chunkSize);
                skipPaddingByte(inputStream, chunkSize);
                return new ParsedWav(format, chunkSize);
            } else {
                skipExact(inputStream, chunkSize);
                skipPaddingByte(inputStream, chunkSize);
            }
        }
    }

    private static PcmFormat readPcmFormat(
            ChapterAudioSegmentSource source,
            InputStream inputStream,
            long chunkSize
    ) throws IOException {
        if (chunkSize < 16 || chunkSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Unsupported WAV fmt chunk for segment: " + source.segmentId());
        }

        byte[] formatBytes = readExact(inputStream, 16, "Incomplete WAV fmt chunk.");
        long remaining = chunkSize - 16;
        skipExact(inputStream, remaining);
        skipPaddingByte(inputStream, chunkSize);

        int audioFormat = readUnsignedShort(formatBytes, 0);
        int channels = readUnsignedShort(formatBytes, 2);
        int sampleRate = (int) readUnsignedInt(formatBytes, 4);
        int blockAlign = readUnsignedShort(formatBytes, 12);
        int bitsPerSample = readUnsignedShort(formatBytes, 14);

        if (audioFormat != PCM_FORMAT) {
            throw new IllegalArgumentException("Unsupported WAV encoding for segment: " + source.segmentId());
        }
        if (channels <= 0) {
            throw new IllegalArgumentException("WAV channel count must be positive for segment: " + source.segmentId());
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("WAV sample rate must be positive for segment: " + source.segmentId());
        }
        if (bitsPerSample != BITS_PER_SAMPLE_16) {
            throw new IllegalArgumentException("Unsupported WAV bit depth for segment: " + source.segmentId());
        }
        int expectedBlockAlign = channels * (BITS_PER_SAMPLE_16 / 8);
        if (blockAlign != expectedBlockAlign) {
            throw new IllegalArgumentException("Invalid WAV block alignment for segment: " + source.segmentId());
        }

        return new PcmFormat(channels, sampleRate, bitsPerSample, blockAlign);
    }

    private static void validateDataChunk(ChapterAudioSegmentSource source, PcmFormat format, long dataSize) {
        if (dataSize <= 0) {
            throw new IllegalArgumentException("WAV data chunk must not be empty for segment: " + source.segmentId());
        }
        if (dataSize % format.blockAlign() != 0) {
            throw new IllegalArgumentException("WAV data chunk is not aligned to PCM frames for segment: " + source.segmentId());
        }
    }

    private static void requireWavMimeType(ChapterAudioSegmentSource source) {
        String normalized = source.mimeType().split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("audio/wav") && !normalized.equals("audio/x-wav") && !normalized.equals("audio/wave")) {
            throw new IllegalArgumentException("Unsupported source MIME type for PCM WAV assembly: " + source.mimeType());
        }
    }

    private static long checkedAddDataBytes(long current, long additional) {
        long total = current + additional;
        if (total < current || total > UINT32_MAX || total + 36L > UINT32_MAX) {
            throw new IllegalArgumentException("Assembled WAV data size exceeds RIFF/WAV limits.");
        }
        return total;
    }

    private static long framesToMillis(long frames, int sampleRate) {
        return Math.floorDiv(frames * 1000L, sampleRate);
    }

    private Path createTempFile() {
        try {
            return tempFileFactory.create();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create chapter audio temporary file.", e);
        }
    }

    private static void writePlaceholderHeader(OutputStream outputStream) throws IOException {
        writeWavHeader(outputStream, new PcmFormat(1, 1, BITS_PER_SAMPLE_16, 2), 0L);
    }

    private static void writeFinalHeader(Path tempFile, PcmFormat format, long dataSize) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(tempFile.toFile(), "rw")) {
            raf.seek(0L);
            raf.write(wavHeaderBytes(format, dataSize));
        }
    }

    private static void writeWavHeader(OutputStream outputStream, PcmFormat format, long dataSize) throws IOException {
        outputStream.write(wavHeaderBytes(format, dataSize));
    }

    private static byte[] wavHeaderBytes(PcmFormat format, long dataSize) {
        if (dataSize > UINT32_MAX || dataSize + 36L > UINT32_MAX) {
            throw new IllegalArgumentException("WAV data size exceeds RIFF/WAV limits.");
        }

        byte[] header = new byte[44];
        writeAscii(header, 0, "RIFF");
        writeLittleEndianInt(header, 4, dataSize + 36L);
        writeAscii(header, 8, "WAVE");
        writeAscii(header, 12, "fmt ");
        writeLittleEndianInt(header, 16, 16L);
        writeLittleEndianShort(header, 20, PCM_FORMAT);
        writeLittleEndianShort(header, 22, format.channels());
        writeLittleEndianInt(header, 24, format.sampleRate());
        writeLittleEndianInt(header, 28, (long) format.sampleRate() * format.blockAlign());
        writeLittleEndianShort(header, 32, format.blockAlign());
        writeLittleEndianShort(header, 34, format.bitsPerSample());
        writeAscii(header, 36, "data");
        writeLittleEndianInt(header, 40, dataSize);
        return header;
    }

    private static void copyExact(InputStream inputStream, OutputStream outputStream, long byteCount) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long remaining = byteCount;
        while (remaining > 0) {
            int read = inputStream.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new EOFException("Unexpected end of WAV data chunk.");
            }
            outputStream.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static byte[] readExact(InputStream inputStream, int length, String errorMessage) throws IOException {
        byte[] bytes = readExactOrNull(inputStream, length);
        if (bytes == null) {
            throw new IllegalArgumentException(errorMessage);
        }
        return bytes;
    }

    private static byte[] readExactOrNull(InputStream inputStream, int length) throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = inputStream.read(bytes, offset, length - offset);
            if (read < 0) {
                if (offset == 0) {
                    return null;
                }
                throw new EOFException("Unexpected end of WAV stream.");
            }
            offset += read;
        }
        return bytes;
    }

    private static void skipExact(InputStream inputStream, long byteCount) throws IOException {
        long remaining = byteCount;
        while (remaining > 0) {
            long skipped = inputStream.skip(remaining);
            if (skipped <= 0) {
                if (inputStream.read() < 0) {
                    throw new EOFException("Unexpected end of WAV stream while skipping chunk.");
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private static void skipPaddingByte(InputStream inputStream, long chunkSize) throws IOException {
        if (chunkSize % 2L != 0L && inputStream.read() < 0) {
            throw new EOFException("Unexpected end of WAV stream while skipping chunk padding.");
        }
    }

    private static boolean asciiEquals(byte[] bytes, int offset, String expected) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.US_ASCII);
        if (offset < 0 || offset + expectedBytes.length > bytes.length) {
            return false;
        }
        for (int i = 0; i < expectedBytes.length; i++) {
            if (bytes[offset + i] != expectedBytes[i]) {
                return false;
            }
        }
        return true;
    }

    private static int readUnsignedShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static long readUnsignedInt(byte[] bytes, int offset) {
        return ((long) bytes[offset] & 0xFF) |
                (((long) bytes[offset + 1] & 0xFF) << 8) |
                (((long) bytes[offset + 2] & 0xFF) << 16) |
                (((long) bytes[offset + 3] & 0xFF) << 24);
    }

    private static void writeAscii(byte[] bytes, int offset, String value) {
        byte[] valueBytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(valueBytes, 0, bytes, offset, valueBytes.length);
    }

    private static void writeLittleEndianShort(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value & 0xFF);
        bytes[offset + 1] = (byte) ((value >>> 8) & 0xFF);
    }

    private static void writeLittleEndianInt(byte[] bytes, int offset, long value) {
        bytes[offset] = (byte) (value & 0xFF);
        bytes[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        bytes[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        bytes[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    private void deleteTempFileAfterFailure(Path tempFile, RuntimeException primaryFailure) {
        try {
            tempFileDeleter.deleteIfExists(tempFile);
        } catch (IOException cleanupFailure) {
            primaryFailure.addSuppressed(cleanupFailure);
        }
    }

    private record PcmFormat(int channels, int sampleRate, int bitsPerSample, int blockAlign) {
    }

    private record ParsedWav(PcmFormat format, long dataSizeBytes) {

        long frameCount() {
            return dataSizeBytes / format.blockAlign();
        }
    }
}
