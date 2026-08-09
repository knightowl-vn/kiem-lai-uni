package com.universe.shared.web;


import org.slf4j.MDC;
import java.time.Instant;
import java.util.Map;

/**
 * Class chuẩn hóa toàn bộ API Response của hệ thống Kiếm Lai Universe.
 */
public record ApiResponse<T>(
        String code,
        String message,
        T data,
        Map<String, String> meta
) {
    // Phương thức tĩnh tiện ích để trả về dữ liệu thành công
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(
                "SUCCESS",
                "Operation completed",
                data,
                Map.of(
                        // Lấy CorrelationID để dễ dàng tra cứu log khi có lỗi
                        "correlationId", MDC.get("correlationId") != null ? MDC.get("correlationId") : "",
                        "timestamp", Instant.now().toString()
                )
        );
    }
}