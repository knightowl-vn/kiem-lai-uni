package com.universe.novel.domain.narration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("NarrationMediaCleanupTask Domain Model Tests")
class NarrationMediaCleanupTaskDomainTest {

    private static final UUID ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID MEDIA_ASSET_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final Instant NOW = Instant.parse("2026-09-06T10:00:00Z");

    @Test
    @DisplayName("Should successfully create a valid NarrationMediaCleanupTask with initial defaults")
    void shouldCreateValidCleanupTask() {
        NarrationMediaCleanupTask task = NarrationMediaCleanupTask.create(
                ID,
                MEDIA_ASSET_ID,
                NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET,
                NOW
        );

        assertThat(task.getId()).isEqualTo(ID);
        assertThat(task.getMediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
        assertThat(task.getReason()).isEqualTo(NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET);
        assertThat(task.getAttemptCount()).isEqualTo(0);
        assertThat(task.getLastErrorType()).isNull();
        assertThat(task.getCreatedAt()).isEqualTo(NOW);
        assertThat(task.getLastAttemptAt()).isNull();
        assertThat(task.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("Should reject null mandatory parameters on create")
    void shouldRejectNullArgumentsOnCreate() {
        assertThatThrownBy(() -> NarrationMediaCleanupTask.create(null, MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET, NOW))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> NarrationMediaCleanupTask.create(ID, null, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET, NOW))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> NarrationMediaCleanupTask.create(ID, MEDIA_ASSET_ID, null, NOW))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> NarrationMediaCleanupTask.create(ID, MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should successfully rehydrate cleanup task preserving all persisted state")
    void shouldRehydratePreservingState() {
        Instant createdAt = NOW.minusSeconds(300);
        Instant lastAttemptAt = NOW.minusSeconds(60);
        Instant updatedAt = NOW.minusSeconds(60);

        NarrationMediaCleanupTask task = NarrationMediaCleanupTask.rehydrate(
                ID,
                MEDIA_ASSET_ID,
                NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET,
                3,
                "MediaNotFoundException",
                createdAt,
                lastAttemptAt,
                updatedAt
        );

        assertThat(task.getId()).isEqualTo(ID);
        assertThat(task.getMediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
        assertThat(task.getReason()).isEqualTo(NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET);
        assertThat(task.getAttemptCount()).isEqualTo(3);
        assertThat(task.getLastErrorType()).isEqualTo("MediaNotFoundException");
        assertThat(task.getCreatedAt()).isEqualTo(createdAt);
        assertThat(task.getLastAttemptAt()).isEqualTo(lastAttemptAt);
        assertThat(task.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    @DisplayName("Should reject rehydration if attemptCount is negative")
    void shouldRejectNegativeAttemptCountOnRehydration() {
        assertThatThrownBy(() -> NarrationMediaCleanupTask.rehydrate(
                ID, MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET, -1, null, NOW, null, NOW
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attemptCount không được âm");
    }

    @Test
    @DisplayName("Should reject rehydration if updatedAt is before createdAt")
    void shouldRejectUpdatedAtBeforeCreatedAt() {
        Instant createdAt = NOW;
        Instant updatedAt = NOW.minusSeconds(10);

        assertThatThrownBy(() -> NarrationMediaCleanupTask.rehydrate(
                ID, MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET, 0, null, createdAt, null, updatedAt
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Thời gian cập nhật không được trước thời gian tạo");
    }

    @Test
    @DisplayName("Should reject rehydration if lastAttemptAt is before createdAt")
    void shouldRejectLastAttemptAtBeforeCreatedAt() {
        Instant createdAt = NOW;
        Instant lastAttemptAt = NOW.minusSeconds(10);

        assertThatThrownBy(() -> NarrationMediaCleanupTask.rehydrate(
                ID, MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET, 1, "Error", createdAt, lastAttemptAt, NOW
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Thời gian thử gần nhất không được trước thời gian tạo");
    }

    @Test
    @DisplayName("Should record failed attempt with string error type and update state correctly")
    void shouldRecordFailedAttemptWithString() {
        NarrationMediaCleanupTask task = NarrationMediaCleanupTask.create(
                ID,
                MEDIA_ASSET_ID,
                NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET,
                NOW
        );

        Instant attempt1 = NOW.plusSeconds(30);
        task.recordFailedAttempt("StorageConnectionException", attempt1);

        assertThat(task.getAttemptCount()).isEqualTo(1);
        assertThat(task.getLastErrorType()).isEqualTo("StorageConnectionException");
        assertThat(task.getLastAttemptAt()).isEqualTo(attempt1);
        assertThat(task.getUpdatedAt()).isEqualTo(attempt1);
        assertThat(task.getCreatedAt()).isEqualTo(NOW);

        Instant attempt2 = NOW.plusSeconds(60);
        task.recordFailedAttempt("MediaAssetNotFoundException", attempt2);

        assertThat(task.getAttemptCount()).isEqualTo(2);
        assertThat(task.getLastErrorType()).isEqualTo("MediaAssetNotFoundException");
        assertThat(task.getLastAttemptAt()).isEqualTo(attempt2);
        assertThat(task.getUpdatedAt()).isEqualTo(attempt2);
    }

    @Test
    @DisplayName("Should record failed attempt with throwable deriving simple exception name")
    void shouldRecordFailedAttemptWithThrowable() {
        NarrationMediaCleanupTask task = NarrationMediaCleanupTask.create(
                ID,
                MEDIA_ASSET_ID,
                NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET,
                NOW
        );

        Instant attempt = NOW.plusSeconds(15);
        task.recordFailedAttempt(new java.io.IOException("Disk full"), attempt);

        assertThat(task.getAttemptCount()).isEqualTo(1);
        assertThat(task.getLastErrorType()).isEqualTo("IOException");
        assertThat(task.getLastAttemptAt()).isEqualTo(attempt);
        assertThat(task.getUpdatedAt()).isEqualTo(attempt);
    }

    @Test
    @DisplayName("Should sanitize multiline stack traces and retain only the root error type token")
    void shouldSanitizeMultilineStackTraceErrorType() {
        NarrationMediaCleanupTask task = NarrationMediaCleanupTask.create(
                ID,
                MEDIA_ASSET_ID,
                NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET,
                NOW
        );

        String stackTrace = "java.lang.IllegalStateException: Storage volume disconnected\n" +
                "\tat com.universe.media.Storage.write(Storage.java:102)\n" +
                "\tat com.universe.novel.Task.run(Task.java:55)";

        task.recordFailedAttempt(stackTrace, NOW.plusSeconds(10));

        assertThat(task.getLastErrorType()).isEqualTo("java.lang.IllegalStateException");
        assertThat(task.getLastErrorType()).doesNotContain("\n");
        assertThat(task.getLastErrorType()).doesNotContain("Storage volume disconnected");
        assertThat(task.getLastErrorType()).doesNotContain("at com.universe");
    }

    @Test
    @DisplayName("Should sanitize raw provider messages and retain only the leading error type token")
    void shouldSanitizeRawProviderMessageErrorType() {
        NarrationMediaCleanupTask task = NarrationMediaCleanupTask.create(
                ID,
                MEDIA_ASSET_ID,
                NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET,
                NOW
        );

        String providerMessage = "CloudinaryAPIException: 400 Bad Request - Invalid signature parameter foo=bar";
        task.recordFailedAttempt(providerMessage, NOW.plusSeconds(10));

        assertThat(task.getLastErrorType()).isEqualTo("CloudinaryAPIException");
        assertThat(task.getLastErrorType()).doesNotContain("400 Bad Request");
        assertThat(task.getLastErrorType()).doesNotContain("foo=bar");
    }

    @Test
    @DisplayName("Should sanitize arbitrary prose sentence into single token")
    void shouldSanitizeArbitraryProseErrorType() {
        NarrationMediaCleanupTask task = NarrationMediaCleanupTask.create(
                ID,
                MEDIA_ASSET_ID,
                NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET,
                NOW
        );

        String prose = "Failed to delete media asset from S3 bucket kiemlai-assets";
        task.recordFailedAttempt(prose, NOW.plusSeconds(10));

        assertThat(task.getLastErrorType()).isEqualTo("Failed");
        assertThat(task.getLastErrorType()).doesNotContain("to delete media asset");
    }

    @Test
    @DisplayName("Should sanitize long error types to maximum 200 characters")
    void shouldSanitizeLongErrorType() {
        NarrationMediaCleanupTask task = NarrationMediaCleanupTask.create(
                ID,
                MEDIA_ASSET_ID,
                NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET,
                NOW
        );

        String longErrorType = "E".repeat(250);
        task.recordFailedAttempt(longErrorType, NOW.plusSeconds(10));

        assertThat(task.getLastErrorType()).hasSize(200);
        assertThat(task.getLastErrorType()).isEqualTo("E".repeat(200));
    }

    @Test
    @DisplayName("Should handle null and blank error types gracefully")
    void shouldHandleNullAndBlankErrorTypes() {
        NarrationMediaCleanupTask task = NarrationMediaCleanupTask.create(
                ID,
                MEDIA_ASSET_ID,
                NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET,
                NOW
        );

        task.recordFailedAttempt("   ", NOW.plusSeconds(10));
        assertThat(task.getLastErrorType()).isNull();

        task.recordFailedAttempt((String) null, NOW.plusSeconds(20));
        assertThat(task.getLastErrorType()).isNull();
    }

    @Test
    @DisplayName("Should reject recording failed attempt with null timestamp or before createdAt")
    void shouldRejectInvalidAttemptTimestamps() {
        NarrationMediaCleanupTask task = NarrationMediaCleanupTask.create(
                ID,
                MEDIA_ASSET_ID,
                NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET,
                NOW
        );

        assertThatThrownBy(() -> task.recordFailedAttempt("Error", null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> task.recordFailedAttempt("Error", NOW.minusSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Thời gian thử không được trước thời gian tạo");

        Instant attempt1 = NOW.plusSeconds(30);
        task.recordFailedAttempt("Error", attempt1);

        assertThatThrownBy(() -> task.recordFailedAttempt("Error", NOW.plusSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Thời gian thử mới không được trước thời gian thử trước đó");
    }

    @Test
    @DisplayName("Should verify equality and hashCode based on ID")
    void shouldVerifyEqualityAndHashCode() {
        NarrationMediaCleanupTask task1 = NarrationMediaCleanupTask.create(
                ID, MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET, NOW
        );
        NarrationMediaCleanupTask task2 = NarrationMediaCleanupTask.create(
                ID, UUID.randomUUID(), NarrationMediaCleanupReason.SUPERSEDED_REGENERATION_ASSET, NOW.plusSeconds(10)
        );
        NarrationMediaCleanupTask task3 = NarrationMediaCleanupTask.create(
                UUID.randomUUID(), MEDIA_ASSET_ID, NarrationMediaCleanupReason.UNREFERENCED_GENERATED_ASSET, NOW
        );

        assertThat(task1).isEqualTo(task2);
        assertThat(task1.hashCode()).isEqualTo(task2.hashCode());
        assertThat(task1).isNotEqualTo(task3);
    }
}
