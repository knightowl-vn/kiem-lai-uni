package com.universe.novel.application.narration;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Read-only snapshot of an Admin narration generation operation state (MS-04.9H.7D4A).
 * <p>
 * <strong>Security & Clean Architecture:</strong>
 * Never contains raw exception stack traces, provider identifiers, or storage details.
 *
 * @param chapterId      identity of the chapter
 * @param managedVoiceId identity of the managed voice
 * @param status         current lifecycle status
 * @param startedAt      timestamp when generation started (null if IDLE)
 * @param completedAt    timestamp when generation completed (null if IDLE or RUNNING)
 * @param message        safe, human-readable summary message
 */
public record AdminNarrationOperationState(
        UUID chapterId,
        UUID managedVoiceId,
        AdminNarrationOperationStatus status,
        Instant startedAt,
        Instant completedAt,
        String message
) {
    public AdminNarrationOperationState {
        Objects.requireNonNull(chapterId, "chapterId must not be null");
        Objects.requireNonNull(managedVoiceId, "managedVoiceId must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }

    public static AdminNarrationOperationState idle(UUID chapterId, UUID managedVoiceId) {
        return new AdminNarrationOperationState(
                chapterId,
                managedVoiceId,
                AdminNarrationOperationStatus.IDLE,
                null,
                null,
                "Chưa có tiến trình tạo giọng đọc nào."
        );
    }

    public static AdminNarrationOperationState running(UUID chapterId, UUID managedVoiceId, Instant startedAt) {
        return new AdminNarrationOperationState(
                chapterId,
                managedVoiceId,
                AdminNarrationOperationStatus.RUNNING,
                startedAt != null ? startedAt : Instant.now(),
                null,
                "Đang xử lý tạo giọng đọc cho chương..."
        );
    }

    public static AdminNarrationOperationState succeeded(UUID chapterId, UUID managedVoiceId, Instant startedAt, Instant completedAt, String message) {
        return new AdminNarrationOperationState(
                chapterId,
                managedVoiceId,
                AdminNarrationOperationStatus.SUCCEEDED,
                startedAt,
                completedAt != null ? completedAt : Instant.now(),
                message != null ? message : "Tạo giọng đọc cho chương hoàn tất thành công."
        );
    }

    public static AdminNarrationOperationState partial(UUID chapterId, UUID managedVoiceId, Instant startedAt, Instant completedAt, String message) {
        return new AdminNarrationOperationState(
                chapterId,
                managedVoiceId,
                AdminNarrationOperationStatus.PARTIAL,
                startedAt,
                completedAt != null ? completedAt : Instant.now(),
                message != null ? message : "Tạo giọng đọc hoàn tất với một số đoạn bị lỗi."
        );
    }

    public static AdminNarrationOperationState failed(UUID chapterId, UUID managedVoiceId, Instant startedAt, Instant completedAt, String message) {
        return new AdminNarrationOperationState(
                chapterId,
                managedVoiceId,
                AdminNarrationOperationStatus.FAILED,
                startedAt,
                completedAt != null ? completedAt : Instant.now(),
                message != null ? message : "Tạo giọng đọc cho chương thất bại."
        );
    }

    public boolean isRunning() {
        return status == AdminNarrationOperationStatus.RUNNING;
    }
}
