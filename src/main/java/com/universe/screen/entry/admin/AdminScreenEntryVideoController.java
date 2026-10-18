package com.universe.screen.entry.admin;

import com.universe.media.contracts.dto.AllocateUploadSessionRequestDTO;
import com.universe.media.contracts.dto.ClaimCompletionIntentRequestDTO;
import com.universe.media.contracts.dto.CompleteMultipartUploadRequestDTO;
import com.universe.media.contracts.dto.MediaUploadSessionResponseDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.screen.application.dto.ScreenEntryDTO;
import com.universe.screen.application.exceptions.ScreenEntryNotFoundException;
import com.universe.screen.application.exceptions.ScreenEntryVideoAlreadyAttachedException;
import com.universe.screen.application.exceptions.ScreenEntryVideoConflictException;
import com.universe.screen.application.exceptions.ScreenEntryVideoNotAttachedException;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.screen.application.usecase.AttachScreenEntryVideoUseCase;
import com.universe.screen.application.usecase.DetachScreenEntryVideoUseCase;
import com.universe.screen.application.usecase.GetScreenEntryDetailUseCase;
import com.universe.screen.application.usecase.GetScreenTitleDetailUseCase;
import com.universe.screen.application.usecase.ReplaceScreenEntryVideoUseCase;
import com.universe.screen.entry.admin.dto.CompleteVideoUploadResponse;
import com.universe.screen.entry.admin.dto.CompleteVideoUploadSessionRequest;
import com.universe.screen.entry.admin.dto.InitiateVideoUploadSessionRequest;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.contracts.dto.MediaAssetDetailDTO;
import com.universe.media.contracts.dto.MediaAssetStatusDTO;
import com.universe.media.contracts.dto.MediaAssetStreamDTO;
import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaVersionDTO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;

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

    public AdminScreenEntryVideoController(
            GetScreenTitleDetailUseCase getScreenTitleDetailUseCase,
            GetScreenEntryDetailUseCase getScreenEntryDetailUseCase,
            AttachScreenEntryVideoUseCase attachScreenEntryVideoUseCase,
            DetachScreenEntryVideoUseCase detachScreenEntryVideoUseCase,
            ReplaceScreenEntryVideoUseCase replaceScreenEntryVideoUseCase,
            MediaContract mediaContract
    ) {
        this.getScreenTitleDetailUseCase = Objects.requireNonNull(getScreenTitleDetailUseCase, "GetScreenTitleDetailUseCase cannot be null.");
        this.getScreenEntryDetailUseCase = Objects.requireNonNull(getScreenEntryDetailUseCase, "GetScreenEntryDetailUseCase cannot be null.");
        this.attachScreenEntryVideoUseCase = Objects.requireNonNull(attachScreenEntryVideoUseCase, "AttachScreenEntryVideoUseCase cannot be null.");
        this.detachScreenEntryVideoUseCase = Objects.requireNonNull(detachScreenEntryVideoUseCase, "DetachScreenEntryVideoUseCase cannot be null.");
        this.replaceScreenEntryVideoUseCase = Objects.requireNonNull(replaceScreenEntryVideoUseCase, "ReplaceScreenEntryVideoUseCase cannot be null.");
        this.mediaContract = Objects.requireNonNull(mediaContract, "MediaContract cannot be null.");
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
                "Video đã được tải lên Backblaze B2 và gán thành công vào Screen Entry."
        ));
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
     * Explicit fallback attach endpoint to attach or replace with an existing registered video MediaAsset.
     * Enforces strict provenance validation via a completed upload session tied to this ScreenEntry and authenticated principal.
     */
    @PostMapping(value = "/attach", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
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

    private static final int STREAM_BUFFER_SIZE = 8192;

    @FunctionalInterface
    private interface StreamSupplier {
        MediaAssetStreamDTO open() throws Exception;
    }

    private static boolean isClientDisconnect(Throwable t) {
        Throwable current = t;
        while (current != null) {
            String className = current.getClass().getName();
            String msg = (current.getMessage() != null) ? current.getMessage().toLowerCase() : "";
            if (className.endsWith("ClientAbortException")
                    || msg.contains("broken pipe")
                    || msg.contains("connection reset")
                    || msg.contains("connection was aborted")
                    || msg.contains("an established connection was aborted")) {
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
            try (MediaAssetStreamDTO streamDto = streamSupplier.open()) {
                if (streamDto == null || streamDto.contentStream() == null) {
                    return;
                }
                InputStream in = streamDto.contentStream();
                byte[] buffer = new byte[STREAM_BUFFER_SIZE];
                int bytesRead;
                while (true) {
                    try {
                        bytesRead = in.read(buffer);
                    } catch (IOException e) {
                        log.error("Storage stream read failure for entry {} (asset {}): {}",
                                entryId, videoAssetId, e.getMessage());
                        throw e;
                    }

                    if (bytesRead == -1) {
                        break;
                    }

                    try {
                        outputStream.write(buffer, 0, bytesRead);
                    } catch (IOException e) {
                        if (isClientDisconnect(e)) {
                            log.debug("Client disconnected while writing video stream for entry {}: {}",
                                    entryId, e.getMessage());
                            return;
                        }
                        log.warn("Downstream client write failure for entry {} (asset {}): {}",
                                entryId, videoAssetId, e.getMessage());
                        throw e;
                    }
                }
            } catch (IOException ioEx) {
                throw ioEx;
            } catch (RuntimeException runtimeEx) {
                log.error("Streaming runtime error for entry {} (asset {}): {}",
                        entryId, videoAssetId, runtimeEx.getMessage());
                throw runtimeEx;
            } catch (Exception ex) {
                log.error("Streaming error for entry {} (asset {}): {}",
                        entryId, videoAssetId, ex.getMessage());
                throw new IOException("Lỗi khi mở hoặc truyền dữ liệu video cho asset " + videoAssetId, ex);
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
    public ResponseEntity<Map<String, String>> handleConflict(ScreenEntryVideoConflictException ex) {
        log.warn("Conflict in Screen Video Controller: {} - {}", ex.getErrorCode(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", ex.getErrorCode(),
                "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(BaseApplicationException.class)
    public ResponseEntity<Map<String, String>> handleBaseApplicationException(BaseApplicationException ex) {
        log.warn("Application exception in Screen Video Controller: {} - {}", ex.getErrorCode(), ex.getMessage());
        HttpStatus status = (ex instanceof ScreenEntryNotFoundException || ex instanceof ScreenTitleNotFoundException)
                ? HttpStatus.NOT_FOUND
                : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of(
                "error", ex.getErrorCode(),
                "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(AdminScreenVideoValidationException.class)
    public ResponseEntity<Map<String, String>> handleValidation(AdminScreenVideoValidationException ex) {
        log.warn("Validation error in Screen Video Controller: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "BAD_REQUEST",
                "message", ex.getMessage()
        ));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class, RuntimeException.class})
    public ResponseEntity<Map<String, String>> handleUnexpected(RuntimeException ex) {
        if (ex instanceof AccessDeniedException) {
            throw (AccessDeniedException) ex;
        }
        log.error("Unexpected error in Screen Video Controller: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "BAD_REQUEST",
                "message", "Yêu cầu không hợp lệ hoặc đã xảy ra lỗi trong quá trình xử lý video."
        ));
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
