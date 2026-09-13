package com.universe.novel.application.narration;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * Narration-owned PCM WAV boundary padding for browser speech-safe segment handoff.
 */
final class NarrationWavBoundaryNormalizer {

    static final int LEADING_SILENCE_MILLIS = 100;
    static final int TRAILING_SILENCE_MILLIS = 150;
    static final int NEAR_SILENCE_16_BIT_THRESHOLD = 512;

    private static final int RIFF_HEADER_SIZE = 12;
    private static final int CHUNK_HEADER_SIZE = 8;
    private static final int PCM_FORMAT = 1;
    private static final int BITS_PER_SAMPLE_16 = 16;

    private NarrationWavBoundaryNormalizer() {
    }

    static byte[] normalize(byte[] audioBytes, String mediaType) {
        if (audioBytes == null || audioBytes.length < RIFF_HEADER_SIZE || !isWavMediaType(mediaType)) {
            return audioBytes;
        }

        WavInfo wav = parse(audioBytes);
        if (wav == null || !wav.isSupportedPcm16()) {
            return audioBytes;
        }

        int leadingFrames = countLeadingSilentFrames(audioBytes, wav);
        int trailingFrames = countTrailingSilentFrames(audioBytes, wav);
        int requiredLeadingFrames = millisToFrames(LEADING_SILENCE_MILLIS, wav.sampleRate);
        int requiredTrailingFrames = millisToFrames(TRAILING_SILENCE_MILLIS, wav.sampleRate);
        int missingLeadingFrames = Math.max(0, requiredLeadingFrames - leadingFrames);
        int missingTrailingFrames = Math.max(0, requiredTrailingFrames - trailingFrames);

        if (missingLeadingFrames == 0 && missingTrailingFrames == 0) {
            return audioBytes;
        }

        int missingLeadingBytes = missingLeadingFrames * wav.blockAlign;
        int missingTrailingBytes = missingTrailingFrames * wav.blockAlign;
        long newDataSizeLong = (long) wav.dataSize + missingLeadingBytes + missingTrailingBytes;
        long newFileSizeLong = (long) audioBytes.length + missingLeadingBytes + missingTrailingBytes;
        if (newDataSizeLong > 0xFFFFFFFFL || newFileSizeLong - 8 > 0xFFFFFFFFL || newFileSizeLong > Integer.MAX_VALUE) {
            return audioBytes;
        }

        byte[] normalized = new byte[(int) newFileSizeLong];
        System.arraycopy(audioBytes, 0, normalized, 0, wav.dataStart);
        System.arraycopy(audioBytes, wav.dataStart, normalized, wav.dataStart + missingLeadingBytes, wav.dataSize);

        int sourceAfterData = wav.dataStart + wav.dataSize;
        int targetAfterData = wav.dataStart + missingLeadingBytes + wav.dataSize + missingTrailingBytes;
        if (sourceAfterData < audioBytes.length) {
            System.arraycopy(audioBytes, sourceAfterData, normalized, targetAfterData, audioBytes.length - sourceAfterData);
        }

        writeLittleEndianInt(normalized, 4, normalized.length - 8);
        writeLittleEndianInt(normalized, wav.dataSizeOffset, (int) newDataSizeLong);
        return normalized;
    }

    private static boolean isWavMediaType(String mediaType) {
        if (mediaType == null) {
            return false;
        }
        String normalized = mediaType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return normalized.equals("audio/wav") || normalized.equals("audio/x-wav") || normalized.equals("audio/wave");
    }

