package com.universe.screen.entry.admin;

import com.universe.media.contracts.dto.AllocateUploadSessionRequestDTO;
import com.universe.media.contracts.dto.ClaimCompletionIntentRequestDTO;
import com.universe.media.contracts.dto.CompleteMultipartUploadRequestDTO;
import com.universe.media.contracts.dto.MediaUploadSessionResponseDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.screen.application.dto.DiscardScreenEntryVideoUploadCommand;
import com.universe.screen.application.dto.DiscardScreenEntryVideoUploadResult;
import com.universe.screen.application.dto.ScreenEntryDTO;
import com.universe.screen.application.exceptions.InvalidScreenMediaAssetTypeException;
import com.universe.screen.application.exceptions.ScreenEntryNotFoundException;
import com.universe.screen.application.exceptions.ScreenEntryVideoAlreadyAttachedException;
import com.universe.screen.application.exceptions.ScreenEntryVideoConflictException;
import com.universe.screen.application.exceptions.ScreenEntryVideoNotAttachedException;
import com.universe.screen.application.exceptions.ScreenMediaAssetNotFoundException;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.screen.application.exceptions.ScreenUploadSessionNotFoundException;
import com.universe.screen.application.exceptions.ScreenVideoAlreadyReferencedException;
import com.universe.screen.application.exceptions.ScreenVideoDiscardAccessDeniedException;
import com.universe.screen.application.dto.ReconcileScreenVideoVisibilityResult;
import com.universe.screen.application.exceptions.ScreenVideoDiscardValidationException;
import com.universe.screen.application.usecase.AttachScreenEntryVideoUseCase;
import com.universe.screen.application.usecase.DetachScreenEntryVideoUseCase;
import com.universe.screen.application.usecase.DiscardScreenEntryVideoUploadUseCase;
import com.universe.screen.application.usecase.GetScreenEntryDetailUseCase;
import com.universe.screen.application.usecase.GetScreenTitleDetailUseCase;
import com.universe.screen.application.usecase.ReconcileScreenEntryVideoVisibilityUseCase;
import com.universe.screen.application.usecase.ReplaceScreenEntryVideoUseCase;
import com.universe.screen.entry.admin.dto.CompleteVideoUploadResponse;
import com.universe.screen.entry.admin.dto.CompleteVideoUploadSessionRequest;
import com.universe.screen.entry.admin.dto.DiscardVideoUploadResponse;
import com.universe.screen.entry.admin.dto.DiscardVideoUploadSessionRequest;
import com.universe.screen.entry.admin.dto.InitiateVideoUploadSessionRequest;
import com.universe.screen.entry.admin.dto.ReconcileScreenVideoVisibilityResponse;
import com.universe.screen.entry.admin.dto.ScreenVideoErrorResponse;
import com.universe.screen.entry.admin.dto.VideoPartUploadUrlView;
import com.universe.screen.entry.admin.dto.VideoUploadSessionView;
import com.universe.shared.exceptions.BaseApplicationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.exceptions.StorageAmbiguousInitiationException;
import com.universe.media.application.exceptions.StorageInitiationRejectedException;
import com.universe.media.application.exceptions.UploadSessionInitiationConflictException;
import com.universe.media.application.exceptions.UploadSessionUnconfirmedInitiationException;
import com.universe.media.contracts.dto.MediaAssetDetailDTO;
import com.universe.media.contracts.dto.MediaAssetStatusDTO;
import com.universe.media.contracts.dto.MediaAssetStreamDTO;
import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaVersionDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.context.request.async.WebAsyncManager;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * REST controller orchestrating browser-to-B2 multipart video upload sessions, attachment,
 * detachment, and atomic replacement for ScreenEntry targets.
 */
@RestController
@RequestMapping("/admin/screen/titles/{titleId}/entries/{entryId}/video")
public class AdminScreenEntryVideoController {

    private static final Logger log = LoggerFactory.getLogger(AdminScreenEntryVideoController.class);
    private static final String TARGET_ENTITY_TYPE = "SCREEN_ENTRY";
    private static final String CONSUMER_CONTEXT = "screen";
    private static final String CLIENT_TAG = "screen-video";
    private static final String CLIENT_TAG_REPLACE_PREFIX = "screen-replace:";

    /**
     * Local validation exception representing trusted, user-facing validation errors
     * detected by controller-level parameter, payload, and operation-intent constraints.
     */
    public static final class AdminScreenVideoValidationException extends RuntimeException {
        public AdminScreenVideoValidationException(String message) {
            super(message);
        }

        public AdminScreenVideoValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private enum SessionIntentType {
        INITIAL,
        REPLACEMENT
    }

    private record SessionOperationIntent(
            SessionIntentType type,
            UUID replacementExpectedAssetId
    ) {
        boolean isReplacement() {
            return type == SessionIntentType.REPLACEMENT;
        }
    }

    private static SessionOperationIntent parseAndValidateSessionIntent(String clientTag) {
        if (clientTag == null || clientTag.isBlank()) {
            throw new AdminScreenVideoValidationException("Upload session có clientTag không hợp lệ hoặc bị trống.");
        }
        if (CLIENT_TAG.equals(clientTag)) {
            return new SessionOperationIntent(SessionIntentType.INITIAL, null);
        }
        if (clientTag.startsWith(CLIENT_TAG_REPLACE_PREFIX)) {
            String rawUuid = clientTag.substring(CLIENT_TAG_REPLACE_PREFIX.length()).trim();
            if (rawUuid.isEmpty()) {
                throw new AdminScreenVideoValidationException("Upload session có clientTag thay thế video bị thiếu UUID: " + clientTag);
            }
            try {
                UUID expectedAssetId = UUID.fromString(rawUuid);
                return new SessionOperationIntent(SessionIntentType.REPLACEMENT, expectedAssetId);
            } catch (IllegalArgumentException e) {
                throw new AdminScreenVideoValidationException("Upload session có clientTag thay thế video không đúng định dạng UUID: " + clientTag);
            }
        }
        throw new AdminScreenVideoValidationException("Upload session có clientTag không được hỗ trợ: " + clientTag);
    }

    private final GetScreenTitleDetailUseCase getScreenTitleDetailUseCase;
    private final GetScreenEntryDetailUseCase getScreenEntryDetailUseCase;
    private final AttachScreenEntryVideoUseCase attachScreenEntryVideoUseCase;
    private final DetachScreenEntryVideoUseCase detachScreenEntryVideoUseCase;
    private final ReplaceScreenEntryVideoUseCase replaceScreenEntryVideoUseCase;
    private final MediaContract mediaContract;
    private final DiscardScreenEntryVideoUploadUseCase discardScreenEntryVideoUploadUseCase;
    private final ReconcileScreenEntryVideoVisibilityUseCase reconcileScreenEntryVideoVisibilityUseCase;

