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
        var original = segment(audioId, 1L, source(1, "a".repeat(64), "source.wav"));
        String fingerprint = fingerprint(List.of(original));
        assertThat(fingerprint(List.of(segment(UUID.randomUUID(), 1L, original.sourceMediaVersion())))).isNotEqualTo(fingerprint);
        assertThat(fingerprint(List.of(segment(audioId, 2L, original.sourceMediaVersion())))).isNotEqualTo(fingerprint);
    }

    @Test
    void exactMediaVersionAndHashMatterButDisplayFilenameDoesNot() {
        String fingerprint = fingerprint(List.of(segment(audioId, 1L, source(1, "a".repeat(64), "source.wav"))));
        assertThat(fingerprint(List.of(segment(audioId, 1L, source(2, "a".repeat(64), "source.wav"))))).isNotEqualTo(fingerprint);
        assertThat(fingerprint(List.of(segment(audioId, 1L, source(1, "b".repeat(64), "source.wav"))))).isNotEqualTo(fingerprint);
        assertThat(fingerprint(List.of(segment(audioId, 1L, source(1, "a".repeat(64), "renamed.wav"))))).isEqualTo(fingerprint);
        assertThat(fingerprint).matches("[0-9a-f]{64}");
    }

    @Test
    void segmentOrderAndContentRemainExact() {
        var first = segment(audioId, 1L, source(1, "a".repeat(64), "source.wav"));
        var second = new ChapterNarrationPlaybackSegmentSnapshot(UUID.randomUUID(), 1, "d".repeat(64),
                UUID.randomUUID(), 1L, mediaId, 2L, first.sourceMediaVersion());
        assertThat(fingerprint(List.of(first, second))).isNotEqualTo(fingerprint(List.of(second, first)));
        var changedContent = new ChapterNarrationPlaybackSegmentSnapshot(segmentId, 0, "e".repeat(64),
                audioId, 1L, mediaId, 2L, first.sourceMediaVersion());
        assertThat(fingerprint(List.of(first))).isNotEqualTo(fingerprint(List.of(changedContent)));
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
        return new MediaAssetVersionSnapshotDTO(mediaId, version, hash, "audio/wav", 100L, filename);
    }
}
