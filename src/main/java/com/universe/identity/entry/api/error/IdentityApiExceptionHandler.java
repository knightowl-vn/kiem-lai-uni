package com.universe.identity.entry.api.error;

import com.universe.identity.domain.exceptions.EmailAlreadyExistsException;
import com.universe.identity.domain.exceptions.InvalidEmailException;
import com.universe.identity.domain.exceptions.WeakPasswordException;
import com.universe.shared.exceptions.BaseApplicationException;
import com.universe.shared.web.error.ApiErrorResponse;
import com.universe.shared.web.error.FieldErrorResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice(
        basePackages =
                "com.universe.identity.entry.api"
)

public class IdentityApiExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(
                    IdentityApiExceptionHandler.class
            );

    /**
     * Email đã tồn tại.
     */
    @ExceptionHandler(
            EmailAlreadyExistsException.class
    )
    public ResponseEntity<ApiErrorResponse>
    handleEmailAlreadyExists(
            EmailAlreadyExistsException exception
    ) {
        ApiErrorResponse response =
                new ApiErrorResponse(
                        exception.getErrorCode(),
                        exception.getMessage(),
                        List.of(),
                        Instant.now()
                );

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(response);
    }

    /**
     * Email sai định dạng.
     */
    @ExceptionHandler(
            InvalidEmailException.class
    )
    public ResponseEntity<ApiErrorResponse>
    handleInvalidEmail(
            InvalidEmailException exception
    ) {
        ApiErrorResponse response =
                new ApiErrorResponse(
                        exception.getErrorCode(),
                        exception.getMessage(),
                        List.of(),
                        Instant.now()
                );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
    }

    /**
     * Mật khẩu không đạt yêu cầu.
     */
    @ExceptionHandler(
            WeakPasswordException.class
    )
    public ResponseEntity<ApiErrorResponse>
    handleWeakPassword(
            WeakPasswordException exception
    ) {
        ApiErrorResponse response =
                new ApiErrorResponse(
                        exception.getErrorCode(),
                        exception.getMessage(),
                        List.of(),
                        Instant.now()
                );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
    }

    /**
     * Lỗi Bean Validation từ @Valid.
     *
     * Ví dụ:
     * - email trống;
     * - password quá ngắn;
     * - displayName quá dài.
     */
    @ExceptionHandler(
            MethodArgumentNotValidException.class
    )
    public ResponseEntity<ApiErrorResponse>
    handleValidation(
            MethodArgumentNotValidException exception
    ) {
        List<FieldErrorResponse> fieldErrors =
                exception.getBindingResult()
                        .getFieldErrors()
                        .stream()
                        .map(this::toFieldErrorResponse)
                        .toList();

        String message =
                fieldErrors.isEmpty()
                        ? "Dữ liệu không hợp lệ."
                        : fieldErrors.get(0).message();

        ApiErrorResponse response =
                new ApiErrorResponse(
                        "VALIDATION_ERROR",
                        message,
                        fieldErrors,
                        Instant.now()
                );

        return ResponseEntity
                .badRequest()
                .body(response);
    }

    /**
     * Các BaseApplicationException khác.
     */
    @ExceptionHandler(
            BaseApplicationException.class
    )
    public ResponseEntity<ApiErrorResponse>
    handleApplicationException(
            BaseApplicationException exception
    ) {
        ApiErrorResponse response =
                new ApiErrorResponse(
                        exception.getErrorCode(),
                        exception.getMessage(),
                        List.of(),
                        Instant.now()
                );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
    }

    /**
     * IllegalArgumentException dùng cho dữ liệu đầu vào
     * không hợp lệ nhưng chưa có exception riêng.
     */
    @ExceptionHandler(
            IllegalArgumentException.class
    )
    public ResponseEntity<ApiErrorResponse>
    handleIllegalArgument(
            IllegalArgumentException exception
    ) {
        ApiErrorResponse response =
                new ApiErrorResponse(
                        "INVALID_ARGUMENT",
                        exception.getMessage(),
                        List.of(),
                        Instant.now()
                );

        return ResponseEntity
                .badRequest()
                .body(response);
    }

    /**
     * Lỗi ngoài dự kiến.
     *
     * Không trả exception.getMessage() ra frontend
     * để tránh lộ thông tin nội bộ.
     */
    @ExceptionHandler(
            Exception.class
    )
    public ResponseEntity<ApiErrorResponse>
    handleUnexpectedException(
            Exception exception
    ) {
        log.error(
                "Lỗi hệ thống ngoài dự kiến.",
                exception
        );

        ApiErrorResponse response =
                new ApiErrorResponse(
                        "INTERNAL_SERVER_ERROR",
                        "Hệ thống đang gặp sự cố. Vui lòng thử lại sau.",
                        List.of(),
                        Instant.now()
                );

        return ResponseEntity
                .status(
                        HttpStatus.INTERNAL_SERVER_ERROR
                )
                .body(response);
    }

    private FieldErrorResponse toFieldErrorResponse(
            FieldError fieldError
    ) {
        String message =
                fieldError.getDefaultMessage() == null
                        ? "Giá trị không hợp lệ."
                        : fieldError.getDefaultMessage();

        return new FieldErrorResponse(
                fieldError.getField(),
                message
        );
    }
}