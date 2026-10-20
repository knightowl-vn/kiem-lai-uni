package com.universe.screen.entry.admin;

import com.universe.shared.exceptions.BaseApplicationException;
import com.universe.screen.application.dto.DiscardScreenEntryVideoUploadCommand;
import com.universe.screen.application.dto.DiscardScreenEntryVideoUploadResult;
import com.universe.screen.application.dto.FindRecoverableScreenVideoUploadsQuery;
import com.universe.screen.application.dto.RecoverableVideoUploadPageDTO;
import com.universe.screen.application.exceptions.InvalidScreenMediaAssetTypeException;
import com.universe.screen.application.exceptions.ScreenEntryNotFoundException;
import com.universe.screen.application.exceptions.ScreenMediaAssetNotFoundException;
import com.universe.screen.application.exceptions.ScreenUploadSessionNotFoundException;
import com.universe.screen.application.exceptions.ScreenVideoAlreadyReferencedException;
import com.universe.screen.application.exceptions.ScreenVideoDiscardAccessDeniedException;
import com.universe.screen.application.exceptions.ScreenVideoDiscardValidationException;
import com.universe.screen.application.usecase.DiscardScreenEntryVideoUploadUseCase;
import com.universe.screen.application.usecase.FindRecoverableScreenVideoUploadsUseCase;
import com.universe.screen.entry.admin.dto.DiscardVideoUploadResponse;
import com.universe.screen.entry.admin.dto.DiscardVideoUploadSessionRequest;
import com.universe.screen.entry.admin.dto.ScreenVideoErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Objects;
import java.util.UUID;

/**
 * REST controller for discovering and managing completed Screen video upload sessions for orphan recovery.
 *
 * <p><strong>Security Invariants:</strong>
 * <ul>
 *   <li>Requires authenticated admin or super-admin role via global security configuration.</li>
 *   <li>Actor isolation: operations are strictly bound to the authenticated actor's server-side identity.</li>
 *   <li>SUPER_ADMIN does NOT bypass session ownership; actors may only discover and discard their own uploads.</li>
 * </ul>
 */
@RestController
@RequestMapping("/admin/screen/video-uploads")
public class AdminScreenVideoRecoveryController {

    private static final Logger log = LoggerFactory.getLogger(AdminScreenVideoRecoveryController.class);

    private final FindRecoverableScreenVideoUploadsUseCase findRecoverableScreenVideoUploadsUseCase;
    private final DiscardScreenEntryVideoUploadUseCase discardScreenEntryVideoUploadUseCase;

    public AdminScreenVideoRecoveryController(
            FindRecoverableScreenVideoUploadsUseCase findRecoverableScreenVideoUploadsUseCase,
            DiscardScreenEntryVideoUploadUseCase discardScreenEntryVideoUploadUseCase
    ) {
        this.findRecoverableScreenVideoUploadsUseCase = Objects.requireNonNull(
                findRecoverableScreenVideoUploadsUseCase,
                "FindRecoverableScreenVideoUploadsUseCase cannot be null."
        );
        this.discardScreenEntryVideoUploadUseCase = Objects.requireNonNull(
                discardScreenEntryVideoUploadUseCase,
                "DiscardScreenEntryVideoUploadUseCase cannot be null."
        );
    }

