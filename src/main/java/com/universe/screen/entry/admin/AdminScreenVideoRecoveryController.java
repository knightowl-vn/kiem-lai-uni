package com.universe.screen.entry.admin;

import com.universe.screen.application.dto.FindRecoverableScreenVideoUploadsQuery;
import com.universe.screen.application.dto.RecoverableVideoUploadPageDTO;
import com.universe.screen.application.usecase.FindRecoverableScreenVideoUploadsUseCase;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/**
 * REST controller for discovering completed Screen video upload sessions for orphan recovery.
 *
 * <p><strong>Security Invariants:</strong>
 * <ul>
 *   <li>Requires authenticated admin or super-admin role via global security configuration.</li>
 *   <li>Actor isolation: queries are strictly filtered by the authenticated actor's server-side identity.</li>
 *   <li>SUPER_ADMIN does NOT bypass session ownership; users only discover their own completed uploads.</li>
 * </ul>
 */
@RestController
@RequestMapping("/admin/screen/video-uploads")
public class AdminScreenVideoRecoveryController {

    private final FindRecoverableScreenVideoUploadsUseCase findRecoverableScreenVideoUploadsUseCase;

    public AdminScreenVideoRecoveryController(
            FindRecoverableScreenVideoUploadsUseCase findRecoverableScreenVideoUploadsUseCase
    ) {
        this.findRecoverableScreenVideoUploadsUseCase = Objects.requireNonNull(
                findRecoverableScreenVideoUploadsUseCase,
                "FindRecoverableScreenVideoUploadsUseCase cannot be null."
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

    private static String resolvePrincipalId(Authentication authentication) {
        if (authentication != null && authentication.getName() != null && !authentication.getName().isBlank()) {
            return authentication.getName().trim();
        }
        throw new AccessDeniedException("Yêu cầu xác thực tài khoản hợp lệ.");
    }
}
