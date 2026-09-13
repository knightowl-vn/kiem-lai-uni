package com.universe.novel.application.narration;

import java.util.Objects;

/**
 * Result returned by {@link AdminNarrationGenerationDispatcher#dispatch} (MS-04.9H.7D4A).
 *
 * @param status  dispatch disposition status
 * @param state   current operation state snapshot
 * @param message safe diagnostic / status message
 */
public record AdminNarrationDispatchResult(
        AdminNarrationDispatchStatus status,
        AdminNarrationOperationState state,
        String message
) {
    public AdminNarrationDispatchResult {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(state, "state must not be null");
    }

    public static AdminNarrationDispatchResult started(AdminNarrationOperationState state) {
        return new AdminNarrationDispatchResult(
                AdminNarrationDispatchStatus.STARTED,
                state,
                "Tiến trình tạo giọng đọc đã được khởi chạy trong nền."
        );
    }

    public static AdminNarrationDispatchResult alreadyRunning(AdminNarrationOperationState state) {
        return new AdminNarrationDispatchResult(
                AdminNarrationDispatchStatus.ALREADY_RUNNING,
                state,
                "Tiến trình tạo giọng đọc cho chương và giọng đọc này đang chạy."
        );
    }

    public static AdminNarrationDispatchResult rejected(AdminNarrationOperationState state) {
        return new AdminNarrationDispatchResult(
                AdminNarrationDispatchStatus.REJECTED,
                state,
                "Hệ thống đang bận. Hàng đợi tác vụ tạo giọng đọc đã đầy, vui lòng thử lại sau."
        );
    }

    public static AdminNarrationDispatchResult failed(AdminNarrationOperationState state, String message) {
        return new AdminNarrationDispatchResult(
                AdminNarrationDispatchStatus.FAILED,
                state,
                message != null ? message : "Không thể khởi chạy tiến trình tạo giọng đọc."
        );
    }
}
