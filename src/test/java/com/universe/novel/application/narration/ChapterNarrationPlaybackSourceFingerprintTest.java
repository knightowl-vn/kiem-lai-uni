package com.universe.novel.application.narration;

import com.universe.media.contracts.dto.MediaAssetVersionSnapshotDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ChapterNarrationPlaybackSourceFingerprintTest {
    private final UUID chapterId = UUID.randomUUID();
    private final UUID voiceId = UUID.randomUUID();
    private final UUID segmentId = UUID.randomUUID();
    private final UUID audioId = UUID.randomUUID();
    private final UUID mediaId = UUID.randomUUID();

    @Test
    void assignmentIdentityAndVersionArePartOfSourceIdentity() {
        var original = segment(audioId, 1L, source(1, "a".repeat(64), "source.mp3"));
        String fingerprint = fingerprint(List.of(original));
        assertThat(fingerprint(List.of(segment(UUID.randomUUID(), 1L, original.sourceMediaVersion())))).isNotEqualTo(fingerprint);
        assertThat(fingerprint(List.of(segment(audioId, 2L, original.sourceMediaVersion())))).isNotEqualTo(fingerprint);
    }

    @Test
    void exactMediaVersionAndHashMatterButDisplayFilenameDoesNot() {
        String fingerprint = fingerprint(List.of(segment(audioId, 1L, source(1, "a".repeat(64), "source.mp3"))));
        assertThat(fingerprint(List.of(segment(audioId, 1L, source(2, "a".repeat(64), "source.mp3"))))).isNotEqualTo(fingerprint);
        assertThat(fingerprint(List.of(segment(audioId, 1L, source(1, "b".repeat(64), "source.mp3"))))).isNotEqualTo(fingerprint);
        assertThat(fingerprint(List.of(segment(audioId, 1L, source(1, "a".repeat(64), "renamed.mp3"))))).isEqualTo(fingerprint);
        assertThat(fingerprint).matches("[0-9a-f]{64}");
    }

    @Test
    void segmentOrderAndContentRemainExact() {
        var first = segment(audioId, 1L, source(1, "a".repeat(64), "source.mp3"));
        var second = new ChapterNarrationPlaybackSegmentSnapshot(UUID.randomUUID(), 1, "d".repeat(64),
                UUID.randomUUID(), 1L, mediaId, 2L, first.sourceMediaVersion());
        assertThat(fingerprint(List.of(first, second))).isNotEqualTo(fingerprint(List.of(second, first)));
        var changedContent = new ChapterNarrationPlaybackSegmentSnapshot(segmentId, 0, "e".repeat(64),
                audioId, 1L, mediaId, 2L, first.sourceMediaVersion());
        assertThat(fingerprint(List.of(first))).isNotEqualTo(fingerprint(List.of(changedContent)));
    }

    @Test
    void changingEncodedContributionSamplesChangesFingerprint() {
        var base = new ChapterNarrationPlaybackSegmentSnapshot(
                segmentId, 0, "b".repeat(64), audioId, 1L, mediaId, 2L,
                source(1, "a".repeat(64), "source.mp3"), 2400L, 48000
        );
        var changedSamples = new ChapterNarrationPlaybackSegmentSnapshot(
                segmentId, 0, "b".repeat(64), audioId, 1L, mediaId, 2L,
                source(1, "a".repeat(64), "source.mp3"), 4800L, 48000
        );
        var nullSamples = new ChapterNarrationPlaybackSegmentSnapshot(
                segmentId, 0, "b".repeat(64), audioId, 1L, mediaId, 2L,
                source(1, "a".repeat(64), "source.mp3"), null, 48000
        );

        String baseFingerprint = fingerprint(List.of(base));
        assertThat(fingerprint(List.of(changedSamples))).isNotEqualTo(baseFingerprint);
        assertThat(fingerprint(List.of(nullSamples))).isNotEqualTo(baseFingerprint);
    }

    @Test
    void changingEncodedSampleRateHzChangesFingerprint() {
        var base = new ChapterNarrationPlaybackSegmentSnapshot(
                segmentId, 0, "b".repeat(64), audioId, 1L, mediaId, 2L,
                source(1, "a".repeat(64), "source.mp3"), 2400L, 48000
        );
        var changedRate = new ChapterNarrationPlaybackSegmentSnapshot(
                segmentId, 0, "b".repeat(64), audioId, 1L, mediaId, 2L,
                source(1, "a".repeat(64), "source.mp3"), 2400L, 44100
        );
        var nullRate = new ChapterNarrationPlaybackSegmentSnapshot(
                segmentId, 0, "b".repeat(64), audioId, 1L, mediaId, 2L,
                source(1, "a".repeat(64), "source.mp3"), 2400L, null
        );

        String baseFingerprint = fingerprint(List.of(base));
        assertThat(fingerprint(List.of(changedRate))).isNotEqualTo(baseFingerprint);
        assertThat(fingerprint(List.of(nullRate))).isNotEqualTo(baseFingerprint);
    }

    @Test
    void canonicalSourcePrefixIsV2Internally() {
        assertThat(ChapterNarrationPlaybackSourceFingerprint.CANONICAL_PREFIX)
                .isEqualTo("chapter-playback-source-v2");

        var snapshot = new ChapterNarrationPlaybackBuildSnapshot(
                chapterId, voiceId, 1L, 2L, "c".repeat(64), List.of()
        );
        String payload = ChapterNarrationPlaybackSourceFingerprint.canonicalPayload(snapshot);
        assertThat(payload).startsWith("26:chapter-playback-source-v2");
    }

    @Test
    void changingSourceContentVersionChangesFingerprint() {
        var segment = segment(audioId, 1L, source(1, "a".repeat(64), "source.mp3"));
        var baseSnapshot = new ChapterNarrationPlaybackBuildSnapshot(
                chapterId, voiceId, 1L, 2L, "c".repeat(64), List.of(segment));
        var changedSnapshot = new ChapterNarrationPlaybackBuildSnapshot(
                chapterId, voiceId, 2L, 2L, "c".repeat(64), List.of(segment));

        assertThat(ChapterNarrationPlaybackSourceFingerprint.compute(changedSnapshot))
                .isNotEqualTo(ChapterNarrationPlaybackSourceFingerprint.compute(baseSnapshot));
    }

    @Test
    void changingSynthesisRevisionChangesFingerprint() {
        var segment = segment(audioId, 1L, source(1, "a".repeat(64), "source.mp3"));
        var baseSnapshot = new ChapterNarrationPlaybackBuildSnapshot(
                chapterId, voiceId, 1L, 2L, "c".repeat(64), List.of(segment));
        var changedSnapshot = new ChapterNarrationPlaybackBuildSnapshot(
                chapterId, voiceId, 1L, 3L, "c".repeat(64), List.of(segment));

        assertThat(ChapterNarrationPlaybackSourceFingerprint.compute(changedSnapshot))
                .isNotEqualTo(ChapterNarrationPlaybackSourceFingerprint.compute(baseSnapshot));
    }

    @Test
    void changingManifestHashChangesFingerprint() {
        var segment = segment(audioId, 1L, source(1, "a".repeat(64), "source.mp3"));
        var baseSnapshot = new ChapterNarrationPlaybackBuildSnapshot(
                chapterId, voiceId, 1L, 2L, "c".repeat(64), List.of(segment));
        var changedSnapshot = new ChapterNarrationPlaybackBuildSnapshot(
                chapterId, voiceId, 1L, 2L, "d".repeat(64), List.of(segment));

        assertThat(ChapterNarrationPlaybackSourceFingerprint.compute(changedSnapshot))
                .isNotEqualTo(ChapterNarrationPlaybackSourceFingerprint.compute(baseSnapshot));
    }

    private String fingerprint(List<ChapterNarrationPlaybackSegmentSnapshot> segments) {
        return ChapterNarrationPlaybackSourceFingerprint.compute(new ChapterNarrationPlaybackBuildSnapshot(
                chapterId, voiceId, 1L, 2L, "c".repeat(64), segments));
    }

    private ChapterNarrationPlaybackSegmentSnapshot segment(UUID assignmentId, Long assignmentVersion, MediaAssetVersionSnapshotDTO source) {
        return new ChapterNarrationPlaybackSegmentSnapshot(segmentId, 0, "b".repeat(64), assignmentId,
                assignmentVersion, mediaId, 2L, source);
    }

    private MediaAssetVersionSnapshotDTO source(int version, String hash, String filename) {
        return new MediaAssetVersionSnapshotDTO(mediaId, version, hash, "audio/mpeg", 100L, filename);
    }
}