    /**
     * Bounded, read-only endpoint for discovering completed Screen VIDEO upload sessions owned by the authenticated actor.
     *
     * @param entryId optional ScreenEntry ID filter
     * @param page zero-based page index (default: 0)
     * @param size page size (default: 20, max: 50)
     * @param authentication Spring Security authentication token
     * @return paginated container of recoverable upload session views
     */
    @GetMapping(value = "/recoverable", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RecoverableVideoUploadPageDTO> getRecoverableUploads(
            @RequestParam(required = false) UUID entryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication
    ) {
        String authenticatedActor = resolvePrincipalId(authentication);

        FindRecoverableScreenVideoUploadsQuery query = FindRecoverableScreenVideoUploadsQuery.of(
                authenticatedActor,
                entryId,
                page,
                size
        );

        RecoverableVideoUploadPageDTO result = findRecoverableScreenVideoUploadsUseCase.execute(query);
        return ResponseEntity.ok(result);
    }

    /**
     * Session-rooted explicit manual-discard endpoint for completed Screen video upload sessions.
     *
     * <p>Permits authorized session owners (ADMIN and SUPER_ADMIN without role bypass) to discard
     * unreferenced completed video uploads even when the original target ScreenEntry or ScreenTitle
     * is missing. Enforces full lock order and server-side session authority.
     *
     * @param sessionId durable upload session ID
     * @param request optional request payload specifying videoMediaAssetId for matching validation
     * @param authentication Spring Security authentication token
     * @return sanitized discard outcome view
     */
    @PostMapping(value = "/{sessionId}/discard", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DiscardVideoUploadResponse> discardRecoverableVideoUpload(
            @PathVariable UUID sessionId,
            @RequestBody(required = false) DiscardVideoUploadSessionRequest request,
            Authentication authentication
    ) {
        String authenticatedActor = resolvePrincipalId(authentication);
        UUID videoMediaAssetId = (request != null) ? request.videoMediaAssetId() : null;

        DiscardScreenEntryVideoUploadCommand command = DiscardScreenEntryVideoUploadCommand.forSession(
                sessionId,
                videoMediaAssetId,
                authenticatedActor
        );

        DiscardScreenEntryVideoUploadResult result = discardScreenEntryVideoUploadUseCase.execute(command);

        return ResponseEntity.ok(new DiscardVideoUploadResponse(
                sessionId,
                result.videoMediaAssetId(),
                result.outcome().name(),
                result.message()
        ));
    }

    private static String resolvePrincipalId(Authentication authentication) {
        if (authentication != null && authentication.getName() != null && !authentication.getName().isBlank()) {
            return authentication.getName().trim();
        }
        throw new AccessDeniedException("Yêu cầu xác thực tài khoản hợp lệ.");
    }

    // =========================================================================
    // EXCEPTION HANDLERS
    // =========================================================================

    @ExceptionHandler(AccessDeniedException.class)
    public void handleAccessDenied(AccessDeniedException ex) {
        throw ex;
    }

    @ExceptionHandler(ScreenVideoDiscardAccessDeniedException.class)
    public ResponseEntity<ScreenVideoErrorResponse> handleDiscardAccessDenied(ScreenVideoDiscardAccessDeniedException ex) {
        log.warn("Access denied in Screen Video Recovery Controller: {} - {}", ex.getErrorCode(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ScreenVideoErrorResponse(
                ex.getErrorCode(),
                ex.getMessage()
        ));
    }

    @ExceptionHandler(ScreenVideoAlreadyReferencedException.class)
    public ResponseEntity<ScreenVideoErrorResponse> handleAlreadyReferenced(ScreenVideoAlreadyReferencedException ex) {
        log.warn("Reference conflict in Screen Video Recovery Controller: {} - {}", ex.getErrorCode(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ScreenVideoErrorResponse(
                ex.getErrorCode(),
                ex.getMessage()
        ));
    }

    @ExceptionHandler(BaseApplicationException.class)
    public ResponseEntity<ScreenVideoErrorResponse> handleBaseApplicationException(BaseApplicationException ex) {
        if (ex instanceof ScreenUploadSessionNotFoundException
                || ex instanceof ScreenEntryNotFoundException
                || ex instanceof ScreenMediaAssetNotFoundException) {
            log.warn("Not found in Screen Video Recovery Controller: {} - {}", ex.getErrorCode(), ex.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ScreenVideoErrorResponse(
                    ex.getErrorCode(),
                    ex.getMessage()
            ));
        }

        if (ex instanceof ScreenVideoAlreadyReferencedException) {
            log.warn("Conflict in Screen Video Recovery Controller: {} - {}", ex.getErrorCode(), ex.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ScreenVideoErrorResponse(
                    ex.getErrorCode(),
                    ex.getMessage()
            ));
        }

        if (ex instanceof ScreenVideoDiscardAccessDeniedException) {
            log.warn("Access denied in Screen Video Recovery Controller: {} - {}", ex.getErrorCode(), ex.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ScreenVideoErrorResponse(
                    ex.getErrorCode(),
                    ex.getMessage()
            ));
        }

        if (ex instanceof ScreenVideoDiscardValidationException
                || ex instanceof InvalidScreenMediaAssetTypeException) {
            log.warn("Business validation error in Screen Video Recovery Controller: {} - {}", ex.getErrorCode(), ex.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ScreenVideoErrorResponse(
                    ex.getErrorCode(),
                    ex.getMessage()
            ));
        }

        log.error("Unknown application exception in Screen Video Recovery Controller: {} - {}", ex.getErrorCode(), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ScreenVideoErrorResponse(
                "INTERNAL_SERVER_ERROR",
                "Đã xảy ra lỗi máy chủ không mong muốn trong quá trình xử lý video."
        ));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ScreenVideoErrorResponse> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        log.warn("Malformed or unreadable HTTP message in Screen Video Recovery Controller: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ScreenVideoErrorResponse(
                "BAD_REQUEST",
                "Dữ liệu yêu cầu không hợp lệ hoặc sai định dạng JSON."
        ));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ScreenVideoErrorResponse> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("Method argument type mismatch in Screen Video Recovery Controller: property={}", ex.getName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ScreenVideoErrorResponse(
                "BAD_REQUEST",
                "Tham số đường dẫn hoặc tham số truy vấn không đúng định dạng."
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ScreenVideoErrorResponse> handleGenericException(Exception ex) {
        log.error("Unhandled exception in Screen Video Recovery Controller: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ScreenVideoErrorResponse(
                "INTERNAL_SERVER_ERROR",
                "Đã xảy ra lỗi máy chủ không mong muốn."
        ));
    }
}
