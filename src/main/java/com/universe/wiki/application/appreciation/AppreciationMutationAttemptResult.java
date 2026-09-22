package com.universe.wiki.application.appreciation;

/**
 * Kết quả thực thi một lượt ghi nhận đánh giá của AttemptExecutor.
 *
 * @param changed true nếu có thay đổi lưu trữ (thêm mới hoặc cập nhật khác điểm), false nếu là same-value no-op
 */
public record AppreciationMutationAttemptResult(boolean changed) {
}
