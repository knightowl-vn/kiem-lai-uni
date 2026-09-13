package com.universe.novel.application.narration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NarrationWavBoundaryNormalizer Unit Tests")
class NarrationWavBoundaryNormalizerTest {

    private static final int SAMPLE_RATE = 48_000;

    @Test
    @DisplayName("Valid mono 48 kHz 16-bit PCM WAV remains valid and receives missing boundary silence")
    void validMonoPcmWavReceivesMissingBoundarySilence() {
        short[] speech = new short[]{1200, -1400, 1600, -1800, 2000};
        byte[] source = wavWithSilence(10, speech, 20);

        byte[] normalized = NarrationWavBoundaryNormalizer.normalize(source, "audio/wav");

        assertThat(normalized).isNotSameAs(source);
        assertThat(ascii(normalized, 0, 4)).isEqualTo("RIFF");
        assertThat(readInt(normalized, 4)).isEqualTo(normalized.length - 8);
        assertThat(ascii(normalized, 8, 4)).isEqualTo("WAVE");
        assertThat(readShort(normalized, 20)).isEqualTo((short) 1);
        assertThat(readShort(normalized, 22)).isEqualTo((short) 1);
        assertThat(readInt(normalized, 24)).isEqualTo(SAMPLE_RATE);
        assertThat(readShort(normalized, 34)).isEqualTo((short) 16);
        assertThat(readInt(normalized, 40)).isEqualTo(normalized.length - 44);
        assertThat(countLeadingSilentFrames(normalized)).isGreaterThanOrEqualTo(4_800);
        assertThat(countTrailingSilentFrames(normalized)).isGreaterThanOrEqualTo(7_200);
        assertThat(loudSamples(normalized)).containsExactly(1200, -1400, 1600, -1800, 2000);
    }

    @Test
    @DisplayName("Existing sufficient leading and trailing silence is not duplicated")
    void sufficientBoundarySilenceReturnsOriginalBytes() {
        byte[] source = wavWithSilence(4_800, new short[]{1100, -1200, 1300}, 7_200);

        byte[] normalized = NarrationWavBoundaryNormalizer.normalize(source, "audio/wav; charset=binary");

        assertThat(normalized).isSameAs(source);
    }

    @Test
    @DisplayName("Near-silence is counted toward policy and only missing margin is added")
    void nearSilenceCountsTowardPolicy() {
        short[] quietLead = repeated((short) 400, 2_400);
        short[] speech = new short[]{900, -950};
        short[] quietTrail = repeated((short) -400, 3_600);
        byte[] source = wav(concat(quietLead, speech, quietTrail));

        byte[] normalized = NarrationWavBoundaryNormalizer.normalize(source, "audio/x-wav");

        assertThat(countLeadingSilentFrames(normalized)).isEqualTo(4_800);
        assertThat(countTrailingSilentFrames(normalized)).isEqualTo(7_200);
        assertThat(loudSamples(normalized)).containsExactly(900, -950);
        assertThat(readInt(normalized, 4)).isEqualTo(normalized.length - 8);
        assertThat(readInt(normalized, 40)).isEqualTo(normalized.length - 44);
    }

    @Test
    @DisplayName("Unsupported or malformed audio is returned unchanged")
    void unsupportedInputReturnsOriginalBytes() {
        byte[] notWav = new byte[]{1, 2, 3, 4, 5};
        byte[] wrongMediaType = wavWithSilence(0, new short[]{1000}, 0);
        byte[] pcmEightBit = eightBitPcmWav();

        assertThat(NarrationWavBoundaryNormalizer.normalize(notWav, "audio/wav")).isSameAs(notWav);
        assertThat(NarrationWavBoundaryNormalizer.normalize(wrongMediaType, "audio/mpeg")).isSameAs(wrongMediaType);
        assertThat(NarrationWavBoundaryNormalizer.normalize(pcmEightBit, "audio/wav")).isSameAs(pcmEightBit);
    }

