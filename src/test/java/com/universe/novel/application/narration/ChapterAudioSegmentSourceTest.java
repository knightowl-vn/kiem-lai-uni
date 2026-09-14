package com.universe.novel.application.narration;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChapterAudioSegmentSourceTest {

    private static final UUID SEGMENT_ID = UUID.fromString("90000000-0000-0000-0000-000000000001");
    private static final ChapterAudioSegmentBinarySource BINARY_SOURCE = () -> new ByteArrayInputStream(new byte[]{1, 2, 3});

    @Test
    void timingNullNullAcceptedForCompatibility() {
        ChapterAudioSegmentSource source4Arg = new ChapterAudioSegmentSource(
                SEGMENT_ID, 0, "audio/mpeg", BINARY_SOURCE
        );
        assertThat(source4Arg.encodedContributionSamples()).isNull();
        assertThat(source4Arg.encodedSampleRateHz()).isNull();

        ChapterAudioSegmentSource source6Arg = new ChapterAudioSegmentSource(
                SEGMENT_ID, 0, "audio/mpeg", BINARY_SOURCE, null, null
        );
        assertThat(source6Arg.encodedContributionSamples()).isNull();
        assertThat(source6Arg.encodedSampleRateHz()).isNull();
    }

    @Test
    void positiveTimingPairAccepted() {
        ChapterAudioSegmentSource source = new ChapterAudioSegmentSource(
                SEGMENT_ID, 0, "audio/mpeg", BINARY_SOURCE, 2400L, 48000
        );
        assertThat(source.encodedContributionSamples()).isEqualTo(2400L);
        assertThat(source.encodedSampleRateHz()).isEqualTo(48000);
    }

    @Test
    void partialPairRejected() {
        assertThatThrownBy(() -> new ChapterAudioSegmentSource(
                SEGMENT_ID, 0, "audio/mpeg", BINARY_SOURCE, 2400L, null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("both be null or both be non-null");

        assertThatThrownBy(() -> new ChapterAudioSegmentSource(
                SEGMENT_ID, 0, "audio/mpeg", BINARY_SOURCE, null, 48000
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("both be null or both be non-null");
    }

    @Test
    void nonPositiveTimingRejected() {
        assertThatThrownBy(() -> new ChapterAudioSegmentSource(
                SEGMENT_ID, 0, "audio/mpeg", BINARY_SOURCE, 0L, 48000
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("encodedContributionSamples must be > 0");

        assertThatThrownBy(() -> new ChapterAudioSegmentSource(
                SEGMENT_ID, 0, "audio/mpeg", BINARY_SOURCE, -100L, 48000
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("encodedContributionSamples must be > 0");

        assertThatThrownBy(() -> new ChapterAudioSegmentSource(
                SEGMENT_ID, 0, "audio/mpeg", BINARY_SOURCE, 2400L, 0
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("encodedSampleRateHz must be > 0");

        assertThatThrownBy(() -> new ChapterAudioSegmentSource(
                SEGMENT_ID, 0, "audio/mpeg", BINARY_SOURCE, 2400L, -48000
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("encodedSampleRateHz must be > 0");
    }
}
