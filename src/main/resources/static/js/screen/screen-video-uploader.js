/**
 * KiemLai Universe — Screen Video Upload Lifecycle Coordinator (MS-08E2)
 *
 * Responsibilities:
 * - Encapsulates upload attempts in immutable attempt contexts.
 * - Enforces stable per-attempt idempotency keys for network retry safety.
 * - Protects against async race conditions: stale callbacks from older cancelled
 *   or failed attempts are discarded and cannot mutate newer attempts or the UI.
 * - Uses AbortController to abort in-flight fetch requests.
 * - Requires typed ABORTED response from backend before marking an attempt settled.
 * - Prevents initiating a new upload attempt while a previous attempt is active/unsettled.
 * - Preserves failure phase (failedAtPhase) to distinguish INITIATING, UPLOADING_PARTS, and COMPLETING.
 * - Distinguishes pre-dispatch cancellation from ambiguous in-flight dispatch cancellation.
 * - Fails closed if cryptographic SHA-256 capability is unavailable.
 */
(function (root, factory) {
    'use strict';
    if (typeof module === 'object' && typeof module.exports === 'object') {
        module.exports = factory();
    } else {
        const exports = factory();
        root.ScreenVideoUploader = exports;
        if (!root.KiemLai) {
            root.KiemLai = {};
        }
        root.KiemLai.ScreenVideoUploader = exports;
    }
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    const PART_SIZE = 26214400; // 25 MiB fixed part size
    const MAX_BROWSER_SMOKE_SIZE = 524288000; // 500 MiB limit for browser smoke slice

    const UploadPhase = {
        IDLE: 'IDLE',
        HASHING: 'HASHING',
        INITIATING: 'INITIATING',
        UPLOADING_PARTS: 'UPLOADING_PARTS',
        COMPLETING: 'COMPLETING',
        COMPLETED: 'COMPLETED',
        ABORTED: 'ABORTED',
        FAILED: 'FAILED'
    };

    /**
     * Generates a stable unique identifier string.
     */
    function generateId(prefix) {
        if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
            return (prefix ? prefix + '-' : '') + crypto.randomUUID();
        }
        return (prefix ? prefix + '-' : '') + Date.now().toString(36) + '-' + Math.random().toString(36).substring(2, 9);
    }

    /**
     * Creates an immutable upload attempt context.
     */
    function createAttempt(file, entryId, options) {
        options = options || {};
        const attemptId = options.id || generateId('attempt');
        const idempotencyKey = options.idempotencyKey || ('screen-entry-' + entryId + '-' + generateId('upload'));
        const abortController = options.abortController || (typeof AbortController !== 'undefined' ? new AbortController() : null);

        return {
            id: attemptId,
            entryId: entryId,
            file: file,
            idempotencyKey: idempotencyKey,
            generation: options.generation || 1,
            sessionId: options.sessionId || null,
            phase: options.phase || UploadPhase.IDLE,
            failedAtPhase: null,
            initiationDispatched: false,
            abortController: abortController,
            isCancelled: false,
            isSettled: false,
            registeredAssetId: null,
            playbackUrl: null,
            error: null,
            partsManifest: [],
            isReplacement: options.isReplacement || false,
            expectedCurrentVideoMediaAssetId: options.expectedCurrentVideoMediaAssetId || null
        };
    }

    /**
     * Upload Coordinator managing the active attempt lifecycle.
     */
    function UploadCoordinator(options) {
        this.options = options || {};
        this.currentAttempt = null;
    }

    UploadCoordinator.prototype.getCurrentAttempt = function () {
        return this.currentAttempt;
    };

    /**
     * Checks if a callback or promise continuation is stale or cancelled.
     * When generation is provided, fences out continuations from older request generations.
     */
    UploadCoordinator.prototype.isStale = function (attempt, generation) {
        if (!attempt) return true;
        if (this.currentAttempt !== attempt) return true;
        if (attempt.isCancelled) return true;
        if (generation !== undefined && generation !== null && attempt.generation !== generation) return true;
        return false;
    };

    /**
     * Calculates SHA-256 hash using crypto.subtle or provided custom crypto function.
     * FAILS CLOSED: Never fabricates synthetic hashes if Web Crypto is unavailable.
     */
    UploadCoordinator.prototype.calculateSha256 = async function (file, cryptoFn) {
        if (cryptoFn) {
            return await cryptoFn(file);
        }
        if (typeof crypto !== 'undefined' && crypto.subtle && typeof crypto.subtle.digest === 'function' && typeof file.arrayBuffer === 'function') {
            const buffer = await file.arrayBuffer();
            const digest = await crypto.subtle.digest('SHA-256', buffer);
            const hashArray = Array.from(new Uint8Array(digest));
            return hashArray.map(b => b.toString(16).padStart(2, '0')).join('');
        }
        throw new Error('Web Crypto API (crypto.subtle) is not supported in this environment. SHA-256 calculation failed closed.');
    };

    /**
     * Starts a new upload attempt. Fails if a previous attempt is not locally settled.
     */
    UploadCoordinator.prototype.startAttempt = async function (params) {
        const file = params.file;
        const titleId = params.titleId;
        const entryId = params.entryId;
        const callbacks = params.callbacks || {};
        const fetchFn = params.fetchFn || (typeof fetch !== 'undefined' ? fetch : null);
        const cryptoFn = params.cryptoFn;
        const getCsrfHeaders = params.getCsrfHeaders || function (h) { return h || {}; };

        if (!fetchFn) {
            throw new Error('Fetch implementation is not available.');
        }

        // Prevent concurrent uploads: reject if existing attempt is active and not settled
        if (this.currentAttempt && !this.currentAttempt.isSettled) {
            const unsettledErr = new Error('Một phiên tải lên trước đó vẫn đang hoạt động hoặc chưa được giải quyết an toàn. Vui lòng hủy hoặc giải quyết phiên hiện tại trước.');
            unsettledErr.code = 'PREVIOUS_ATTEMPT_UNSETTLED';
            unsettledErr.unsettledAttempt = this.currentAttempt;
            if (callbacks.onError) {
                callbacks.onError(unsettledErr, this.currentAttempt);
            }
            throw unsettledErr;
        }

        // Fail-closed validation for replacement mode: require non-empty expectedCurrentVideoMediaAssetId
        if (params.isReplacement && (!params.expectedCurrentVideoMediaAssetId || typeof params.expectedCurrentVideoMediaAssetId !== 'string' || !params.expectedCurrentVideoMediaAssetId.trim())) {
            const missingIdErr = new Error('expectedCurrentVideoMediaAssetId không được để trống khi thực hiện thay thế video.');
            missingIdErr.code = 'MISSING_EXPECTED_ASSET_ID';
            if (callbacks.onError) {
                callbacks.onError(missingIdErr, null);
            }
            throw missingIdErr;
        }

        // Create new attempt context
        const attempt = createAttempt(file, entryId, {
            idempotencyKey: params.idempotencyKey,
            abortController: params.abortController,
            isReplacement: params.isReplacement,
            expectedCurrentVideoMediaAssetId: params.expectedCurrentVideoMediaAssetId
        });
        this.currentAttempt = attempt;
        const currentGen = attempt.generation;

        if (callbacks.onAttemptCreated) {
            callbacks.onAttemptCreated(attempt);
        }

        try {
            // STEP 1: Hashing
            attempt.phase = UploadPhase.HASHING;
            if (callbacks.onProgress && !this.isStale(attempt, currentGen)) {
                callbacks.onProgress(5, 'Đang tính toán mã băm SHA-256...', attempt);
            }

            const contentHash = await this.calculateSha256(file, cryptoFn);
            if (this.isStale(attempt, currentGen)) return attempt;

            // STEP 2: Initiate upload session with stable idempotencyKey
            await this.performInitiation(attempt, titleId, entryId, contentHash, callbacks, fetchFn, getCsrfHeaders);
            if (this.isStale(attempt, currentGen)) return attempt;

            // STEP 3: Upload chunks
            await this.performPartsUpload(attempt, titleId, entryId, callbacks, fetchFn, getCsrfHeaders);
            if (this.isStale(attempt, currentGen)) return attempt;

            // STEP 4: Complete multipart upload & attach
            await this.performCompletion(attempt, titleId, entryId, contentHash, callbacks, fetchFn, getCsrfHeaders);
            return attempt;

        } catch (err) {
            if (this.isStale(attempt, currentGen)) {
                // Silently ignore errors from stale or cancelled attempts
                return attempt;
            }
            attempt.error = err.message;
            attempt.failedAtPhase = attempt.phase;
            attempt.phase = UploadPhase.FAILED;

            // Only settled if failure happened strictly before initiation was dispatched
            if (!attempt.initiationDispatched && !attempt.sessionId) {
                attempt.isSettled = true;
            } else {
                attempt.isSettled = false;
            }

            if (callbacks.onError) {
                callbacks.onError(err, attempt);
            }
            return attempt;
        }
    };

    /**
     * Performs session allocation & initiation with stable idempotencyKey.
     * Fences out responses or errors from superseded request generations.
     */
    UploadCoordinator.prototype.performInitiation = async function (attempt, titleId, entryId, contentHash, callbacks, fetchFn, getCsrfHeaders) {
        const requestGen = attempt.generation;
        attempt.phase = UploadPhase.INITIATING;
        if (callbacks.onProgress && !this.isStale(attempt, requestGen)) {
            callbacks.onProgress(10, 'Đang khởi tạo phiên tải lên multipart (Khóa: ' + attempt.idempotencyKey + ')...', attempt);
        }

        const signal = attempt.abortController ? attempt.abortController.signal : undefined;
        
        // Mark that initiation POST is being dispatched to backend
        attempt.initiationDispatched = true;

        let initResp;
        try {
            const initBody = {
                declaredSizeBytes: attempt.file.size,
                originalFilename: attempt.file.name,
                idempotencyKey: attempt.idempotencyKey
            };
            if (attempt.isReplacement) {
                initBody.isReplacement = true;
                if (attempt.expectedCurrentVideoMediaAssetId) {
                    initBody.expectedCurrentVideoMediaAssetId = attempt.expectedCurrentVideoMediaAssetId;
                }
            }

            initResp = await fetchFn(`/admin/screen/titles/${titleId}/entries/${entryId}/video/upload-session`, {
                method: 'POST',
                headers: getCsrfHeaders({ 'Content-Type': 'application/json' }),
                body: JSON.stringify(initBody),
                signal: signal
            });
        } catch (netErr) {
            if (this.isStale(attempt, requestGen)) {
                // Stale generation from a superseded retry: discard completely
                if (attempt.generation !== requestGen) {
                    return;
                }
                // Cancelled after dispatch but before response received in the CURRENT generation
                if (attempt.isCancelled && !attempt.sessionId) {
                    attempt.phase = UploadPhase.FAILED;
                    attempt.failedAtPhase = UploadPhase.INITIATING;
                    attempt.isSettled = false;
                    const cancelUncertainErr = new Error('Yêu cầu tải lên đã được hủy cục bộ nhưng yêu cầu khởi tạo đã gửi tới máy chủ. Trạng thái phiên chưa xác định. Khóa phiên được bảo lưu: ' + attempt.idempotencyKey);
                    cancelUncertainErr.isUncertainInitiation = true;
                    cancelUncertainErr.idempotencyKey = attempt.idempotencyKey;
                    attempt.error = cancelUncertainErr.message;
                    if (callbacks.onError) {
                        callbacks.onError(cancelUncertainErr, attempt);
                    }
                }
                return;
            }
            const lostRespErr = new Error('Lỗi kết nối khi gửi yêu cầu khởi tạo: ' + netErr.message + '. Phản hồi từ máy chủ bị gián đoạn. Idempotency Key được bảo toàn: ' + attempt.idempotencyKey);
            lostRespErr.isLostResponse = true;
            lostRespErr.idempotencyKey = attempt.idempotencyKey;
            throw lostRespErr;
        }

        // FENCE OUT STALE GENERATIONS IMMEDIATELY (Before JSON Parsing)
        if (this.isStale(attempt, requestGen)) {
            // Discard completely if superseded by a newer generation
            if (attempt.generation !== requestGen) {
                return;
            }
            // Cancelled attempt that received a late initiation response in current generation: retain session for cleanup
            if (attempt.isCancelled && !attempt.sessionId && initResp && initResp.ok) {
                try {
                    const data = await initResp.json();
                    // Recheck generation and attempt identity immediately AFTER async JSON body read
                    if (this.currentAttempt !== attempt || attempt.generation !== requestGen) {
                        return;
                    }
                    if (data && data.sessionId && !attempt.sessionId) {
                        attempt.sessionId = data.sessionId;
                        if (Number.isInteger(data.expectedPartCount) && data.expectedPartCount > 0) {
                            attempt.totalParts = data.expectedPartCount;
                        }
                        if (typeof data.partSizeBytes === 'number' && data.partSizeBytes > 0) {
                            attempt.partSizeBytes = data.partSizeBytes;
                        }
                    }
                } catch (e) { /* ignore */ }
            }
            return;
        }

        let sessionData = null;
        if (initResp.ok) {
            try {
                sessionData = await initResp.json();
            } catch (jsonErr) {
                if (this.currentAttempt !== attempt || attempt.generation !== requestGen) {
                    return;
                }
                throw new Error('Khởi tạo upload session thất bại: Phản hồi không phải định dạng JSON hợp lệ: ' + jsonErr.message);
            }

            // CRITICAL: Recheck generation and cancellation AFTER asynchronous JSON body read and BEFORE mutating attempt state!
            if (this.currentAttempt !== attempt || attempt.generation !== requestGen) {
                return;
            }
            if (attempt.isCancelled) {
                // Preserved for remote cleanup only if still in matching generation and no session is set
                if (sessionData && sessionData.sessionId && !attempt.sessionId) {
                    attempt.sessionId = sessionData.sessionId;
                    if (Number.isInteger(sessionData.expectedPartCount) && sessionData.expectedPartCount > 0) {
                        attempt.totalParts = sessionData.expectedPartCount;
                    }
                    if (typeof sessionData.partSizeBytes === 'number' && sessionData.partSizeBytes > 0) {
                        attempt.partSizeBytes = sessionData.partSizeBytes;
                    }
                }
                return;
            }

            // Reject malformed successful initiation responses safely (missing sessionId, invalid expectedPartCount or invalid partSizeBytes)
            if (!sessionData || 
                !sessionData.sessionId || 
                typeof sessionData.sessionId !== 'string' || 
                sessionData.sessionId.trim() === '' ||
                !Number.isInteger(sessionData.expectedPartCount) || 
                sessionData.expectedPartCount <= 0 ||
                typeof sessionData.partSizeBytes !== 'number' || 
                sessionData.partSizeBytes <= 0) {
                throw new Error('Khởi tạo upload session thất bại: Phản hồi từ máy chủ không hợp lệ hoặc thiếu thông tin phiên.');
            }

            // Safe mutation of attempt state for active matching generation
            if (!attempt.sessionId) {
                attempt.sessionId = sessionData.sessionId;
            }
            attempt.totalParts = sessionData.expectedPartCount;
            attempt.partSizeBytes = sessionData.partSizeBytes;
        } else {
            let errMsg = 'HTTP ' + initResp.status;
            try {
                const errData = await initResp.json();
                if (errData && errData.message) errMsg = errData.message;
            } catch (e) { /* ignore JSON parse error on HTTP errors */ }

            // Recheck generation and cancellation after async error body read
            if (this.currentAttempt !== attempt || attempt.generation !== requestGen) {
                return;
            }
            if (attempt.isCancelled) {
                return;
            }
            throw new Error('Khởi tạo upload session thất bại: ' + errMsg);
        }

        if (this.isStale(attempt, requestGen)) {
            return;
        }

        if (callbacks.onSessionInitiated && !this.isStale(attempt, requestGen)) {
            callbacks.onSessionInitiated(sessionData, attempt);
        }
    };

    /**
     * Retries initiation for the active attempt using the exact same idempotencyKey.
     * Uses fresh cancellation context (AbortController) and advances request generation counter
     * to fence out any pending or delayed responses from previous attempts.
     */
    UploadCoordinator.prototype.retryInitiation = async function (params) {
        const attempt = this.currentAttempt;
        if (!attempt) {
            throw new Error('Không có phiên tải lên nào để thử lại khởi tạo.');
        }
        if (attempt.sessionId) {
            throw new Error('Phiên tải lên đã có session ID (' + attempt.sessionId + '). Không cần khởi tạo lại.');
        }

        params = params || {};
        const titleId = params.titleId;
        const entryId = params.entryId;
        const callbacks = params.callbacks || {};
        const fetchFn = params.fetchFn || (typeof fetch !== 'undefined' ? fetch : null);
        const cryptoFn = params.cryptoFn;
        const getCsrfHeaders = params.getCsrfHeaders || function (h) { return h || {}; };

        // 1. Advance request generation to fence out any in-flight requests from prior attempt
        attempt.generation = (attempt.generation || 0) + 1;
        const currentGen = attempt.generation;

        // 2. Allocate fresh AbortController context so retry is not pre-aborted
        attempt.abortController = params.abortController || (typeof AbortController !== 'undefined' ? new AbortController() : null);

        // 3. Reset cancellation and failure state for the new attempt generation
        attempt.isCancelled = false;
        attempt.error = null;
        attempt.failedAtPhase = null;

        try {
            attempt.phase = UploadPhase.HASHING;
            const contentHash = await this.calculateSha256(attempt.file, cryptoFn);
            if (this.isStale(attempt, currentGen)) return attempt;

            await this.performInitiation(attempt, titleId, entryId, contentHash, callbacks, fetchFn, getCsrfHeaders);
            if (this.isStale(attempt, currentGen)) return attempt;

            await this.performPartsUpload(attempt, titleId, entryId, callbacks, fetchFn, getCsrfHeaders);
            if (this.isStale(attempt, currentGen)) return attempt;

            await this.performCompletion(attempt, titleId, entryId, contentHash, callbacks, fetchFn, getCsrfHeaders);
            return attempt;
        } catch (err) {
            if (this.isStale(attempt, currentGen)) return attempt;
            attempt.error = err.message;
            attempt.failedAtPhase = attempt.phase;
            attempt.phase = UploadPhase.FAILED;
            if (!attempt.initiationDispatched && !attempt.sessionId) {
                attempt.isSettled = true;
            } else {
                attempt.isSettled = false;
            }
            if (callbacks.onError) {
                callbacks.onError(err, attempt);
            }
            return attempt;
        }
    };

    /**
     * Uploads each chunk to B2 via presigned URLs.
     */
    UploadCoordinator.prototype.performPartsUpload = async function (attempt, titleId, entryId, callbacks, fetchFn, getCsrfHeaders) {
        attempt.phase = UploadPhase.UPLOADING_PARTS;
        const totalParts = attempt.totalParts;
        const partSize = attempt.partSizeBytes || PART_SIZE;
        const file = attempt.file;
        const signal = attempt.abortController ? attempt.abortController.signal : undefined;
        const currentGen = attempt.generation;

        attempt.partsManifest = [];

        for (let partNumber = 1; partNumber <= totalParts; partNumber++) {
            if (this.isStale(attempt, currentGen)) return;

            const start = (partNumber - 1) * partSize;
            const end = Math.min(start + partSize, file.size);
            const chunk = file.slice(start, end);

            if (callbacks.onProgress && !this.isStale(attempt, currentGen)) {
                callbacks.onProgress(
                    10 + Math.round(((partNumber - 1) / totalParts) * 70),
                    'Đang lấy URL tải lên cho phần ' + partNumber + ' / ' + totalParts + '...',
                    attempt
                );
            }

            const urlResp = await fetchFn(`/admin/screen/titles/${titleId}/entries/${entryId}/video/upload-session/${attempt.sessionId}/parts/${partNumber}/url`, {
                headers: getCsrfHeaders(),
                signal: signal
            });

            if (this.isStale(attempt, currentGen)) return;
            if (!urlResp.ok) {
                throw new Error('Không thể lấy URL presigned cho phần ' + partNumber + ' (HTTP ' + urlResp.status + ').');
            }

            const urlData = await urlResp.json();
            if (this.isStale(attempt, currentGen)) return;
            const presignedUrl = urlData.uploadUrl;

            if (callbacks.onProgress && !this.isStale(attempt, currentGen)) {
                callbacks.onProgress(
                    10 + Math.round(((partNumber - 0.5) / totalParts) * 70),
                    'Đang truyền dữ liệu phần ' + partNumber + ' / ' + totalParts + ' lên Backblaze B2...',
                    attempt
                );
            }

            let putResp;
            try {
                putResp = await fetchFn(presignedUrl, {
                    method: 'PUT',
                    body: chunk,
                    signal: signal
                });
            } catch (netErr) {
                if (this.isStale(attempt, currentGen)) return;
                throw new Error('Lỗi kết nối tới Backblaze B2 ở phần ' + partNumber + ': ' + netErr.message);
            }

            if (this.isStale(attempt, currentGen)) return;
            if (!putResp.ok) {
                throw new Error('Backblaze B2 từ chối phần ' + partNumber + ': HTTP ' + putResp.status);
            }

            let rawETag = null;
            if (putResp.headers && typeof putResp.headers.get === 'function') {
                rawETag = putResp.headers.get('ETag');
            }
            if (!rawETag) {
                throw new Error('Backblaze B2 không trả về header ETag cho phần ' + partNumber + '.');
            }

            attempt.partsManifest.push({
                partNumber: partNumber,
                sizeBytes: chunk.size,
                eTag: rawETag.trim()
            });
        }
    };

    /**
     * Completes multipart upload on backend & attaches to ScreenEntry.
     */
    UploadCoordinator.prototype.performCompletion = async function (attempt, titleId, entryId, contentHash, callbacks, fetchFn, getCsrfHeaders) {
        const currentGen = attempt.generation;
        attempt.phase = UploadPhase.COMPLETING;
        if (callbacks.onProgress && !this.isStale(attempt, currentGen)) {
            callbacks.onProgress(85, 'Đang xác thực và hoàn tất multipart trên Backblaze B2...', attempt);
        }

        const signal = attempt.abortController ? attempt.abortController.signal : undefined;
        let completeResp;
        try {
            const completeBody = {
                declaredContentHash: contentHash,
                parts: attempt.partsManifest
            };
            if (attempt.expectedCurrentVideoMediaAssetId) {
                completeBody.expectedCurrentVideoMediaAssetId = attempt.expectedCurrentVideoMediaAssetId;
            }

            completeResp = await fetchFn(`/admin/screen/titles/${titleId}/entries/${entryId}/video/upload-session/${attempt.sessionId}/complete`, {
                method: 'POST',
                headers: getCsrfHeaders({ 'Content-Type': 'application/json' }),
                body: JSON.stringify(completeBody),
                signal: signal
            });
        } catch (netErr) {
            if (this.isStale(attempt, currentGen)) return;
            throw new Error('Lỗi kết nối khi gửi yêu cầu hoàn tất phiên tải lên: ' + netErr.message);
        }

        if (this.isStale(attempt, currentGen)) return;
        if (!completeResp.ok) {
            let errMsg = 'HTTP ' + completeResp.status;
            try {
                const errData = await completeResp.json();
                if (errData && errData.message) errMsg = errData.message;
            } catch (e) { /* ignore parse error */ }
            throw new Error('Hoàn tất upload session thất bại: ' + errMsg);
        }

        const completeData = await completeResp.json();
        if (this.isStale(attempt, currentGen)) return;

        attempt.phase = UploadPhase.COMPLETED;
        attempt.isSettled = true;
        attempt.registeredAssetId = completeData.videoMediaAssetId;
        attempt.playbackUrl = completeData.playbackUrl;

        if (callbacks.onProgress && !this.isStale(attempt, currentGen)) {
            const successMsg = completeData.status === 'REPLACED'
                    ? 'Tải lên và thay thế video thành công!'
                    : 'Tải lên và gắn video thành công!';
            callbacks.onProgress(100, successMsg, attempt);
        }
        if (callbacks.onSuccess && !this.isStale(attempt, currentGen)) {
            callbacks.onSuccess(completeData, attempt);
        }
    };

    /**
     * Aborts the active attempt safely.
     * Cancels in-flight fetch immediately, and requires typed ABORTED confirmation from server.
     */
    UploadCoordinator.prototype.abortAttempt = async function (params) {
        const attempt = this.currentAttempt;
        if (!attempt) return;

        const titleId = params.titleId;
        const entryId = params.entryId;
        const callbacks = params.callbacks || {};
        const fetchFn = params.fetchFn || (typeof fetch !== 'undefined' ? fetch : null);
        const getCsrfHeaders = params.getCsrfHeaders || function (h) { return h || {}; };

        // Mark attempt cancelled immediately to prevent pending promises from updating state or UI
        attempt.isCancelled = true;

        // Abort in-flight fetch requests via AbortController
        if (attempt.abortController && typeof attempt.abortController.abort === 'function') {
            try {
                attempt.abortController.abort();
            } catch (e) { /* ignore */ }
        }

        // Case A: Initiation was NEVER dispatched (e.g. cancelled during hashing or pre-initiation)
        if (!attempt.initiationDispatched && !attempt.sessionId) {
            attempt.phase = UploadPhase.ABORTED;
            attempt.failedAtPhase = null;
            attempt.isSettled = true;
            if (callbacks.onAborted) {
                callbacks.onAborted({
                    sessionId: null,
                    status: 'ABORTED',
                    message: 'Đã hủy thành công trước khi gửi yêu cầu khởi tạo tới máy chủ.'
                }, attempt);
            }
            return;
        }

        // Case B: Initiation WAS dispatched, but sessionId has not been acknowledged/confirmed yet
        if (attempt.initiationDispatched && !attempt.sessionId) {
            // Ambiguous server state! The request reached or may have reached the server.
            // We MUST NOT mark it settled or safely ABORTED.
            attempt.phase = UploadPhase.FAILED;
            attempt.failedAtPhase = UploadPhase.INITIATING;
            attempt.isSettled = false;
            const uncertainErr = new Error('Yêu cầu khởi tạo đã được gửi tới máy chủ trước khi hủy nhưng phản hồi chưa được xác nhận. Trạng thái phiên chưa rõ ràng. Khóa phiên được giữ lại: ' + attempt.idempotencyKey + '. Không tự động tạo phiên mới.');
            uncertainErr.isUncertainInitiation = true;
            uncertainErr.idempotencyKey = attempt.idempotencyKey;
            attempt.error = uncertainErr.message;
            if (callbacks.onError) {
                callbacks.onError(uncertainErr, attempt);
            }
            return;
        }

        // Case C: Session exists on server -> call backend /abort endpoint
        try {
            const resp = await fetchFn(`/admin/screen/titles/${titleId}/entries/${entryId}/video/upload-session/${attempt.sessionId}/abort`, {
                method: 'POST',
                headers: getCsrfHeaders({ 'Content-Type': 'application/json' })
            });

            const data = await resp.json().catch(() => null);

            if (!resp.ok) {
                const errMsg = (data && data.message) ? data.message : ('HTTP ' + resp.status);
                // On failure, DO NOT settle attempt and DO NOT clear sessionId!
                attempt.isSettled = false;
                const err = new Error('Yêu cầu hủy không thành công: ' + errMsg + '. Session ID: ' + attempt.sessionId);
                err.sessionId = attempt.sessionId;
                if (callbacks.onError) {
                    callbacks.onError(err, attempt);
                }
                return;
            }

            // Verify typed response status ABORTED from backend
            if (data && data.status === 'ABORTED') {
                attempt.phase = UploadPhase.ABORTED;
                attempt.isSettled = true;
                if (callbacks.onAborted) {
                    callbacks.onAborted(data, attempt);
                }
            } else {
                // Ambiguous provider status
                attempt.isSettled = false;
                const ambigErr = new Error('Phản hồi hủy chưa được xác nhận là ABORTED (Trạng thái: ' + (data ? data.status : 'N/A') + '). Session ID: ' + attempt.sessionId);
                if (callbacks.onError) {
                    callbacks.onError(ambigErr, attempt);
                }
            }
        } catch (netErr) {
            // Network failure during abort: preserve sessionId, do not settle attempt
            attempt.isSettled = false;
            const netAbortErr = new Error('Lỗi mạng khi gửi yêu cầu hủy: ' + netErr.message + '. Session ID: ' + attempt.sessionId);
            netAbortErr.sessionId = attempt.sessionId;
            if (callbacks.onError) {
                callbacks.onError(netAbortErr, attempt);
            }
        }
    };

    /**
     * Clears settled attempt so a clean new attempt can be created.
     */
    UploadCoordinator.prototype.dismissSettledAttempt = function () {
        if (this.currentAttempt && this.currentAttempt.isSettled) {
            this.currentAttempt = null;
            return true;
        }
        return false;
    };

    return {
        PART_SIZE: PART_SIZE,
        MAX_BROWSER_SMOKE_SIZE: MAX_BROWSER_SMOKE_SIZE,
        UploadPhase: UploadPhase,
        generateId: generateId,
        createAttempt: createAttempt,
        UploadCoordinator: UploadCoordinator
    };
});
