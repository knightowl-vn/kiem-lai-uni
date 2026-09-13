package com.universe.novel.domain.narration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("NarrationManifestHasher Domain Tests")
class NarrationManifestHasherTest {

    @Test
    @DisplayName("Should return empty manifest hash for empty list of segments")
    void shouldReturnEmptyManifestHashForEmptyList() {
        String hash = NarrationManifestHasher.computeManifestHash(List.of());

        assertThat(hash).isEqualTo(NarrationManifestHasher.EMPTY_MANIFEST_HASH);
        assertThat(hash).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    @DisplayName("Should compute canonical hash for a single segment")
    void shouldComputeCanonicalHashForSingleSegment() throws Exception {
        NarrationTextSegment segment0 = NarrationTextSegment.of(0, "Lạc Phách sơn phong quang vô hạn.");

        String actualHash = NarrationManifestHasher.computeManifestHash(List.of(segment0));

        String expectedPayload = "0:" + segment0.contentHash() + "\n";
        String expectedHash = sha256(expectedPayload);

        assertThat(actualHash).isEqualTo(expectedHash);
        assertThat(actualHash).matches("^[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("Should compute canonical hash for multiple contiguous segments")
    void shouldComputeCanonicalHashForMultipleSegments() throws Exception {
        NarrationTextSegment s0 = NarrationTextSegment.of(0, "Đoạn thứ nhất.");
        NarrationTextSegment s1 = NarrationTextSegment.of(1, "Đoạn thứ hai.");
        NarrationTextSegment s2 = NarrationTextSegment.of(2, "Đoạn thứ ba.");

        String actualHash = NarrationManifestHasher.computeManifestHash(List.of(s0, s1, s2));

        String expectedPayload = "0:" + s0.contentHash() + "\n" +
                "1:" + s1.contentHash() + "\n" +
                "2:" + s2.contentHash() + "\n";
        String expectedHash = sha256(expectedPayload);

        assertThat(actualHash).isEqualTo(expectedHash);
    }

    @Test
    @DisplayName("Should reject null list")
    void shouldRejectNullList() {
        assertThatThrownBy(() -> NarrationManifestHasher.computeManifestHash(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("segments list must not be null");
    }

    @Test
    @DisplayName("Should reject list containing null segment")
    void shouldRejectListContainingNull() {
        NarrationTextSegment s0 = NarrationTextSegment.of(0, "Đoạn một.");
        List<NarrationTextSegment> list = java.util.Arrays.asList(s0, null);

        assertThatThrownBy(() -> NarrationManifestHasher.computeManifestHash(list))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("must not be null");
    }

    @Test
    @DisplayName("Should reject non-zero starting segment index")
    void shouldRejectNonZeroStartingIndex() {
        NarrationTextSegment s1 = NarrationTextSegment.of(1, "Đoạn một với chỉ số sai.");

        assertThatThrownBy(() -> NarrationManifestHasher.computeManifestHash(List.of(s1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected strictly contiguous 0-based indices");
    }

    @Test
    @DisplayName("Should reject gapped segment indices")
    void shouldRejectGappedIndices() {
        NarrationTextSegment s0 = NarrationTextSegment.of(0, "Đoạn 0.");
        NarrationTextSegment s2 = NarrationTextSegment.of(2, "Đoạn 2 thay vì 1.");

        assertThatThrownBy(() -> NarrationManifestHasher.computeManifestHash(List.of(s0, s2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected strictly contiguous 0-based indices");
    }

    @Test
    @DisplayName("Should reject out-of-order segment indices")
    void shouldRejectOutOfOrderIndices() {
        NarrationTextSegment s0 = NarrationTextSegment.of(0, "Đoạn 0.");
        NarrationTextSegment s1 = NarrationTextSegment.of(1, "Đoạn 1.");

        assertThatThrownBy(() -> NarrationManifestHasher.computeManifestHash(List.of(s1, s0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected strictly contiguous 0-based indices");
    }

    @Test
    @DisplayName("Should produce different hashes when segment text changes")
    void shouldProduceDifferentHashesWhenTextChanges() {
        NarrationTextSegment s0A = NarrationTextSegment.of(0, "Nội dung bản A.");
        NarrationTextSegment s0B = NarrationTextSegment.of(0, "Nội dung bản B.");

        String hashA = NarrationManifestHasher.computeManifestHash(List.of(s0A));
        String hashB = NarrationManifestHasher.computeManifestHash(List.of(s0B));

        assertThat(hashA).isNotEqualTo(hashB);
    }

    @Test
    @DisplayName("Should produce different manifest hashes when segment order changes")
    void shouldProduceDifferentHashesWhenSegmentOrderChanges() {
        String textA = "Văn bản đoạn A.";
        String textB = "Văn bản đoạn B.";

        // Manifest A: 0 -> A, 1 -> B
        List<NarrationTextSegment> manifestA = List.of(
                NarrationTextSegment.of(0, textA),
                NarrationTextSegment.of(1, textB)
        );

        // Manifest B: 0 -> B, 1 -> A
        List<NarrationTextSegment> manifestB = List.of(
                NarrationTextSegment.of(0, textB),
                NarrationTextSegment.of(1, textA)
        );

        String hashA = NarrationManifestHasher.computeManifestHash(manifestA);
        String hashB = NarrationManifestHasher.computeManifestHash(manifestB);

        assertThat(hashA).isNotEqualTo(hashB);
    }

    @Test
    @DisplayName("Should produce different manifest hashes when segmentation boundaries differ")
    void shouldProduceDifferentHashesWhenSegmentationBoundariesDiffer() {
        String text1 = "Câu thứ nhất trong đoạn văn.";
        String text2 = "Câu thứ hai tiếp nối câu thứ nhất.";

        // Manifest A: one segment containing combined text
        List<NarrationTextSegment> manifestSingle = List.of(
                NarrationTextSegment.of(0, text1 + " " + text2)
        );

        // Manifest B: two valid contiguous segments
        List<NarrationTextSegment> manifestTwoSegments = List.of(
                NarrationTextSegment.of(0, text1),
                NarrationTextSegment.of(1, text2)
        );

        String hashSingle = NarrationManifestHasher.computeManifestHash(manifestSingle);
        String hashTwoSegments = NarrationManifestHasher.computeManifestHash(manifestTwoSegments);

        assertThat(hashSingle).isNotEqualTo(hashTwoSegments);
    }

    private static String sha256(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }
}
