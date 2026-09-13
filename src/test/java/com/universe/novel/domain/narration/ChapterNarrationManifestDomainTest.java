package com.universe.novel.domain.narration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ChapterNarrationManifest Domain Tests")
class ChapterNarrationManifestDomainTest {

    private static final String VALID_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String VALID_HASH_2 = "a".repeat(64);

    @Test
    @DisplayName("Should successfully create a ChapterNarrationManifest")
    void shouldCreateManifestSuccessfully() {
        UUID chapterId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        ChapterNarrationManifest manifest = ChapterNarrationManifest.create(
                chapterId,
                1L,
                VALID_HASH,
                now
        );

        assertThat(manifest.getChapterId()).isEqualTo(chapterId);
        assertThat(manifest.getSourceContentVersion()).isEqualTo(1L);
        assertThat(manifest.getManifestHash()).isEqualTo(VALID_HASH);
        assertThat(manifest.getCreatedAt()).isEqualTo(now);
        assertThat(manifest.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("Should rehydrate manifest with distinct createdAt and updatedAt")
    void shouldRehydrateManifestSuccessfully() {
        UUID chapterId = UUID.randomUUID();
        Instant createdAt = Instant.now().minusSeconds(100).truncatedTo(ChronoUnit.MICROS);
        Instant updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        ChapterNarrationManifest manifest = ChapterNarrationManifest.rehydrate(
                chapterId,
                3L,
                VALID_HASH,
                createdAt,
                updatedAt
        );

        assertThat(manifest.getChapterId()).isEqualTo(chapterId);
        assertThat(manifest.getSourceContentVersion()).isEqualTo(3L);
        assertThat(manifest.getManifestHash()).isEqualTo(VALID_HASH);
        assertThat(manifest.getCreatedAt()).isEqualTo(createdAt);
        assertThat(manifest.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    @DisplayName("Should reject create when chapterId is null")
    void shouldRejectNullChapterId() {
        Instant now = Instant.now();
        assertThatThrownBy(() -> ChapterNarrationManifest.create(null, 1L, VALID_HASH, now))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("chapterId must not be null");
    }

    @Test
    @DisplayName("Should reject create when sourceContentVersion is less than 1")
    void shouldRejectInvalidSourceContentVersion() {
        UUID chapterId = UUID.randomUUID();
        Instant now = Instant.now();

        assertThatThrownBy(() -> ChapterNarrationManifest.create(chapterId, 0L, VALID_HASH, now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceContentVersion must be >= 1");

        assertThatThrownBy(() -> ChapterNarrationManifest.create(chapterId, -1L, VALID_HASH, now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceContentVersion must be >= 1");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "not-a-hash",
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b85", // 63 chars
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b8555", // 65 chars
            "E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855", // uppercase
            "z3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" // invalid non-hex char 'z'
    })
    @DisplayName("Should reject invalid manifestHash values")
    void shouldRejectInvalidManifestHash(String invalidHash) {
        UUID chapterId = UUID.randomUUID();
        Instant now = Instant.now();

        assertThatThrownBy(() -> ChapterNarrationManifest.create(chapterId, 1L, invalidHash, now))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            " " + VALID_HASH,
            VALID_HASH + " ",
            "  " + VALID_HASH + "  ",
            "\t" + VALID_HASH,
            VALID_HASH + "\t",
            "\n" + VALID_HASH,
            VALID_HASH + "\n",
            "\r\n" + VALID_HASH
    })
    @DisplayName("Should reject manifestHash with leading or trailing whitespace without trimming")
    void shouldRejectManifestHashWithWhitespace(String whitespaceHash) {
        UUID chapterId = UUID.randomUUID();
        Instant now = Instant.now();

        assertThatThrownBy(() -> ChapterNarrationManifest.create(chapterId, 1L, whitespaceHash, now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("manifestHash must be exactly a 64-character lowercase hexadecimal string");

        ChapterNarrationManifest manifest = ChapterNarrationManifest.create(chapterId, 1L, VALID_HASH, now);
        assertThatThrownBy(() -> manifest.reconcileTo(2L, whitespaceHash, now.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("manifestHash must be exactly a 64-character lowercase hexadecimal string");
    }

    @Test
    @DisplayName("Should reject null manifestHash")
    void shouldRejectNullManifestHash() {
        UUID chapterId = UUID.randomUUID();
        Instant now = Instant.now();

        assertThatThrownBy(() -> ChapterNarrationManifest.create(chapterId, 1L, null, now))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should reject rehydrate when updatedAt precedes createdAt")
    void shouldRejectUpdatedAtBeforeCreatedAt() {
        UUID chapterId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        Instant updatedAt = createdAt.minusSeconds(10);

        assertThatThrownBy(() -> ChapterNarrationManifest.rehydrate(chapterId, 1L, VALID_HASH, createdAt, updatedAt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("updatedAt must not precede createdAt");
    }

    @Test
    @DisplayName("ReconcileTo with identical version and hash should be a no-op")
    void shouldNoOpWhenReconcilingIdenticalVersionAndHash() {
        UUID chapterId = UUID.randomUUID();
        Instant createdAt = Instant.now().minusSeconds(100).truncatedTo(ChronoUnit.MICROS);
        Instant updatedAt = createdAt;

        ChapterNarrationManifest manifest = ChapterNarrationManifest.rehydrate(
                chapterId,
                1L,
                VALID_HASH,
                createdAt,
                updatedAt
        );

        Instant reconcileTime = Instant.now().truncatedTo(ChronoUnit.MICROS);
        manifest.reconcileTo(1L, VALID_HASH, reconcileTime);

        assertThat(manifest.getSourceContentVersion()).isEqualTo(1L);
        assertThat(manifest.getManifestHash()).isEqualTo(VALID_HASH);
        assertThat(manifest.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    @DisplayName("ReconcileTo with same version but different hash should update hash and updatedAt")
    void shouldUpdateHashAndTimestampOnSameVersionDifferentHash() {
        UUID chapterId = UUID.randomUUID();
        Instant createdAt = Instant.now().minusSeconds(100).truncatedTo(ChronoUnit.MICROS);

        ChapterNarrationManifest manifest = ChapterNarrationManifest.rehydrate(
                chapterId,
                1L,
                VALID_HASH,
                createdAt,
                createdAt
        );

        Instant reconcileTime = Instant.now().truncatedTo(ChronoUnit.MICROS);
        manifest.reconcileTo(1L, VALID_HASH_2, reconcileTime);

        assertThat(manifest.getSourceContentVersion()).isEqualTo(1L);
        assertThat(manifest.getManifestHash()).isEqualTo(VALID_HASH_2);
        assertThat(manifest.getUpdatedAt()).isEqualTo(reconcileTime);
    }

    @Test
    @DisplayName("ReconcileTo with higher version should update sourceContentVersion, hash, and updatedAt")
    void shouldUpdateOnHigherVersion() {
        UUID chapterId = UUID.randomUUID();
        Instant createdAt = Instant.now().minusSeconds(100).truncatedTo(ChronoUnit.MICROS);

        ChapterNarrationManifest manifest = ChapterNarrationManifest.create(
                chapterId,
                1L,
                VALID_HASH,
                createdAt
        );

        Instant reconcileTime = Instant.now().truncatedTo(ChronoUnit.MICROS);
        manifest.reconcileTo(2L, VALID_HASH_2, reconcileTime);

        assertThat(manifest.getSourceContentVersion()).isEqualTo(2L);
        assertThat(manifest.getManifestHash()).isEqualTo(VALID_HASH_2);
        assertThat(manifest.getUpdatedAt()).isEqualTo(reconcileTime);
    }

    @Test
    @DisplayName("ReconcileTo must reject moving backward to an earlier version")
    void shouldRejectReconcileToEarlierVersion() {
        UUID chapterId = UUID.randomUUID();
        Instant createdAt = Instant.now().minusSeconds(100);

        ChapterNarrationManifest manifest = ChapterNarrationManifest.create(
                chapterId,
                3L,
                VALID_HASH,
                createdAt
        );

        Instant now = Instant.now();
        assertThatThrownBy(() -> manifest.reconcileTo(2L, VALID_HASH_2, now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot reconcile manifest to an earlier source content version");
    }

    @Test
    @DisplayName("ReconcileTo must reject timestamp before createdAt")
    void shouldRejectReconcileTimestampBeforeCreatedAt() {
        UUID chapterId = UUID.randomUUID();
        Instant createdAt = Instant.now();

        ChapterNarrationManifest manifest = ChapterNarrationManifest.create(
                chapterId,
                1L,
                VALID_HASH,
                createdAt
        );

        Instant beforeCreated = createdAt.minusSeconds(10);
        assertThatThrownBy(() -> manifest.reconcileTo(2L, VALID_HASH_2, beforeCreated))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("now must not precede createdAt");
    }

    @Test
    @DisplayName("Equals and hashCode should be based strictly on chapterId aggregate identity")
    void shouldEnforceIdentityBasedEquality() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        Instant now = Instant.now();

        ChapterNarrationManifest m1 = ChapterNarrationManifest.create(id1, 1L, VALID_HASH, now);
        ChapterNarrationManifest m1DifferentState = ChapterNarrationManifest.create(id1, 2L, VALID_HASH_2, now.plusSeconds(10));
        ChapterNarrationManifest m2 = ChapterNarrationManifest.create(id2, 1L, VALID_HASH, now);

        assertThat(m1).isEqualTo(m1DifferentState);
        assertThat(m1.hashCode()).isEqualTo(m1DifferentState.hashCode());

        assertThat(m1).isNotEqualTo(m2);
        assertThat(m1.hashCode()).isNotEqualTo(m2.hashCode());
    }
}