    private static WavInfo parse(byte[] bytes) {
        if (!asciiEquals(bytes, 0, "RIFF") || !asciiEquals(bytes, 8, "WAVE")) {
            return null;
        }

        Integer audioFormat = null;
        Integer channels = null;
        Integer sampleRate = null;
        Integer blockAlign = null;
        Integer bitsPerSample = null;
        int dataStart = -1;
        int dataSize = -1;
        int dataSizeOffset = -1;

        int offset = RIFF_HEADER_SIZE;
        while (offset + CHUNK_HEADER_SIZE <= bytes.length) {
            long chunkSizeLong = readUnsignedInt(bytes, offset + 4);
            if (chunkSizeLong > Integer.MAX_VALUE) {
                return null;
            }
            int chunkSize = (int) chunkSizeLong;
            int chunkDataStart = offset + CHUNK_HEADER_SIZE;
            int chunkDataEnd = chunkDataStart + chunkSize;
            if (chunkDataEnd < chunkDataStart || chunkDataEnd > bytes.length) {
                return null;
            }

            String chunkId = new String(bytes, offset, 4, StandardCharsets.US_ASCII);
            if ("fmt ".equals(chunkId)) {
                if (chunkSize < 16) {
                    return null;
                }
                audioFormat = readUnsignedShort(bytes, chunkDataStart);
                channels = readUnsignedShort(bytes, chunkDataStart + 2);
                sampleRate = (int) readUnsignedInt(bytes, chunkDataStart + 4);
                blockAlign = readUnsignedShort(bytes, chunkDataStart + 12);
                bitsPerSample = readUnsignedShort(bytes, chunkDataStart + 14);
            } else if ("data".equals(chunkId)) {
                dataStart = chunkDataStart;
                dataSize = chunkSize;
                dataSizeOffset = offset + 4;
                break;
            }

            int padding = chunkSize % 2;
            offset = chunkDataEnd + padding;
            if (offset < chunkDataEnd || offset > bytes.length) {
                return null;
            }
        }

        if (audioFormat == null || channels == null || sampleRate == null || blockAlign == null ||
                bitsPerSample == null || dataStart < 0 || dataSize < 0) {
            return null;
        }
        return new WavInfo(audioFormat, channels, sampleRate, blockAlign, bitsPerSample, dataStart, dataSize, dataSizeOffset);
    }

    private static int countLeadingSilentFrames(byte[] bytes, WavInfo wav) {
        int totalFrames = wav.dataSize / wav.blockAlign;
        for (int frame = 0; frame < totalFrames; frame++) {
            if (!isSilentFrame(bytes, wav.dataStart + frame * wav.blockAlign, wav.channels)) {
                return frame;
            }
        }
        return totalFrames;
    }

    private static int countTrailingSilentFrames(byte[] bytes, WavInfo wav) {
        int totalFrames = wav.dataSize / wav.blockAlign;
        for (int frame = totalFrames - 1; frame >= 0; frame--) {
            if (!isSilentFrame(bytes, wav.dataStart + frame * wav.blockAlign, wav.channels)) {
                return totalFrames - 1 - frame;
            }
        }
        return totalFrames;
    }

    private static boolean isSilentFrame(byte[] bytes, int frameOffset, int channels) {
        for (int channel = 0; channel < channels; channel++) {
            short sample = readLittleEndianShort(bytes, frameOffset + channel * 2);
            if (Math.abs((int) sample) > NEAR_SILENCE_16_BIT_THRESHOLD) {
                return false;
            }
        }
        return true;
    }

    private static int millisToFrames(int millis, int sampleRate) {
        return (int) Math.ceil(sampleRate * (millis / 1000.0));
    }

    private static boolean asciiEquals(byte[] bytes, int offset, String expected) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.US_ASCII);
        if (offset < 0 || offset + expectedBytes.length > bytes.length) {
            return false;
        }
        return Arrays.equals(expectedBytes, Arrays.copyOfRange(bytes, offset, offset + expectedBytes.length));
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

    private static short readLittleEndianShort(byte[] bytes, int offset) {
        return (short) readUnsignedShort(bytes, offset);
    }

    private static void writeLittleEndianInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value & 0xFF);
        bytes[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        bytes[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        bytes[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    private record WavInfo(
            int audioFormat,
            int channels,
            int sampleRate,
            int blockAlign,
            int bitsPerSample,
            int dataStart,
            int dataSize,
            int dataSizeOffset
    ) {
        boolean isSupportedPcm16() {
            return audioFormat == PCM_FORMAT &&
                    channels > 0 &&
                    sampleRate > 0 &&
                    bitsPerSample == BITS_PER_SAMPLE_16 &&
                    blockAlign == channels * (BITS_PER_SAMPLE_16 / 8) &&
                    dataSize >= 0 &&
                    dataSize % blockAlign == 0;
        }
    }
}