    public AdminScreenEntryVideoController(
            GetScreenTitleDetailUseCase getScreenTitleDetailUseCase,
            GetScreenEntryDetailUseCase getScreenEntryDetailUseCase,
            AttachScreenEntryVideoUseCase attachScreenEntryVideoUseCase,
            DetachScreenEntryVideoUseCase detachScreenEntryVideoUseCase,
            ReplaceScreenEntryVideoUseCase replaceScreenEntryVideoUseCase,
            MediaContract mediaContract,
            DiscardScreenEntryVideoUploadUseCase discardScreenEntryVideoUploadUseCase,
            ReconcileScreenEntryVideoVisibilityUseCase reconcileScreenEntryVideoVisibilityUseCase
    ) {
        this.getScreenTitleDetailUseCase = Objects.requireNonNull(getScreenTitleDetailUseCase, "GetScreenTitleDetailUseCase cannot be null.");
        this.getScreenEntryDetailUseCase = Objects.requireNonNull(getScreenEntryDetailUseCase, "GetScreenEntryDetailUseCase cannot be null.");
        this.attachScreenEntryVideoUseCase = Objects.requireNonNull(attachScreenEntryVideoUseCase, "AttachScreenEntryVideoUseCase cannot be null.");
        this.detachScreenEntryVideoUseCase = Objects.requireNonNull(detachScreenEntryVideoUseCase, "DetachScreenEntryVideoUseCase cannot be null.");
        this.replaceScreenEntryVideoUseCase = Objects.requireNonNull(replaceScreenEntryVideoUseCase, "ReplaceScreenEntryVideoUseCase cannot be null.");
        this.mediaContract = Objects.requireNonNull(mediaContract, "MediaContract cannot be null.");
        this.discardScreenEntryVideoUploadUseCase = Objects.requireNonNull(discardScreenEntryVideoUploadUseCase, "DiscardScreenEntryVideoUploadUseCase cannot be null.");
        this.reconcileScreenEntryVideoVisibilityUseCase = Objects.requireNonNull(reconcileScreenEntryVideoVisibilityUseCase, "ReconcileScreenEntryVideoVisibilityUseCase cannot be null.");
    }

