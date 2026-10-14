package com.universe.screen.entry.admin;

import com.universe.media.contracts.dto.AllocateUploadSessionRequestDTO;
import com.universe.media.contracts.dto.ClaimCompletionIntentRequestDTO;
import com.universe.media.contracts.dto.CompleteMultipartUploadRequestDTO;
import com.universe.media.contracts.dto.MediaUploadSessionResponseDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.screen.application.dto.ScreenEntryDTO;
import com.universe.screen.application.exceptions.ScreenEntryNotFoundException;
import com.universe.screen.application.exceptions.ScreenEntryVideoAlreadyAttachedException;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.screen.application.usecase.AttachScreenEntryVideoUseCase;
import com.universe.screen.application.usecase.GetScreenEntryDetailUseCase;
import com.universe.screen.application.usecase.GetScreenTitleDetailUseCase;
import com.universe.screen.entry.admin.dto.CompleteVideoUploadResponse;
import com.universe.screen.entry.admin.dto.CompleteVideoUploadSessionRequest;
import com.universe.screen.entry.admin.dto.InitiateVideoUploadSessionRequest;
import com.universe.screen.entry.admin.dto.VideoPartUploadUrlView;
import com.universe.screen.entry.admin.dto.VideoUploadSessionView;
import com.universe.shared.exceptions.BaseApplicationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * REST controller orchestrating browser-to-B2 multipart video upload sessions and attachment to ScreenEntry targets.
 */
@RestController
@RequestMapping("/admin/screen/titles/{titleId}/entries/{entryId}/video")
public class AdminScreenEntryVideoController {

    private static final Logger log = LoggerFactory.getLogger(AdminScreenEntryVideoController.class);
    private static final String TARGET_ENTITY_TYPE = "SCREEN_ENTRY";
    private static final String CONSUMER_CONTEXT = "screen";
    private static final String CLIENT_TAG = "screen-video";

    private final GetScreenTitleDetailUseCase getScreenTitleDetailUseCase;
    private final GetScreenEntryDetailUseCase getScreenEntryDetailUseCase;
    private final AttachScreenEntryVideoUseCase attachScreenEntryVideoUseCase;
    private final MediaContract mediaContract;

    public AdminScreenEntryVideoController(
            GetScreenTitleDetailUseCase getScreenTitleDetailUseCase,
            GetScreenEntryDetailUseCase getScreenEntryDetailUseCase,
            AttachScreenEntryVideoUseCase attachScreenEntryVideoUseCase,
            MediaContract mediaContract
    ) {
        this.getScreenTitleDetailUseCase = Objects.requireNonNull(getScreenTitleDetailUseCase, "GetScreenTitleDetailUseCase cannot be null.");
        this.getScreenEntryDetailUseCase = Objects.requireNonNull(getScreenEntryDetailUseCase, "GetScreenEntryDetailUseCase cannot be null.");
        this.attachScreenEntryVideoUseCase = Objects.requireNonNull(attachScreenEntryVideoUseCase, "AttachScreenEntryVideoUseCase cannot be null.");
        this.mediaContract = Objects.requireNonNull(mediaContract, "MediaContract cannot be null.");
    }

    /**
     * Allocates and initiates a multipart upload session for this ScreenEntry.
     */
    @PostMapping("/upload-session")
    public ResponseEntity<VideoUploadSessionView> initiateUploadSession(
            @PathVariable UUID titleId,
            @PathVariable UUID entryId,
            @RequestBody InitiateVideoUploadSessionRequest request,
            Authentication authentication
    ) {
        getScreenTitleDetailUseCase.execute(titleId);
        ScreenEntryDTO entry = getScreenEntryDetailUseCase.execute(titleId, entryId);

        if (entry.videoMediaAssetId() != null) {
            throw new ScreenEntryVideoAlreadyAttachedException(entryId, entry.videoMediaAssetId());
        }

        String principalId = resolvePrincipalId(authentication);

        if (request.originalFilename() == null || !request.originalFilename().toLowerCase().endsWith(".mp4")) {
            throw new IllegalArgumentException("Chỉ chấp nhận file video có đuôi .mp4.");
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
                CLIENT_TAG
        );

        MediaUploadSessionResponseDTO session = mediaContract.allocateUploadSession(allocRequest);

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
            throw new IllegalStateException("Không thể cấp URL upload cho phiên có trạng thái: " + session.status());
        }

