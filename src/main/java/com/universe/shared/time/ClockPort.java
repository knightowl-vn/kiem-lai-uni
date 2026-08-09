package com.universe.shared.time;


import java.time.Instant;

/**
 * Port kỹ thuật cung cấp thời gian thực cho toàn bộ hệ thống.
 * Hỗ trợ Dependency Inversion và Deterministic Testing.
 */
public interface ClockPort {
    /**
     * Lấy thời gian hiện tại theo chuẩn UTC.
     * @return Thời điểm hiện tại (Instant)
     */
    Instant now();
}