    /**
     * Allocates and initiates a multipart upload session for this ScreenEntry.
     * Supports explicit replacement initiation when video is already attached.
     */
    @PostMapping(value = "/upload-session", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<VideoUploadSessionView> initiateUploadSession(
            @PathVariable UUID titleId,
            @PathVariable UUID entryId,
            @RequestBody InitiateVideoUploadSessionRequest request,
            Authentication authentication
    ) {
        getScreenTitleDetailUseCase.execute(titleId);
        ScreenEntryDTO entry = getScreenEntryDetailUseCase.execute(titleId, entryId);

        boolean isReplacement = Boolean.TRUE.equals(request.isReplacement());
        String clientTag;

        if (entry.videoMediaAssetId() != null) {
            if (!isReplacement) {
                throw new ScreenEntryVideoAlreadyAttachedException(entryId, entry.videoMediaAssetId());
            }
            if (request.expectedCurrentVideoMediaAssetId() == null) {
                throw new AdminScreenVideoValidationException("expectedCurrentVideoMediaAssetId không được để trống khi thực hiện thay thế video.");
            }
            if (!entry.videoMediaAssetId().equals(request.expectedCurrentVideoMediaAssetId())) {
                throw new ScreenEntryVideoConflictException(
                        entryId,
                        request.expectedCurrentVideoMediaAssetId(),
                        entry.videoMediaAssetId()
                );
            }
            clientTag = CLIENT_TAG_REPLACE_PREFIX + request.expectedCurrentVideoMediaAssetId();
        } else {
            if (isReplacement) {
                throw new ScreenEntryVideoNotAttachedException(entryId);
            }
            clientTag = CLIENT_TAG;
        }

        String principalId = resolvePrincipalId(authentication);

        if (request.originalFilename() == null || !request.originalFilename().toLowerCase().endsWith(".mp4")) {
            throw new AdminScreenVideoValidationException("Chỉ chấp nhận file video có đuôi .mp4.");
        }

        String idempotencyKey = (request.idempotencyKey() != null && !request.idempotencyKey().isBlank())
                ? request.idempotencyKey().trim()
                : "screen-entry-" + entryId + "-" + UUID.randomUUID();

        AllocateUploadSessionRequestDTO allocRequest = new AllocateUploadSessionRequestDTO(
                principalId,
                CONSUMER_CONTEXT,
                TARGET_ENTITY_TYPE,
                entryId,
                idempotencyKey,
                request.declaredSizeBytes(),
                request.originalFilename(),
                clientTag
        );

        MediaUploadSessionResponseDTO session = mediaContract.allocateUploadSession(allocRequest);

        // Validate returned session clientTag matches requested intent (including idempotency-key reuse)
        if (!Objects.equals(session.clientTag(), clientTag)) {
            throw new AdminScreenVideoValidationException(
                    "Upload session đã tồn tại với ý định thao tác không khớp: " + session.clientTag()
            );
        }

        if ("ALLOCATED".equals(session.status())) {
            session = mediaContract.initiateUploadSession(session.sessionId(), principalId);
        }

        VideoUploadSessionView view = new VideoUploadSessionView(
                session.sessionId(),
                session.status(),
                session.declaredSizeBytes(),
                session.partSizeBytes(),
                session.expectedPartCount(),
                session.originalFilename(),
                session.registeredAssetId(),
                session.expiresAt()
        );

        return ResponseEntity.ok(view);
    }

    /**
     * Issues an authorized short-lived presigned PUT upload URL for a specific part number.
     */
    @GetMapping("/upload-session/{sessionId}/parts/{partNumber}/url")
    public ResponseEntity<VideoPartUploadUrlView> getPartUploadUrl(
            @PathVariable UUID titleId,
            @PathVariable UUID entryId,
            @PathVariable UUID sessionId,
            @PathVariable int partNumber,
            Authentication authentication
    ) {
        getScreenTitleDetailUseCase.execute(titleId);
        getScreenEntryDetailUseCase.execute(titleId, entryId);

        String principalId = resolvePrincipalId(authentication);

        MediaUploadSessionResponseDTO session = validateAndGetSessionForEntry(sessionId, entryId, principalId);
        if (!"INITIATED".equals(session.status())) {
            throw new AdminScreenVideoValidationException("Không thể cấp URL upload cho phiên có trạng thái: " + session.status());
        }

        String uploadUrl = mediaContract.generatePresignedPartUploadUrl(sessionId, partNumber, principalId);
        return ResponseEntity.ok(new VideoPartUploadUrlView(partNumber, uploadUrl));
    }

    /**
     * Validates manifest, claims completion intent, completes multipart upload on provider,
     * registers MediaAsset, and attaches or atomically replaces the video asset on the ScreenEntry.
     * <p>
     * Validates persisted operation intent before any Media completion mutation.
     * Supports idempotent replay if the session has already completed and attached to this entry.
     */
    @PostMapping(value = "/upload-session/{sessionId}/complete", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> completeUploadSession(
            @PathVariable UUID titleId,
            @PathVariable UUID entryId,
            @PathVariable UUID sessionId,
            @RequestBody CompleteVideoUploadSessionRequest request,
            Authentication authentication
    ) {
        getScreenTitleDetailUseCase.execute(titleId);
        ScreenEntryDTO entry = getScreenEntryDetailUseCase.execute(titleId, entryId);

        String principalId = resolvePrincipalId(authentication);

        MediaUploadSessionResponseDTO session = validateAndGetSessionForEntry(sessionId, entryId, principalId);

        // STEP 1: Parse and validate immutable persisted session operation intent FIRST
        SessionOperationIntent intent = parseAndValidateSessionIntent(session.clientTag());

        // STEP 2: Valid completed-session idempotent recovery
        if ("COMPLETED".equals(session.status())
                && session.registeredAssetId() != null
                && session.registeredAssetId().equals(entry.videoMediaAssetId())) {
            String playbackUrl = "/admin/screen/titles/" + titleId + "/entries/" + entryId + "/video/content";
            String respStatus = intent.isReplacement() ? "REPLACED" : "COMPLETED";
            return ResponseEntity.ok(new CompleteVideoUploadResponse(
                    session.registeredAssetId(),
                    playbackUrl,
                    respStatus,
                    "Video đã được hoàn tất và gắn vào Screen Entry từ trước (idempotent)."
            ));
        }

        // STEP 3: Validate operation intent BEFORE any Media completion mutation
        try {
            if (intent.isReplacement()) {
                if (entry.videoMediaAssetId() == null) {
                    throw new ScreenEntryVideoNotAttachedException(entryId);
                }
                if (request == null || request.expectedCurrentVideoMediaAssetId() == null) {
                    throw new AdminScreenVideoValidationException("expectedCurrentVideoMediaAssetId không được để trống khi hoàn tất thay thế video.");
                }
                if (!intent.replacementExpectedAssetId().equals(request.expectedCurrentVideoMediaAssetId())) {
                    throw new ScreenEntryVideoConflictException(
                            entryId,
                            request.expectedCurrentVideoMediaAssetId(),
                            intent.replacementExpectedAssetId()
                    );
                }
                if (!entry.videoMediaAssetId().equals(intent.replacementExpectedAssetId())) {
                    throw new ScreenEntryVideoConflictException(
                            entryId,
                            intent.replacementExpectedAssetId(),
                            entry.videoMediaAssetId()
                    );
                }
            } else {
                // Initial upload session: reject if entry already has a video attached
                if (entry.videoMediaAssetId() != null) {
                    throw new ScreenEntryVideoAlreadyAttachedException(entryId, entry.videoMediaAssetId());
                }
                // Reject converting initial upload session to replacement
                if (request != null && request.expectedCurrentVideoMediaAssetId() != null) {
                    throw new AdminScreenVideoValidationException("Upload session ban đầu không thể dùng để thay thế video.");
                }
            }
        } catch (ScreenEntryVideoAlreadyAttachedException ex) {
            log.warn("ScreenEntry video already attached prior to completion for session {} (asset {}): {}",
                    sessionId, session.registeredAssetId(), ex.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ScreenVideoErrorResponse(
                    ex.getErrorCode(),
                    ex.getMessage(),
                    sessionId,
                    session.registeredAssetId()
            ));
        } catch (ScreenEntryVideoConflictException ex) {
            log.warn("ScreenEntry video conflict prior to completion for session {} (asset {}): {}",
                    sessionId, session.registeredAssetId(), ex.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ScreenVideoErrorResponse(
                    ex.getErrorCode(),
                    ex.getMessage(),
                    sessionId,
                    session.registeredAssetId()
            ));
        } catch (ScreenEntryVideoNotAttachedException ex) {
            log.warn("ScreenEntry video not attached for replacement prior to completion for session {} (asset {}): {}",
                    sessionId, session.registeredAssetId(), ex.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ScreenVideoErrorResponse(
                    ex.getErrorCode(),
                    ex.getMessage(),
                    sessionId,
                    session.registeredAssetId()
            ));
        }

        // STEP 4: Provider completion (only when session is not yet COMPLETED)
        if ("INITIATED".equals(session.status())) {
            ClaimCompletionIntentRequestDTO claimRequest = new ClaimCompletionIntentRequestDTO(
                    sessionId,
                    principalId,
                    TARGET_ENTITY_TYPE,
                    entryId,
                    request != null ? request.declaredContentHash() : null,
                    request != null ? request.parts() : null
            );
            session = mediaContract.claimCompletionIntent(claimRequest);
        }

        if ("COMPLETING".equals(session.status())) {
            CompleteMultipartUploadRequestDTO completeRequest = new CompleteMultipartUploadRequestDTO(
                    sessionId,
                    principalId,
                    request != null ? request.parts() : null
            );
            session = mediaContract.completeMultipartUpload(completeRequest);
        } else if (!"COMPLETED".equals(session.status())) {
            throw new AdminScreenVideoValidationException("Trạng thái upload session không cho phép hoàn tất: " + session.status());
        }

        UUID registeredAssetId = session.registeredAssetId();
        if (registeredAssetId == null) {
            throw new IllegalStateException("Hoàn tất upload session không trả về registeredAssetId hợp lệ: " + sessionId);
        }

        String playbackUrl = "/admin/screen/titles/" + titleId + "/entries/" + entryId + "/video/content";

        // STEP 5: Screen entry persistence (with locked-state check inside use-case)
        try {
            if (intent.isReplacement()) {
                replaceScreenEntryVideoUseCase.execute(
                        titleId,
                        entryId,
                        registeredAssetId,
                        request.expectedCurrentVideoMediaAssetId()
                );

                return ResponseEntity.ok(new CompleteVideoUploadResponse(
                        registeredAssetId,
                        playbackUrl,
                        "REPLACED",
                        "Video đã được thay thế thành công trên Screen Entry."
                ));
            }

            attachScreenEntryVideoUseCase.execute(titleId, entryId, registeredAssetId);

            return ResponseEntity.ok(new CompleteVideoUploadResponse(
                    registeredAssetId,
                    playbackUrl,
                    "COMPLETED",
                    "Video đã được tải lên và gán thành công vào Screen Entry."
            ));
        } catch (ScreenEntryVideoAlreadyAttachedException ex) {
            log.warn("ScreenEntry video already attached after upload completion for session {} (asset {}): {}",
                    sessionId, registeredAssetId, ex.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ScreenVideoErrorResponse(
                    ex.getErrorCode(),
                    ex.getMessage(),
                    sessionId,
                    registeredAssetId
            ));
        } catch (ScreenEntryVideoConflictException ex) {
            log.warn("ScreenEntry video conflict after upload completion for session {} (asset {}): {}",
                    sessionId, registeredAssetId, ex.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ScreenVideoErrorResponse(
                    ex.getErrorCode(),
                    ex.getMessage(),
                    sessionId,
                    registeredAssetId
            ));
        } catch (ScreenEntryVideoNotAttachedException ex) {
            log.warn("ScreenEntry video not attached for replacement after upload completion for session {} (asset {}): {}",
                    sessionId, registeredAssetId, ex.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ScreenVideoErrorResponse(
                    ex.getErrorCode(),
                    ex.getMessage(),
                    sessionId,
                    registeredAssetId
            ));
        }
    }

    /**
     * Aborts an active upload session and tombstones the underlying storage allocation.
     */
    @PostMapping(value = "/upload-session/{sessionId}/abort", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> abortUploadSession(
            @PathVariable UUID titleId,
            @PathVariable UUID entryId,
            @PathVariable UUID sessionId,
            Authentication authentication
    ) {
        getScreenTitleDetailUseCase.execute(titleId);
        getScreenEntryDetailUseCase.execute(titleId, entryId);

        String principalId = resolvePrincipalId(authentication);

        MediaUploadSessionResponseDTO session = validateAndGetSessionForEntry(sessionId, entryId, principalId);
        if ("COMPLETED".equals(session.status())) {
            throw new AdminScreenVideoValidationException("Không thể hủy phiên tải lên đã hoàn tất.");
        }
        if ("ABORTED".equals(session.status())) {
            return ResponseEntity.ok(Map.of(
                    "sessionId", session.sessionId(),
                    "status", session.status(),
                    "message", "Phiên tải lên đã được đánh dấu hủy trước đó."
            ));
        }

        MediaUploadSessionResponseDTO aborted = mediaContract.abortUploadSession(sessionId, principalId);
        return ResponseEntity.ok(Map.of(
                "sessionId", aborted.sessionId(),
                "status", aborted.status(),
                "message", "Phiên tải lên đã được hủy an toàn trên hệ thống."
        ));
    }

    /**
     * Guarded discard endpoint for unattached, completed Screen video uploads.
     * Enforces session initiator ownership, session-to-target immutable bindings,
     * exclusive row locks on MediaAsset and ScreenEntry, current-read reference fencing,
     * and delegates soft-delete or returns authorized ALREADY_DISCARDED.
     */
    @PostMapping(
            value = "/upload-session/{sessionId}/discard",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<DiscardVideoUploadResponse> discardUploadSession(
            @PathVariable UUID titleId,
            @PathVariable UUID entryId,
            @PathVariable UUID sessionId,
            @RequestBody(required = false) DiscardVideoUploadSessionRequest request,
            Authentication authentication
    ) {
        if (request == null || request.videoMediaAssetId() == null) {
            throw new AdminScreenVideoValidationException("videoMediaAssetId không được để trống khi thực hiện hủy video đã upload.");
        }
        String authenticatedActor = resolvePrincipalId(authentication);
        UUID videoMediaAssetId = request.videoMediaAssetId();

        DiscardScreenEntryVideoUploadCommand command = new DiscardScreenEntryVideoUploadCommand(
                titleId,
                entryId,
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

    /**
     * Explicit fallback attach endpoint to attach or replace with an existing registered video MediaAsset.
     * Enforces strict provenance validation via a completed upload session tied to this ScreenEntry and authenticated principal.
     */
    @PostMapping(value = "/attach", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> attachExistingVideo(
            @PathVariable UUID titleId,
            @PathVariable UUID entryId,
            @RequestBody Map<String, String> payload,
            Authentication authentication
    ) {
        getScreenTitleDetailUseCase.execute(titleId);
        ScreenEntryDTO entry = getScreenEntryDetailUseCase.execute(titleId, entryId);

        String principalId = resolvePrincipalId(authentication);

        if (payload == null) {
            throw new AdminScreenVideoValidationException("Request payload không được để trống.");
        }

        String rawSessionId = payload.get("sessionId");
        if (rawSessionId == null || rawSessionId.isBlank()) {
            throw new AdminScreenVideoValidationException("sessionId không được để trống.");
        }

        String rawAssetId = payload.get("videoMediaAssetId");
        if (rawAssetId == null || rawAssetId.isBlank()) {
            throw new AdminScreenVideoValidationException("videoMediaAssetId không được để trống.");
        }

        UUID sessionId;
        UUID videoMediaAssetId;
        try {
            sessionId = UUID.fromString(rawSessionId.trim());
        } catch (IllegalArgumentException e) {
            throw new AdminScreenVideoValidationException("sessionId không đúng định dạng UUID: " + rawSessionId);
        }
        try {
            videoMediaAssetId = UUID.fromString(rawAssetId.trim());
        } catch (IllegalArgumentException e) {
            throw new AdminScreenVideoValidationException("videoMediaAssetId không đúng định dạng UUID: " + rawAssetId);
        }

        boolean isReplacement = Boolean.parseBoolean(payload.get("isReplacement"));
        String rawExpectedId = payload.get("expectedCurrentVideoMediaAssetId");
        UUID expectedCurrentVideoMediaAssetId = null;
        if (rawExpectedId != null && !rawExpectedId.isBlank()) {
            try {
                expectedCurrentVideoMediaAssetId = UUID.fromString(rawExpectedId.trim());
            } catch (IllegalArgumentException e) {
                throw new AdminScreenVideoValidationException("expectedCurrentVideoMediaAssetId không đúng định dạng UUID: " + rawExpectedId);
            }
        }

        MediaUploadSessionResponseDTO session = validateAndGetSessionForEntry(sessionId, entryId, principalId);

        if (!"COMPLETED".equals(session.status())) {
            throw new AdminScreenVideoValidationException("Chỉ có thể gắn video từ upload session đã ở trạng thái COMPLETED. Trạng thái hiện tại: " + session.status());
        }

        if (session.registeredAssetId() == null || !session.registeredAssetId().equals(videoMediaAssetId)) {
            throw new AdminScreenVideoValidationException("videoMediaAssetId không khớp với registeredAssetId của upload session: " + sessionId);
        }

        SessionOperationIntent intent = parseAndValidateSessionIntent(session.clientTag());

        try {
            if (entry.videoMediaAssetId() != null) {
                if (entry.videoMediaAssetId().equals(videoMediaAssetId)) {
                    String playbackUrl = "/admin/screen/titles/" + titleId + "/entries/" + entryId + "/video/content";
                    return ResponseEntity.ok(new CompleteVideoUploadResponse(
                            videoMediaAssetId,
                            playbackUrl,
                            "ATTACHED",
                            "Video đã được gắn vào Screen Entry từ trước (idempotent)."
                    ));
                }
                if (!isReplacement || !intent.isReplacement()) {
                    throw new ScreenEntryVideoAlreadyAttachedException(entryId, entry.videoMediaAssetId());
                }
                if (expectedCurrentVideoMediaAssetId == null) {
                    throw new AdminScreenVideoValidationException("expectedCurrentVideoMediaAssetId không được để trống khi thực hiện thay thế video.");
                }
                if (!intent.replacementExpectedAssetId().equals(expectedCurrentVideoMediaAssetId)) {
                    throw new ScreenEntryVideoConflictException(
                            entryId,
                            expectedCurrentVideoMediaAssetId,
                            intent.replacementExpectedAssetId()
                    );
                }
                if (!entry.videoMediaAssetId().equals(intent.replacementExpectedAssetId())) {
                    throw new ScreenEntryVideoConflictException(
                            entryId,
                            intent.replacementExpectedAssetId(),
                            entry.videoMediaAssetId()
                    );
                }

                replaceScreenEntryVideoUseCase.execute(
                        titleId,
                        entryId,
                        videoMediaAssetId,
                        expectedCurrentVideoMediaAssetId
                );

                String playbackUrl = "/admin/screen/titles/" + titleId + "/entries/" + entryId + "/video/content";
                return ResponseEntity.ok(new CompleteVideoUploadResponse(
                        videoMediaAssetId,
                        playbackUrl,
                        "REPLACED",
                        "Đã thay thế video media asset vào Screen Entry thành công."
                ));
            }

            if (intent.isReplacement() || isReplacement) {
                throw new ScreenEntryVideoNotAttachedException(entryId);
            }

            attachScreenEntryVideoUseCase.execute(titleId, entryId, videoMediaAssetId);

            String playbackUrl = "/admin/screen/titles/" + titleId + "/entries/" + entryId + "/video/content";
            return ResponseEntity.ok(new CompleteVideoUploadResponse(
                    videoMediaAssetId,
                    playbackUrl,
                    "ATTACHED",
                    "Đã gán video media asset vào Screen Entry thành công."
            ));
        } catch (ScreenEntryVideoAlreadyAttachedException ex) {
            log.warn("ScreenEntry video already attached during fallback attach for session {} (asset {}): {}",
                    sessionId, videoMediaAssetId, ex.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ScreenVideoErrorResponse(
                    ex.getErrorCode(),
                    ex.getMessage(),
                    sessionId,
                    videoMediaAssetId
            ));
        } catch (ScreenEntryVideoConflictException ex) {
            log.warn("ScreenEntry video conflict during fallback attach for session {} (asset {}): {}",
                    sessionId, videoMediaAssetId, ex.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ScreenVideoErrorResponse(
                    ex.getErrorCode(),
                    ex.getMessage(),
                    sessionId,
                    videoMediaAssetId
            ));
        } catch (ScreenEntryVideoNotAttachedException ex) {
            log.warn("ScreenEntry video not attached during fallback attach for session {} (asset {}): {}",
                    sessionId, videoMediaAssetId, ex.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ScreenVideoErrorResponse(
                    ex.getErrorCode(),
                    ex.getMessage(),
                    sessionId,
                    videoMediaAssetId
            ));
        }
    }

    /**
     * Explicit REST endpoint to detach the currently attached video from this ScreenEntry.
     * Enforces that the entry is not PUBLISHED and validates expected current video to prevent stale overwrites.
     */
    @PostMapping(value = "/detach", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> detachVideo(
            @PathVariable UUID titleId,
            @PathVariable UUID entryId,
            @RequestBody(required = false) Map<String, String> payload,
            Authentication authentication
    ) {
        getScreenTitleDetailUseCase.execute(titleId);
        getScreenEntryDetailUseCase.execute(titleId, entryId);

        resolvePrincipalId(authentication);

        if (payload == null || !payload.containsKey("expectedVideoMediaAssetId")) {
            throw new AdminScreenVideoValidationException("expectedVideoMediaAssetId không được để trống khi thực hiện gỡ bỏ video.");
        }
        String raw = payload.get("expectedVideoMediaAssetId");
        if (raw == null || raw.isBlank()) {
            throw new AdminScreenVideoValidationException("expectedVideoMediaAssetId không được để trống khi thực hiện gỡ bỏ video.");
        }
        UUID expectedVideoMediaAssetId;
        try {
            expectedVideoMediaAssetId = UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new AdminScreenVideoValidationException("expectedVideoMediaAssetId không đúng định dạng UUID: " + raw);
        }

        ScreenEntryDTO detached = detachScreenEntryVideoUseCase.execute(
                titleId,
                entryId,
                expectedVideoMediaAssetId
        );

        return ResponseEntity.ok(Map.of(
                "status", "DETACHED",
                "entryId", detached.id(),
                "message", "Đã gỡ bỏ video khỏi Screen Entry thành công. Video gốc vẫn được bảo toàn an toàn trên hệ thống Media."
        ));
    }

    /**
     * Admin-authorized explicit reconciliation endpoint for an already-PUBLISHED ScreenEntry.
     * Re-reads authoritative Screen and Media metadata and reconciles attached video visibility
     * to PUBLIC without altering entry publishedAt timestamps or republishing the entry.
     */
    @PostMapping(value = "/reconcile", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReconcileScreenVideoVisibilityResponse> reconcileVideoVisibility(
            @PathVariable UUID titleId,
            @PathVariable UUID entryId,
            Authentication authentication
    ) {
        resolvePrincipalId(authentication);
        ReconcileScreenVideoVisibilityResult result =
                reconcileScreenEntryVideoVisibilityUseCase.execute(titleId, entryId);

        return ResponseEntity.ok(new ReconcileScreenVideoVisibilityResponse(
                result.titleId(),
                result.entryId(),
                result.videoMediaAssetId(),
                result.reconciledVisibility(),
                result.outcome().name(),
                result.message()
        ));
    }

    /**
     * Authorized admin streaming endpoint for attached ScreenEntry video binaries.
     * Supports both HTTP GET (full / 206 Partial Content) and HTTP HEAD metadata requests.
     */
    @RequestMapping(
            value = "/content",
            method = {RequestMethod.GET, RequestMethod.HEAD}
    )
    public ResponseEntity<StreamingResponseBody> streamVideoContent(
            @PathVariable UUID titleId,
            @PathVariable UUID entryId,
            HttpServletRequest request
    ) {
        // Scoped asynchronous request timeout for video streaming (30 minutes)
        if (request != null) {
            WebAsyncManager asyncManager = WebAsyncUtils.getAsyncManager(request);
            if (asyncManager != null && asyncManager.getAsyncWebRequest() != null) {
                asyncManager.getAsyncWebRequest().setTimeout(VIDEO_STREAMING_ASYNC_TIMEOUT_MS);
            }
        }

        getScreenTitleDetailUseCase.execute(titleId);
        ScreenEntryDTO entry = getScreenEntryDetailUseCase.execute(titleId, entryId);

        UUID videoAssetId = entry.videoMediaAssetId();
        if (videoAssetId == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        Optional<MediaAssetDetailDTO> detailOpt = mediaContract.getAssetDetail(videoAssetId);
        if (detailOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        MediaAssetDetailDTO detail = detailOpt.get();
        if (detail.status() != MediaAssetStatusDTO.ACTIVE || detail.mediaType() != MediaTypeDTO.VIDEO) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        MediaVersionDTO currentVersion = detail.currentVersion();
        if (currentVersion == null || currentVersion.sizeBytes() <= 0) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        long totalSize = currentVersion.sizeBytes();
        String mimeType = (currentVersion.mimeType() != null && !currentVersion.mimeType().isBlank())
                ? currentVersion.mimeType()
                : "video/mp4";
        String eTag = "\"" + currentVersion.id() + "\"";
        String cacheControl = "private, no-cache, no-store, must-revalidate";

        if (RequestMethod.HEAD.name().equalsIgnoreCase(request.getMethod())) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, mimeType)
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(totalSize))
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CACHE_CONTROL, cacheControl)
                    .header(HttpHeaders.ETAG, eTag)
                    .build();
        }

        if (request != null) {
            request.setAttribute(STREAMING_ACTIVE_ATTR, Boolean.TRUE);
        }

        String rangeHeader = request.getHeader(HttpHeaders.RANGE);
        if (rangeHeader == null || rangeHeader.isBlank()) {
            StreamingResponseBody responseBody = createStreamingResponseBody(
                    entryId,
                    videoAssetId,
                    () -> mediaContract.openStream(videoAssetId)
            );
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, mimeType)
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(totalSize))
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CACHE_CONTROL, cacheControl)
                    .header(HttpHeaders.ETAG, eTag)
                    .body(responseBody);
        }

        RangeResolution range = resolveRange(rangeHeader, totalSize);
        if (range.status() == RangeStatus.UNSATISFIABLE) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + totalSize)
                    .header(HttpHeaders.CACHE_CONTROL, cacheControl)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .build();
        }

        if (range.status() == RangeStatus.PARTIAL) {
            long length = range.length();
            StreamingResponseBody responseBody = createStreamingResponseBody(
                    entryId,
                    videoAssetId,
                    () -> mediaContract.openRangeStream(videoAssetId, range.startInclusive(), length)
            );
            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .header(HttpHeaders.CONTENT_TYPE, mimeType)
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(length))
                    .header(HttpHeaders.CONTENT_RANGE, "bytes " + range.startInclusive() + "-" + range.endInclusive() + "/" + totalSize)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CACHE_CONTROL, cacheControl)
                    .header(HttpHeaders.ETAG, eTag)
                    .body(responseBody);
        }

        StreamingResponseBody responseBody = createStreamingResponseBody(
                entryId,
                videoAssetId,
                () -> mediaContract.openStream(videoAssetId)
        );
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, mimeType)
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(totalSize))
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CACHE_CONTROL, cacheControl)
                .header(HttpHeaders.ETAG, eTag)
                .body(responseBody);
    }

    private static final int STREAM_BUFFER_SIZE = 32768; // 32 KiB standard streaming buffer
    private static final long VIDEO_STREAMING_ASYNC_TIMEOUT_MS = 1_800_000L; // 30 minutes
    static final String STREAMING_ACTIVE_ATTR = AdminScreenEntryVideoController.class.getName() + ".STREAMING_ACTIVE";

    static boolean isStreamingActive(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        return Boolean.TRUE.equals(request.getAttribute(STREAMING_ACTIVE_ATTR))
                || request.isAsyncStarted();
    }

    @FunctionalInterface
    private interface StreamSupplier {
        MediaAssetStreamDTO open() throws Exception;
    }

    /**
     * Internal marker exception identifying that an I/O failure originated from the upstream storage provider
     * (B2/S3/MediaContract) during stream opening or reading, and must not be misinterpreted as a downstream client disconnect.
     */
    public static class StorageStreamException extends IOException {
        public StorageStreamException(String message, Throwable cause) {
            super(message + (cause != null && cause.getMessage() != null ? ": " + cause.getMessage() : ""), cause);
        }
    }

    static boolean isUpstreamStorageFailure(Throwable t) {
        Throwable current = t;
        while (current != null) {
            if (current instanceof StorageStreamException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    static boolean isClientDisconnect(Throwable t) {
        Throwable current = t;
        while (current != null) {
            String className = current.getClass().getName();
            String msg = (current.getMessage() != null) ? current.getMessage().toLowerCase() : "";
            if (className.endsWith("ClientAbortException")
                    || msg.contains("broken pipe")
                    || msg.contains("connection reset")
                    || msg.contains("connection was aborted")
                    || msg.contains("an established connection was aborted")
                    || msg.contains("software caused connection abort")
                    || msg.contains("socket write error")
                    || msg.contains("connection closed")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private StreamingResponseBody createStreamingResponseBody(
            UUID entryId,
            UUID videoAssetId,
            StreamSupplier streamSupplier
    ) {
        return outputStream -> {
            MediaAssetStreamDTO streamDto;
            try {
                streamDto = streamSupplier.open();
            } catch (Exception openEx) {
                log.error("Storage stream open failure for entry {} (asset {}): {}",
                        entryId, videoAssetId, openEx.getMessage(), openEx);
                throw new StorageStreamException("Storage stream open failure", openEx);
            }

            if (streamDto == null) {
                log.error("Storage stream open returned null for entry {} (asset {})", entryId, videoAssetId);
                throw new StorageStreamException("Storage stream is unavailable (null stream)", null);
            }

            try (streamDto) {
                if (streamDto.contentStream() == null) {
                    log.error("Storage stream content is null for entry {} (asset {})", entryId, videoAssetId);
                    throw new StorageStreamException("Storage stream content is unavailable", null);
                }
                InputStream in = streamDto.contentStream();
                byte[] buffer = new byte[STREAM_BUFFER_SIZE];
                int bytesRead;
                while (true) {
                    try {
                        bytesRead = in.read(buffer);
                    } catch (IOException readEx) {
                        log.error("Storage stream read failure for entry {} (asset {}): {}",
                                entryId, videoAssetId, readEx.getMessage(), readEx);
                        throw new StorageStreamException("Storage stream read failure", readEx);
                    } catch (RuntimeException readRuntimeEx) {
                        log.error("Storage stream runtime read failure for entry {} (asset {}): {}",
                                entryId, videoAssetId, readRuntimeEx.getMessage(), readRuntimeEx);
                        throw new StorageStreamException("Storage stream runtime read failure", readRuntimeEx);
                    }

                    if (bytesRead == -1) {
                        break;
                    }

                    try {
                        outputStream.write(buffer, 0, bytesRead);
                    } catch (IOException writeEx) {
                        if (isClientDisconnect(writeEx)) {
                            log.debug("Client disconnected while writing video stream for entry {}: {}",
                                    entryId, writeEx.getMessage());
                            return;
                        }
                        log.warn("Downstream client write failure for entry {} (asset {}): {}",
                                entryId, videoAssetId, writeEx.getMessage());
                        throw writeEx;
                    }
                }
            } catch (StorageStreamException e) {
                throw e;
            } catch (IOException ioEx) {
                throw ioEx;
            } catch (RuntimeException runtimeEx) {
                throw runtimeEx;
            }
        };
    }

    private enum RangeStatus {
        IGNORED,
        PARTIAL,
        UNSATISFIABLE
    }

    private record RangeResolution(
            RangeStatus status,
            long startInclusive,
            long endInclusive
    ) {
        private static RangeResolution ignored() {
            return new RangeResolution(RangeStatus.IGNORED, 0, 0);
        }

        private static RangeResolution partial(long startInclusive, long endInclusive) {
            return new RangeResolution(RangeStatus.PARTIAL, startInclusive, endInclusive);
        }

        private static RangeResolution unsatisfiable() {
            return new RangeResolution(RangeStatus.UNSATISFIABLE, 0, 0);
        }

        private long length() {
            return endInclusive - startInclusive + 1;
        }
    }

    private static RangeResolution resolveRange(String value, long fullSize) {
        if (value == null || value.isBlank() || fullSize < 0) {
            return RangeResolution.ignored();
        }
        int equalsIndex = value.indexOf('=');
        if (equalsIndex < 0) {
            return RangeResolution.ignored();
        }
        String unit = value.substring(0, equalsIndex).trim();
        if (!"bytes".equalsIgnoreCase(unit)) {
            return RangeResolution.ignored();
        }

        String rangeSpec = value.substring(equalsIndex + 1).trim();
        if (rangeSpec.indexOf(',') >= 0) {
            return RangeResolution.ignored();
        }
        if (fullSize == 0) {
            return RangeResolution.unsatisfiable();
        }

        int hyphenIndex = rangeSpec.indexOf('-');
        if (hyphenIndex < 0 || hyphenIndex != rangeSpec.lastIndexOf('-')) {
            return RangeResolution.unsatisfiable();
        }

        String startPart = rangeSpec.substring(0, hyphenIndex).trim();
        String endPart = rangeSpec.substring(hyphenIndex + 1).trim();
        try {
            if (startPart.isEmpty()) {
                long suffixLength = Long.parseLong(endPart);
                if (suffixLength <= 0) {
                    return RangeResolution.unsatisfiable();
                }
                long selectedLength = Math.min(suffixLength, fullSize);
                return RangeResolution.partial(fullSize - selectedLength, fullSize - 1);
            }

            long startInclusive = Long.parseLong(startPart);
            if (startInclusive < 0 || startInclusive >= fullSize) {
                return RangeResolution.unsatisfiable();
            }

            long endInclusive = endPart.isEmpty()
                    ? fullSize - 1
                    : Long.parseLong(endPart);
            if (endInclusive < startInclusive) {
                return RangeResolution.unsatisfiable();
            }
            return RangeResolution.partial(startInclusive, Math.min(endInclusive, fullSize - 1));
        } catch (NumberFormatException e) {
            return RangeResolution.unsatisfiable();
        }
    }

    @ExceptionHandler(MediaAssetNotFoundException.class)
    public ResponseEntity<Void> handleMediaAssetNotFound(MediaAssetNotFoundException ex) {
        log.warn("Media asset not found during video streaming: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @ExceptionHandler(AccessDeniedException.class)
    public void handleAccessDenied(AccessDeniedException ex) {
        throw ex;
    }

    @ExceptionHandler(ScreenEntryVideoConflictException.class)
    public ResponseEntity<ScreenVideoErrorResponse> handleConflict(ScreenEntryVideoConflictException ex) {
        log.warn("Conflict in Screen Video Controller: {} - {}", ex.getErrorCode(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ScreenVideoErrorResponse(
                ex.getErrorCode(),
                ex.getMessage()
        ));
    }

    @ExceptionHandler(com.universe.screen.application.exceptions.ScreenTitleVideoConflictException.class)
    public ResponseEntity<ScreenVideoErrorResponse> handleTitleVideoConflict(com.universe.screen.application.exceptions.ScreenTitleVideoConflictException ex) {
        log.warn("Title video dynamic conflict in Screen Video Controller: {} - {}", ex.getErrorCode(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ScreenVideoErrorResponse(
                ex.getErrorCode(),
                ex.getMessage()
        ));
    }

    @ExceptionHandler(com.universe.screen.application.exceptions.ScreenVideoProvenanceException.class)
    public ResponseEntity<ScreenVideoErrorResponse> handleProvenanceViolation(com.universe.screen.application.exceptions.ScreenVideoProvenanceException ex) {
        log.warn("Provenance violation in Screen Video Controller: {} - {}", ex.getErrorCode(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ScreenVideoErrorResponse(
                ex.getErrorCode(),
                ex.getMessage()
        ));
    }

    @ExceptionHandler(ScreenVideoAlreadyReferencedException.class)
    public ResponseEntity<ScreenVideoErrorResponse> handleAlreadyReferenced(ScreenVideoAlreadyReferencedException ex) {
        log.warn("Reference conflict in Screen Video Controller: {} - {}", ex.getErrorCode(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ScreenVideoErrorResponse(
                ex.getErrorCode(),
                ex.getMessage()
        ));
    }

    @ExceptionHandler(ScreenVideoDiscardAccessDeniedException.class)
    public ResponseEntity<ScreenVideoErrorResponse> handleDiscardAccessDenied(ScreenVideoDiscardAccessDeniedException ex) {
        log.warn("Access denied in Screen Video Controller: {} - {}", ex.getErrorCode(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ScreenVideoErrorResponse(
                ex.getErrorCode(),
                ex.getMessage()
        ));
    }

    @ExceptionHandler(BaseApplicationException.class)
    public ResponseEntity<ScreenVideoErrorResponse> handleBaseApplicationException(BaseApplicationException ex) {
        if (ex instanceof ScreenEntryNotFoundException
                || ex instanceof ScreenTitleNotFoundException
                || ex instanceof ScreenUploadSessionNotFoundException
                || ex instanceof ScreenMediaAssetNotFoundException) {
            log.warn("Not found in Screen Video Controller: {} - {}", ex.getErrorCode(), ex.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ScreenVideoErrorResponse(
                    ex.getErrorCode(),
                    ex.getMessage()
            ));
        }

        if (ex instanceof ScreenVideoAlreadyReferencedException
                || ex instanceof ScreenEntryVideoConflictException
                || ex instanceof com.universe.screen.application.exceptions.ScreenTitleVideoConflictException) {
            log.warn("Conflict in Screen Video Controller: {} - {}", ex.getErrorCode(), ex.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ScreenVideoErrorResponse(
                    ex.getErrorCode(),
                    ex.getMessage()
            ));
        }

        if (ex instanceof ScreenVideoDiscardAccessDeniedException) {
            log.warn("Access denied in Screen Video Controller: {} - {}", ex.getErrorCode(), ex.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ScreenVideoErrorResponse(
                    ex.getErrorCode(),
                    ex.getMessage()
            ));
        }

        if (ex instanceof ScreenVideoDiscardValidationException
                || ex instanceof InvalidScreenMediaAssetTypeException
                || ex instanceof ScreenEntryVideoAlreadyAttachedException
                || ex instanceof ScreenEntryVideoNotAttachedException
                || ex instanceof com.universe.screen.application.exceptions.ScreenVideoProvenanceException) {
            log.warn("Business validation error in Screen Video Controller: {} - {}", ex.getErrorCode(), ex.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ScreenVideoErrorResponse(
                    ex.getErrorCode(),
                    ex.getMessage()
            ));
        }

        log.error("Unknown application exception in Screen Video Controller: {} - {}", ex.getErrorCode(), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ScreenVideoErrorResponse(
                "INTERNAL_SERVER_ERROR",
                "Đã xảy ra lỗi máy chủ không mong muốn trong quá trình xử lý video."
        ));
    }

    @ExceptionHandler(AdminScreenVideoValidationException.class)
    public ResponseEntity<ScreenVideoErrorResponse> handleValidation(AdminScreenVideoValidationException ex) {
        log.warn("Validation error in Screen Video Controller: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ScreenVideoErrorResponse(
                "BAD_REQUEST",
                ex.getMessage()
        ));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ScreenVideoErrorResponse> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        log.warn("Malformed or unreadable HTTP message in Screen Video Controller: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ScreenVideoErrorResponse(
                "BAD_REQUEST",
                "Dữ liệu yêu cầu không hợp lệ hoặc sai định dạng JSON."
        ));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ScreenVideoErrorResponse> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("Method argument type mismatch in Screen Video Controller: property={}", ex.getName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ScreenVideoErrorResponse(
                "BAD_REQUEST",
                "Tham số đường dẫn hoặc tham số truy vấn không đúng định dạng."
        ));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ScreenVideoErrorResponse> handleHttpMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        log.warn("Unsupported media type in Screen Video Controller: {}", ex.getContentType());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(new ScreenVideoErrorResponse(
                "UNSUPPORTED_MEDIA_TYPE",
                "Content-Type không được hỗ trợ. Yêu cầu application/json."
        ));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ScreenVideoErrorResponse> handleHttpRequestMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("HTTP method not supported in Screen Video Controller: {}", ex.getMethod());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(new ScreenVideoErrorResponse(
                "METHOD_NOT_ALLOWED",
                "Phương thức HTTP không được hỗ trợ cho đường dẫn này."
        ));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ScreenVideoErrorResponse> handleMissingServletRequestParameter(MissingServletRequestParameterException ex) {
        log.warn("Missing servlet request parameter in Screen Video Controller: {}", ex.getParameterName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ScreenVideoErrorResponse(
                "BAD_REQUEST",
                "Thiếu tham số yêu cầu bắt buộc: " + ex.getParameterName()
        ));
    }

    @ExceptionHandler(UploadSessionInitiationConflictException.class)
    public ResponseEntity<ScreenVideoErrorResponse> handleInitiationConflict(UploadSessionInitiationConflictException ex) {
        log.warn("Initiation conflict in Screen Video Controller: session={}, error={}", ex.getSessionId(), ex.getErrorCode());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ScreenVideoErrorResponse(
                ex.getErrorCode(),
                "Phiên tải lên đang được khởi tạo bởi một tiến trình khác. Vui lòng thử lại sau giây lát."
        ));
    }

    @ExceptionHandler(UploadSessionUnconfirmedInitiationException.class)
    public ResponseEntity<ScreenVideoErrorResponse> handleUnconfirmedInitiation(UploadSessionUnconfirmedInitiationException ex) {
        log.warn("Unconfirmed initiation claim in Screen Video Controller: session={}, error={}", ex.getSessionId(), ex.getErrorCode());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ScreenVideoErrorResponse(
                ex.getErrorCode(),
                "Phiên tải lên tồn tại yêu cầu khởi tạo trước đó chưa xác nhận kết quả. Cần thực hiện đối soát an toàn trước khi có thể thử lại."
        ));
    }

    @ExceptionHandler(StorageInitiationRejectedException.class)
    public ResponseEntity<ScreenVideoErrorResponse> handleStorageInitiationRejected(StorageInitiationRejectedException ex) {
        log.error("Storage provider rejected multipart initiation in Screen Video Controller: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ScreenVideoErrorResponse(
                ex.getErrorCode(),
                "Nhà cung cấp lưu trữ từ chối yêu cầu khởi tạo tải lên nhiều phần do cấu hình hoặc quyền truy cập không hợp lệ. Vui lòng kiểm tra cấu hình lưu trữ."
        ));
    }

    @ExceptionHandler(StorageAmbiguousInitiationException.class)
    public ResponseEntity<ScreenVideoErrorResponse> handleStorageAmbiguousInitiation(StorageAmbiguousInitiationException ex) {
        log.error("Storage provider initiation timed out or returned ambiguous error in Screen Video Controller: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(new ScreenVideoErrorResponse(
                ex.getErrorCode(),
                "Yêu cầu khởi tạo tải lên nhiều phần tới nhà cung cấp lưu trữ chưa được xác nhận hoặc bị gián đoạn. Cần đối soát trạng thái trước khi thử lại."
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ScreenVideoErrorResponse> handleUnexpected(
            Exception ex,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws Exception {
        if (ex instanceof AccessDeniedException) {
            throw ex;
        }

        boolean isStreamingRequest = request != null && request.getRequestURI() != null && request.getRequestURI().endsWith("/content");
        boolean streamingActive = isStreamingRequest && isStreamingActive(request);

        // 1. Unrelated non-streaming operations or pre-streaming setup failures must NEVER be treated as client disconnects
        if (!streamingActive) {
            log.error("Unexpected error in Screen Video Controller for {}: {}",
                    request != null ? request.getRequestURI() : "unknown", ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ScreenVideoErrorResponse(
                            "INTERNAL_SERVER_ERROR",
                            "Đã xảy ra lỗi máy chủ không mong muốn trong quá trình xử lý video."
                    ));
        }

        // 2. Upstream storage failure on streaming request (B2 stream open / read)
        if (isUpstreamStorageFailure(ex)) {
            if (response != null && response.isCommitted()) {
                log.error("Upstream storage streaming failure after response commit for {}: {}",
                        request.getRequestURI(), ex.getMessage(), ex);
                return null;
            }
            log.error("Upstream storage streaming failure for {}: {}",
                    request.getRequestURI(), ex.getMessage(), ex);
            throw ex;
        }

        // 3. Downstream client disconnect during streaming
        if (isClientDisconnect(ex)) {
            log.debug("Client disconnected during video streaming request {}: {}",
                    request.getRequestURI(), ex.getMessage());
            return null;
        }

        // 4. Asynchronous request timeout during streaming
        if (ex instanceof AsyncRequestTimeoutException) {
            if (response != null && response.isCommitted()) {
                log.debug("Async request timeout after response commit for {}: {}",
                        request.getRequestURI(), ex.getMessage());
                return null;
            }
            log.warn("Async request timeout before response commit for {}: {}",
                    request.getRequestURI(), ex.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        // 5. Any other unexpected failure after response commit: do not attempt JSON write
        if (response != null && response.isCommitted()) {
            log.error("Streaming failure after response commit for {}: {}",
                    request.getRequestURI(), ex.getMessage(), ex);
            return null;
        }

        // 6. Any other uncommitted streaming failure: rethrow so container/MockMvc propagates cleanly
        log.error("Streaming failure during video content request for {}: {}",
                request.getRequestURI(), ex.getMessage(), ex);
        throw ex;
    }

    private MediaUploadSessionResponseDTO validateAndGetSessionForEntry(
            UUID sessionId,
            UUID entryId,
            String principalId
    ) {
        MediaUploadSessionResponseDTO session = mediaContract.getUploadSession(sessionId)
                .orElseThrow(() -> new AdminScreenVideoValidationException("Không tìm thấy upload session: " + sessionId));

        if (!CONSUMER_CONTEXT.equals(session.consumerContext())
                || !TARGET_ENTITY_TYPE.equals(session.targetEntityType())
                || !entryId.equals(session.targetEntityId())) {
            throw new AdminScreenVideoValidationException("Upload session không thuộc về ScreenEntry hiện tại.");
        }

        if (!principalId.equals(session.initiatorPrincipalId())) {
            throw new AccessDeniedException("Người dùng không có quyền truy cập upload session này.");
        }

        return session;
    }

    private static String resolvePrincipalId(Authentication authentication) {
        if (authentication != null && authentication.getName() != null && !authentication.getName().isBlank()) {
            return authentication.getName().trim();
        }
        throw new AccessDeniedException("Yêu cầu xác thực tài khoản hợp lệ.");
    }
}