    private static byte[] wavWithSilence(int leadingFrames, short[] speech, int trailingFrames) {
        return wav(concat(new short[leadingFrames], speech, new short[trailingFrames]));
    }

    private static byte[] wav(short[] samples) {
        int dataSize = samples.length * 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + dataSize);
        try {
            out.write(new byte[]{'R', 'I', 'F', 'F'});
            writeInt(out, 36 + dataSize);
            out.write(new byte[]{'W', 'A', 'V', 'E'});
            out.write(new byte[]{'f', 'm', 't', ' '});
            writeInt(out, 16);
            writeShort(out, 1);
            writeShort(out, 1);
            writeInt(out, SAMPLE_RATE);
            writeInt(out, SAMPLE_RATE * 2);
            writeShort(out, 2);
            writeShort(out, 16);
            out.write(new byte[]{'d', 'a', 't', 'a'});
            writeInt(out, dataSize);
            for (short sample : samples) {
                writeShort(out, sample);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    private static byte[] eightBitPcmWav() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(45);
        try {
            out.write(new byte[]{'R', 'I', 'F', 'F'});
            writeInt(out, 37);
            out.write(new byte[]{'W', 'A', 'V', 'E'});
            out.write(new byte[]{'f', 'm', 't', ' '});
            writeInt(out, 16);
            writeShort(out, 1);
            writeShort(out, 1);
            writeInt(out, SAMPLE_RATE);
            writeInt(out, SAMPLE_RATE);
            writeShort(out, 1);
            writeShort(out, 8);
            out.write(new byte[]{'d', 'a', 't', 'a'});
            writeInt(out, 1);
            out.write(128);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    private static int countLeadingSilentFrames(byte[] wav) {
        int dataSize = readInt(wav, 40);
        int totalFrames = dataSize / 2;
        for (int frame = 0; frame < totalFrames; frame++) {
            if (Math.abs(readShort(wav, 44 + frame * 2)) > NarrationWavBoundaryNormalizer.NEAR_SILENCE_16_BIT_THRESHOLD) {
                return frame;
            }
        }
        return totalFrames;
    }

    private static int countTrailingSilentFrames(byte[] wav) {
        int dataSize = readInt(wav, 40);
        int totalFrames = dataSize / 2;
        for (int frame = totalFrames - 1; frame >= 0; frame--) {
            if (Math.abs(readShort(wav, 44 + frame * 2)) > NarrationWavBoundaryNormalizer.NEAR_SILENCE_16_BIT_THRESHOLD) {
                return totalFrames - 1 - frame;
            }
        }
        return totalFrames;
    }

    private static List<Integer> loudSamples(byte[] wav) {
        int dataSize = readInt(wav, 40);
        List<Integer> samples = new ArrayList<>();
        for (int offset = 44; offset < 44 + dataSize; offset += 2) {
            short sample = readShort(wav, offset);
            if (Math.abs(sample) > NarrationWavBoundaryNormalizer.NEAR_SILENCE_16_BIT_THRESHOLD) {
                samples.add((int) sample);
            }
        }
        return samples;
    }

    private static short[] repeated(short value, int count) {
        short[] values = new short[count];
        for (int i = 0; i < count; i++) {
            values[i] = value;
        }
        return values;
    }

    private static short[] concat(short[]... arrays) {
        int total = 0;
        for (short[] array : arrays) {
            total += array.length;
        }
        short[] result = new short[total];
        int offset = 0;
        for (short[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }

    private static String ascii(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length);
    }

    private static int readInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) |
                ((bytes[offset + 1] & 0xFF) << 8) |
                ((bytes[offset + 2] & 0xFF) << 16) |
                ((bytes[offset + 3] & 0xFF) << 24);
    }

    private static short readShort(byte[] bytes, int offset) {
        return (short) ((bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8));
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
}
