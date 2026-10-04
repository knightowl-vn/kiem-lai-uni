package com.universe.community.domain.exception;

/**
 * Thrown when an optimistic concurrency conflict occurs while updating Community settings.
 */
public class CommunitySettingsOptimisticLockException extends RuntimeException {

    private final long expectedVersion;

    public CommunitySettingsOptimisticLockException(long expectedVersion) {
        super("Cài đặt cộng đồng đã được cập nhật bởi quản trị viên khác. Phiên bản dự kiến: " + expectedVersion);
        this.expectedVersion = expectedVersion;
    }

    public long getExpectedVersion() {
        return expectedVersion;
    }
}