        String uploadUrl = mediaContract.generatePresignedPartUploadUrl(sessionId, partNumber, principalId);
        return ResponseEntity.ok(new VideoPartUploadUrlView(partNumber, uploadUrl));
    }

    /**
     * Validates manifest, claims completion intent, completes multipart upload on provider,
     * registers MediaAsset, and attaches the resulting video asset to the ScreenEntry.
     * <p>
     * Supports idempotent replay if the session has already completed and attached to this entry.
     */
    @PostMapping("/upload-session/{sessionId}/complete")
    public ResponseEntity<CompleteVideoUploadResponse> completeUploadSession(
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

        // Case 1: Same completed session and same registered asset already attached -> idempotent success
        if (entry.videoMediaAssetId() != null) {
            if ("COMPLETED".equals(session.status())
                    && session.registeredAssetId() != null
                    && entry.videoMediaAssetId().equals(session.registeredAssetId())) {
                String playbackUrl = "/media/assets/" + session.registeredAssetId() + "/content";
                return ResponseEntity.ok(new CompleteVideoUploadResponse(
                        session.registeredAssetId(),
                        playbackUrl,
                        "COMPLETED",
                        "Video đã được hoàn tất và gắn vào Screen Entry từ trước (idempotent)."
                ));
            }
            // Case 2: Different existing asset already attached -> reject
            throw new ScreenEntryVideoAlreadyAttachedException(entryId, entry.videoMediaAssetId());
        }

        // Case 3 & 4: Entry does not have video attached yet
        if ("INITIATED".equals(session.status())) {
            ClaimCompletionIntentRequestDTO claimRequest = new ClaimCompletionIntentRequestDTO(
                    sessionId,
                    principalId,
                    TARGET_ENTITY_TYPE,
                    entryId,
                    request.declaredContentHash(),
                    request.parts()
            );
            session = mediaContract.claimCompletionIntent(claimRequest);
        }

        if ("COMPLETING".equals(session.status())) {
            CompleteMultipartUploadRequestDTO completeRequest = new CompleteMultipartUploadRequestDTO(
                    sessionId,
                    principalId,
                    request.parts()
            );
            session = mediaContract.completeMultipartUpload(completeRequest);
        } else if (!"COMPLETED".equals(session.status())) {
            throw new IllegalStateException("Trạng thái upload session không cho phép hoàn tất: " + session.status());
        }

        UUID registeredAssetId = session.registeredAssetId();
        if (registeredAssetId == null) {
            throw new IllegalStateException("Hoàn tất upload session không trả về registeredAssetId hợp lệ: " + sessionId);
        }

        // Attach video to ScreenEntry in durable transaction
        attachScreenEntryVideoUseCase.execute(titleId, entryId, registeredAssetId);

        String playbackUrl = "/media/assets/" + registeredAssetId + "/content";

        return ResponseEntity.ok(new CompleteVideoUploadResponse(
                registeredAssetId,
                playbackUrl,
                "COMPLETED",
                "Video đã được tải lên Backblaze B2 và gán thành công vào Screen Entry."
        ));
    }

    /**
     * Aborts an active upload session and tombstones the underlying storage allocation.
     */
    @PostMapping("/upload-session/{sessionId}/abort")
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
            throw new IllegalStateException("Không thể hủy phiên tải lên đã hoàn tất.");
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
     * Explicit fallback attach endpoint to attach an existing registered video MediaAsset.
     * Enforces strict provenance validation via a completed upload session tied to this ScreenEntry and authenticated principal.
     */
    @PostMapping("/attach")
    public ResponseEntity<CompleteVideoUploadResponse> attachExistingVideo(
            @PathVariable UUID titleId,
            @PathVariable UUID entryId,
            @RequestBody Map<String, String> payload,
            Authentication authentication
    ) {
        getScreenTitleDetailUseCase.execute(titleId);
        ScreenEntryDTO entry = getScreenEntryDetailUseCase.execute(titleId, entryId);

        String principalId = resolvePrincipalId(authentication);

        if (payload == null) {
            throw new IllegalArgumentException("Request payload không được để trống.");
        }

        String rawSessionId = payload.get("sessionId");
        if (rawSessionId == null || rawSessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId không được để trống.");
        }

        String rawAssetId = payload.get("videoMediaAssetId");
        if (rawAssetId == null || rawAssetId.isBlank()) {
            throw new IllegalArgumentException("videoMediaAssetId không được để trống.");
        }

        UUID sessionId;
        UUID videoMediaAssetId;
        try {
            sessionId = UUID.fromString(rawSessionId.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("sessionId không đúng định dạng UUID: " + rawSessionId);
        }
        try {
            videoMediaAssetId = UUID.fromString(rawAssetId.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("videoMediaAssetId không đúng định dạng UUID: " + rawAssetId);
        }

        MediaUploadSessionResponseDTO session = validateAndGetSessionForEntry(sessionId, entryId, principalId);

        if (!"COMPLETED".equals(session.status())) {
            throw new IllegalStateException("Chỉ có thể gắn video từ upload session đã ở trạng thái COMPLETED. Trạng thái hiện tại: " + session.status());
        }

        if (session.registeredAssetId() == null || !session.registeredAssetId().equals(videoMediaAssetId)) {
            throw new IllegalArgumentException("videoMediaAssetId không khớp với registeredAssetId của upload session: " + sessionId);
        }

        if (entry.videoMediaAssetId() != null) {
            if (entry.videoMediaAssetId().equals(videoMediaAssetId)) {
                String playbackUrl = "/media/assets/" + videoMediaAssetId + "/content";
                return ResponseEntity.ok(new CompleteVideoUploadResponse(
                        videoMediaAssetId,
                        playbackUrl,
                        "ATTACHED",
                        "Video đã được gắn vào Screen Entry từ trước (idempotent)."
                ));
            }
            throw new ScreenEntryVideoAlreadyAttachedException(entryId, entry.videoMediaAssetId());
        }

        attachScreenEntryVideoUseCase.execute(titleId, entryId, videoMediaAssetId);

        String playbackUrl = "/media/assets/" + videoMediaAssetId + "/content";
        return ResponseEntity.ok(new CompleteVideoUploadResponse(
                videoMediaAssetId,
                playbackUrl,
                "ATTACHED",
                "Đã gán video media asset vào Screen Entry thành công."
        ));
    }

    @ExceptionHandler(BaseApplicationException.class)
    public ResponseEntity<Map<String, String>> handleBaseApplicationException(BaseApplicationException ex) {
        log.warn("Application exception in Screen Video Controller: {} - {}", ex.getErrorCode(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", ex.getErrorCode(),
                "message", ex.getMessage()
        ));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> handleBadState(RuntimeException ex) {
        log.warn("Bad request/state in Screen Video Controller: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "BAD_REQUEST",
                "message", ex.getMessage()
        ));
    }

    private MediaUploadSessionResponseDTO validateAndGetSessionForEntry(
            UUID sessionId,
            UUID entryId,
            String principalId
    ) {
        MediaUploadSessionResponseDTO session = mediaContract.getUploadSession(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy upload session: " + sessionId));

        if (!CONSUMER_CONTEXT.equals(session.consumerContext())
                || !TARGET_ENTITY_TYPE.equals(session.targetEntityType())
                || !entryId.equals(session.targetEntityId())) {
            throw new IllegalStateException("Upload session không thuộc về ScreenEntry hiện tại.");
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
