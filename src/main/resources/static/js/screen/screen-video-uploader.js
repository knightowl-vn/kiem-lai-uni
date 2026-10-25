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
    const MAX_VIDEO_SIZE_BYTES = 53687091200; // 50 GiB authoritative Media limit (50 * 1024 * 1024 * 1024)
    const MAX_BROWSER_SMOKE_SIZE = MAX_VIDEO_SIZE_BYTES; // Deprecated alias for backwards compatibility
    const HASH_CHUNK_SIZE = 2097152; // 2 MiB slice size for bounded memory hashing
    const HASH_NAVIGATION_CANCELLED = 'HASH_NAVIGATION_CANCELLED';
    const HASH_ABORTED = 'HASH_ABORTED';

    const K_SHA256 = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5,
        0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
        0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
        0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
        0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
        0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3,
        0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
        0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
        0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
    ];

    /**
     * Incremental SHA-256 implementation conforming to FIPS PUB 180-4.
     * Accurately supports 64-bit length padding for inputs exceeding 512 MiB up to 50 GiB.
     */
    function IncrementalSha256() {
        this.h = new Int32Array([
            0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
            0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
        ]);
        this.buffer = new Uint8Array(64);
        this.bufferLength = 0;
        this.totalBytes = 0;
        this.w = new Int32Array(64);
        this.finalized = false;
    }

    IncrementalSha256.prototype._processBlock = function (block, offset) {
        const w = this.w;
        for (let i = 0; i < 16; i++) {
            const idx = offset + (i << 2);
            w[i] = ((block[idx] << 24) | (block[idx + 1] << 16) | (block[idx + 2] << 8) | block[idx + 3]) | 0;
        }
        for (let i = 16; i < 64; i++) {
            const w15 = w[i - 15];
            const s0 = ((w15 >>> 7) | (w15 << 25)) ^ ((w15 >>> 18) | (w15 << 14)) ^ (w15 >>> 3);
            const w2 = w[i - 2];
            const s1 = ((w2 >>> 17) | (w2 << 15)) ^ ((w2 >>> 19) | (w2 << 13)) ^ (w2 >>> 10);
            w[i] = (w[i - 16] + s0 + w[i - 7] + s1) | 0;
        }

        let a = this.h[0], b = this.h[1], c = this.h[2], d = this.h[3];
        let e = this.h[4], f = this.h[5], g = this.h[6], h = this.h[7];

        for (let i = 0; i < 64; i++) {
            const S1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7));
            const ch = (e & f) ^ ((~e) & g);
            const temp1 = (h + S1 + ch + K_SHA256[i] + w[i]) | 0;
            const S0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10));
            const maj = (a & b) ^ (a & c) ^ (b & c);
            const temp2 = (S0 + maj) | 0;

            h = g;
            g = f;
            f = e;
            e = (d + temp1) | 0;
            d = c;
            c = b;
            b = a;
            a = (temp1 + temp2) | 0;
        }

        this.h[0] = (this.h[0] + a) | 0;
        this.h[1] = (this.h[1] + b) | 0;
        this.h[2] = (this.h[2] + c) | 0;
        this.h[3] = (this.h[3] + d) | 0;
        this.h[4] = (this.h[4] + e) | 0;
        this.h[5] = (this.h[5] + f) | 0;
        this.h[6] = (this.h[6] + g) | 0;
        this.h[7] = (this.h[7] + h) | 0;
    };

    IncrementalSha256.prototype.update = function (chunk) {
        if (this.finalized) {
            throw new Error('Cannot update finalized hash');
        }
        const uint8 = (chunk instanceof Uint8Array) ? chunk : new Uint8Array(chunk);
        let offset = 0;
        let len = uint8.length;
        this.totalBytes += len;

        if (this.bufferLength > 0) {
            const need = 64 - this.bufferLength;
            if (len >= need) {
                this.buffer.set(uint8.subarray(0, need), this.bufferLength);
                this._processBlock(this.buffer, 0);
                this.bufferLength = 0;
                offset = need;
                len -= need;
            } else {
                this.buffer.set(uint8, this.bufferLength);
                this.bufferLength += len;
                return this;
            }
        }

        while (len >= 64) {
            this._processBlock(uint8, offset);
            offset += 64;
            len -= 64;
        }

        if (len > 0) {
            this.buffer.set(uint8.subarray(offset, offset + len), 0);
            this.bufferLength = len;
        }
        return this;
    };

    IncrementalSha256.prototype.digest = function () {
        if (this.finalized) {
            throw new Error('Digest already called');
        }
        this.finalized = true;

        const totalBits = this.totalBytes * 8;
        const highBits = Math.floor(totalBits / 0x100000000);
        const lowBits = (totalBits >>> 0);

        this.buffer[this.bufferLength++] = 0x80;

        if (this.bufferLength > 56) {
            this.buffer.fill(0, this.bufferLength, 64);
            this._processBlock(this.buffer, 0);
            this.bufferLength = 0;
        }

        this.buffer.fill(0, this.bufferLength, 56);

        this.buffer[56] = (highBits >>> 24) & 0xff;
        this.buffer[57] = (highBits >>> 16) & 0xff;
        this.buffer[58] = (highBits >>> 8) & 0xff;
        this.buffer[59] = highBits & 0xff;

        this.buffer[60] = (lowBits >>> 24) & 0xff;
        this.buffer[61] = (lowBits >>> 16) & 0xff;
        this.buffer[62] = (lowBits >>> 8) & 0xff;
        this.buffer[63] = lowBits & 0xff;

        this._processBlock(this.buffer, 0);

        let hex = '';
        for (let i = 0; i < 8; i++) {
            hex += (this.h[i] >>> 0).toString(16).padStart(8, '0');
        }
        return hex;
    };

    /**
     * Formats byte counts into human-readable binary units (B, KiB, MiB, GiB).
     */
    function formatBytes(bytes) {
        if (typeof bytes !== 'number' || isNaN(bytes) || bytes < 0) return '0 B';
        if (bytes < 1024) return bytes + ' B';
        if (bytes < 1048576) return (bytes / 1024).toFixed(2) + ' KiB';
        if (bytes < 1073741824) return (bytes / 1048576).toFixed(2) + ' MiB';
        return (bytes / 1073741824).toFixed(2) + ' GiB';
    }

    /**
     * Authoritative frontend validation for selected video files.
     */
    function validateFileConstraints(file) {
        if (!file) {
            const err = new Error('Vui lòng chọn file video.');
            err.code = 'MISSING_FILE';
            throw err;
        }
        if (file.name && !file.name.toLowerCase().endsWith('.mp4')) {
            const err = new Error('Chỉ chấp nhận file định dạng MP4 (*.mp4).');
            err.code = 'INVALID_FORMAT';
            throw err;
        }
        if (typeof file.size === 'number') {
            if (file.size <= 0) {
                const err = new Error('File video không được để trống (0 byte).');
                err.code = 'ZERO_SIZE_FILE';
                throw err;
            }
            if (file.size > MAX_VIDEO_SIZE_BYTES) {
                const err = new Error('Dung lượng file (' + formatBytes(file.size) + ') vượt quá giới hạn tối đa cho phép (50 GiB). Vui lòng chọn file nhỏ hơn.');
                err.code = 'FILE_EXCEEDS_MAX_SIZE';
                throw err;
            }
        }
        const expectedParts = Math.max(1, Math.ceil((file.size || 0) / PART_SIZE));
        return {
            valid: true,
            size: file.size,
            formattedSize: formatBytes(file.size),
            expectedParts: expectedParts,
            partSize: PART_SIZE
        };
    }

    /**
     * Helper to read a chunk slice into a Uint8Array safely across environments.
     */
    async function readChunkAsUint8Array(chunk) {
        if (chunk instanceof Uint8Array) return chunk;
        if (chunk instanceof ArrayBuffer) return new Uint8Array(chunk);
        if (typeof chunk.arrayBuffer === 'function') {
            const buf = await chunk.arrayBuffer();
            return new Uint8Array(buf);
        }
        if (typeof FileReader !== 'undefined') {
            return new Promise((resolve, reject) => {
                const reader = new FileReader();
                reader.onload = () => resolve(new Uint8Array(reader.result));
                reader.onerror = () => reject(reader.error || new Error('FileReader failed to read chunk'));
                reader.readAsArrayBuffer(chunk);
            });
        }
        throw new Error('Không thể đọc dữ liệu chunk nhị phân.');
    }

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
            hashWorker: null,
            isCancelled: false,
            isSettled: false,
            registeredAssetId: null,
            playbackUrl: null,
            error: null,
            errorCode: null,
            errorStatus: null,
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
     * Incrementally calculates standard SHA-256 in memory-bounded slices on the current thread,
     * yielding periodically via setTimeout(..., 0) to avoid UI freezing.
     */
    UploadCoordinator.prototype.calculateSha256Chunked = async function (file, options) {
        options = options || {};
        const attempt = options.attempt;
        const currentGen = options.generation;
        const onProgress = options.onProgress;
        const chunkSize = options.chunkSize || HASH_CHUNK_SIZE;
        const totalSize = typeof file.size === 'number' ? file.size : 0;

        if (totalSize > 0 && typeof file.slice !== 'function') {
            throw new Error('Đối tượng file không hỗ trợ đọc phân đoạn (file.slice không khả dụng).');
        }

        let onBeforeUnload = null;
        let onAbort = null;
        let navigationCancelled = false;

        if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
            onBeforeUnload = () => {
                navigationCancelled = true;
                if (attempt) {
                    attempt.isCancelled = true;
                }
            };
            try {
                window.addEventListener('beforeunload', onBeforeUnload, { once: true });
            } catch (e) { /* ignore */ }
        }

        if (attempt && attempt.abortController && attempt.abortController.signal) {
            onAbort = () => {
                if (attempt) {
                    attempt.isCancelled = true;
                }
            };
            if (attempt.abortController.signal.aborted) {
                if (attempt) attempt.isCancelled = true;
            } else {
                try {
                    attempt.abortController.signal.addEventListener('abort', onAbort, { once: true });
                } catch (e) { /* ignore */ }
            }
        }

        const cleanupListeners = () => {
            if (onBeforeUnload && typeof window !== 'undefined' && typeof window.removeEventListener === 'function') {
                try {
                    window.removeEventListener('beforeunload', onBeforeUnload);
                } catch (e) { /* ignore */ }
                onBeforeUnload = null;
            }
            if (onAbort && attempt && attempt.abortController && attempt.abortController.signal) {
                try {
                    attempt.abortController.signal.removeEventListener('abort', onAbort);
                } catch (e) { /* ignore */ }
                onAbort = null;
            }
        };

        try {
            const hasher = new IncrementalSha256();
            if (totalSize === 0) {
                return hasher.digest();
            }

            let offset = 0;
            let lastYieldTime = Date.now();

            while (offset < totalSize) {
                if (navigationCancelled) {
                    const navErr = new Error('Tính toán SHA-256 bị hủy do điều hướng trang.');
                    navErr.code = HASH_NAVIGATION_CANCELLED;
                    navErr.isCancellation = true;
                    throw navErr;
                }
                if (attempt && (attempt.isCancelled || this.isStale(attempt, currentGen))) {
                    const cancelErr = new Error('Tính toán SHA-256 đã bị hủy.');
                    cancelErr.code = (attempt && attempt.isCancelled && navigationCancelled) ? HASH_NAVIGATION_CANCELLED : HASH_ABORTED;
                    cancelErr.isCancellation = true;
                    throw cancelErr;
                }

                const end = Math.min(offset + chunkSize, totalSize);
                const slice = file.slice(offset, end);
                const uint8Chunk = await readChunkAsUint8Array(slice);

                if (navigationCancelled) {
                    const navErr = new Error('Tính toán SHA-256 bị hủy do điều hướng trang.');
                    navErr.code = HASH_NAVIGATION_CANCELLED;
                    navErr.isCancellation = true;
                    throw navErr;
                }
                if (attempt && (attempt.isCancelled || this.isStale(attempt, currentGen))) {
                    const cancelErr = new Error('Tính toán SHA-256 đã bị hủy.');
                    cancelErr.code = (attempt && attempt.isCancelled && navigationCancelled) ? HASH_NAVIGATION_CANCELLED : HASH_ABORTED;
                    cancelErr.isCancellation = true;
                    throw cancelErr;
                }

                hasher.update(uint8Chunk);
                offset = end;

                if (onProgress && totalSize > 0) {
                    const pct = Math.floor((offset / totalSize) * 100);
                    onProgress(pct, offset, totalSize);
                }

                const now = Date.now();
                if (now - lastYieldTime > 16 || offset >= totalSize) {
                    lastYieldTime = now;
                    await new Promise(resolve => setTimeout(resolve, 0));
                }
            }

            if (navigationCancelled) {
                const navErr = new Error('Tính toán SHA-256 bị hủy do điều hướng trang.');
                navErr.code = HASH_NAVIGATION_CANCELLED;
                navErr.isCancellation = true;
                throw navErr;
            }
            if (attempt && (attempt.isCancelled || this.isStale(attempt, currentGen))) {
                const cancelErr = new Error('Tính toán SHA-256 đã bị hủy.');
                cancelErr.code = HASH_ABORTED;
                cancelErr.isCancellation = true;
                throw cancelErr;
            }

            return hasher.digest();
        } finally {
            cleanupListeners();
        }
    };

    /**
     * Executes SHA-256 calculation inside a Web Worker when supported.
     */
    UploadCoordinator.prototype.calculateSha256WithWorker = async function (file, options) {
        options = options || {};
        const self = this;
        const attempt = options.attempt;
        const currentGen = options.generation;
        const onProgress = options.onProgress;
        const workerUrl = options.workerUrl || '/js/screen/screen-hash-worker.js';

        return new Promise((resolve, reject) => {
            let worker;
            try {
                const WorkerClass = (typeof window !== 'undefined' && typeof window.Worker !== 'undefined') ? window.Worker : Worker;
                worker = new WorkerClass(workerUrl);
            } catch (e) {
                reject(e);
                return;
            }

            if (attempt) {
                attempt.hashWorker = worker;
            }

            let settled = false;
            let onAbort = null;
            let onBeforeUnload = null;

            const cleanupResources = () => {
                if (attempt && attempt.hashWorker === worker) {
                    attempt.hashWorker = null;
                }
                if (worker) {
                    try { worker.terminate(); } catch (e) { /* ignore */ }
                    worker = null;
                }
                if (onAbort && attempt && attempt.abortController && attempt.abortController.signal) {
                    try {
                        attempt.abortController.signal.removeEventListener('abort', onAbort);
                    } catch (e) { /* ignore */ }
                    onAbort = null;
                }
                if (onBeforeUnload && typeof window !== 'undefined' && typeof window.removeEventListener === 'function') {
                    try {
                        window.removeEventListener('beforeunload', onBeforeUnload);
                    } catch (e) { /* ignore */ }
                    onBeforeUnload = null;
                }
            };

            const finishSuccess = (digest) => {
                if (settled) return;
                settled = true;
                cleanupResources();
                resolve(digest);
            };

            const finishFailure = (error) => {
                if (settled) return;
                settled = true;
                cleanupResources();
                reject(error);
            };

            if (attempt && attempt.abortController && attempt.abortController.signal) {
                onAbort = () => {
                    if (attempt) {
                        attempt.isCancelled = true;
                    }
                    const abortErr = new Error('Tính toán SHA-256 đã bị hủy.');
                    abortErr.code = HASH_ABORTED;
                    abortErr.isCancellation = true;
                    finishFailure(abortErr);
                };
                if (attempt.abortController.signal.aborted) {
                    onAbort();
                    return;
                }
                attempt.abortController.signal.addEventListener('abort', onAbort, { once: true });
            }

            onBeforeUnload = () => {
                if (attempt) {
                    attempt.isCancelled = true;
                }
                const navErr = new Error('Tính toán SHA-256 bị hủy do điều hướng trang.');
                navErr.code = HASH_NAVIGATION_CANCELLED;
                navErr.isCancellation = true;
                finishFailure(navErr);
            };

            if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
                window.addEventListener('beforeunload', onBeforeUnload, { once: true });
            }

            worker.onmessage = (event) => {
                if (settled) return;
                if (attempt && (attempt.isCancelled || self.isStale(attempt, currentGen))) {
                    const cancelErr = new Error('Tính toán SHA-256 đã bị hủy.');
                    cancelErr.code = HASH_ABORTED;
                    cancelErr.isCancellation = true;
                    finishFailure(cancelErr);
                    return;
                }
                const data = event.data || {};
                if (data.type === 'PROGRESS') {
                    if (onProgress && (!attempt || !self.isStale(attempt, currentGen))) {
                        onProgress(data.percent, data.hashedBytes, data.totalBytes);
                    }
                } else if (data.type === 'COMPLETE') {
                    finishSuccess(data.digest);
                } else if (data.type === 'ERROR') {
                    const err = new Error(data.message || 'Lỗi trong quá trình Web Worker tính toán SHA-256.');
                    err.code = data.code || 'WORKER_HASH_ERROR';
                    finishFailure(err);
                }
            };

            worker.onerror = (errEvent) => {
                if (settled) return;
                if (attempt && (attempt.isCancelled || self.isStale(attempt, currentGen))) {
                    const cancelErr = new Error('Tính toán SHA-256 đã bị hủy.');
                    cancelErr.code = HASH_ABORTED;
                    cancelErr.isCancellation = true;
                    finishFailure(cancelErr);
                    return;
                }
                const err = new Error((errEvent && errEvent.message) || 'Web Worker gặp sự cố khi tính toán SHA-256.');
                err.code = 'WORKER_ERROR';
                finishFailure(err);
            };

            try {
                worker.postMessage({
                    type: 'HASH',
                    file: file,
                    chunkSize: HASH_CHUNK_SIZE
                });
            } catch (postErr) {
                finishFailure(postErr);
            }
        });
    };

    /**
     * Calculates standard SHA-256 hash using bounded-memory streaming without loading the full file.
     * FAILS CLOSED: Never fabricates synthetic hashes if Web Crypto capability check fails.
     */
    UploadCoordinator.prototype.calculateSha256 = async function (file, cryptoFn, options) {
        if (cryptoFn) {
            return await cryptoFn(file);
        }

        // FAILS CLOSED: Check environment cryptographic capabilities (MS-08E2 fix 3 invariant)
        if (typeof crypto === 'undefined' || !crypto.subtle || typeof crypto.subtle.digest !== 'function') {
            throw new Error('Web Crypto API (crypto.subtle) is not supported in this environment. SHA-256 calculation failed closed.');
        }

        options = options || {};
        const attempt = options.attempt;

        // Try Web Worker if in browser/worker environment and not explicitly disabled
        const workerCtor = (typeof window !== 'undefined' && typeof window.Worker !== 'undefined')
            ? window.Worker
            : (typeof Worker !== 'undefined' ? Worker : null);
        const canUseWorker = workerCtor !== null && options.disableWorker !== true;

        if (canUseWorker) {
            try {
                return await this.calculateSha256WithWorker(file, options);
            } catch (workerErr) {
                const isCancellation = (attempt && attempt.isCancelled)
                    || (attempt && this.isStale(attempt, options.generation))
                    || (attempt && attempt.abortController && attempt.abortController.signal && attempt.abortController.signal.aborted)
                    || (workerErr && (workerErr.isCancellation === true || workerErr.code === HASH_NAVIGATION_CANCELLED || workerErr.code === HASH_ABORTED));

                if (isCancellation) {
                    throw workerErr;
                }
                // Fallback to bounded-memory chunked hashing on main thread
            }
        }

        return await this.calculateSha256Chunked(file, options);
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

        // Validate file constraints
        try {
            validateFileConstraints(file);
        } catch (validationErr) {
            if (callbacks.onError) {
                callbacks.onError(validationErr, null);
            }
            throw validationErr;
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
                callbacks.onProgress(0, 'Đang chuẩn bị tính toán mã băm SHA-256...', attempt);
            }

            const contentHash = await this.calculateSha256(file, cryptoFn, {
                attempt: attempt,
                generation: currentGen,
                onProgress: (hashPct, hashedBytes, totalBytes) => {
                    if (callbacks.onProgress && !this.isStale(attempt, currentGen)) {
                        const overallPct = Math.round(hashPct * 0.1);
                        const status = 'Đang tính mã băm SHA-256: ' + hashPct + '% (' + formatBytes(hashedBytes) + ' / ' + formatBytes(totalBytes) + ')...';
                        callbacks.onProgress(overallPct, status, attempt);
                    }
                }
            });
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
            // Fenced out if attempt was superseded by another attempt or if generation advanced
            if (this.currentAttempt !== attempt || (attempt.generation !== currentGen)) {
                return attempt;
            }

            // If attempt was already settled (e.g. by abortAttempt()), preserve its settled state
            if (attempt.isSettled) {
                return attempt;
            }

            attempt.error = err.message;
            attempt.errorCode = (err && err.errorCode) || attempt.errorCode || null;
            attempt.errorStatus = (err && err.status) || attempt.errorStatus || null;
            attempt.failedAtPhase = attempt.phase;

            const isNavCancelled = err && (err.code === HASH_NAVIGATION_CANCELLED || err.isCancellation === true);
            const isAborted = attempt.isCancelled || (attempt.abortController && attempt.abortController.signal && attempt.abortController.signal.aborted) || (err && err.code === HASH_ABORTED);

            if (isNavCancelled || isAborted) {
                attempt.phase = UploadPhase.ABORTED;
            } else {
                attempt.phase = UploadPhase.FAILED;
            }

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
            let errCode = null;
            let rawData = null;
            try {
                const errData = await initResp.json();
                rawData = errData;
                if (errData && errData.message) errMsg = errData.message;
                if (errData && errData.errorCode) errCode = errData.errorCode;
            } catch (e) { /* ignore JSON parse error on HTTP errors */ }

            // Recheck generation and cancellation after async error body read
            if (this.currentAttempt !== attempt || attempt.generation !== requestGen) {
                return;
            }
            if (attempt.isCancelled) {
                return;
            }
            const initErr = new Error('Khởi tạo upload session thất bại: ' + errMsg);
            initErr.status = initResp.status;
            initErr.errorCode = errCode;
            initErr.serverError = rawData;
            attempt.errorCode = errCode;
            attempt.errorStatus = initResp.status;
            throw initErr;
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
        attempt.errorCode = null;
        attempt.errorStatus = null;
        attempt.failedAtPhase = null;

        try {
            attempt.phase = UploadPhase.HASHING;
            if (callbacks.onProgress && !this.isStale(attempt, currentGen)) {
                callbacks.onProgress(0, 'Đang tính toán lại mã băm SHA-256...', attempt);
            }
            const contentHash = await this.calculateSha256(attempt.file, cryptoFn, {
                attempt: attempt,
                generation: currentGen,
                onProgress: (hashPct, hashedBytes, totalBytes) => {
                    if (callbacks.onProgress && !this.isStale(attempt, currentGen)) {
                        const overallPct = Math.round(hashPct * 0.1);
                        const status = 'Đang tính mã băm SHA-256: ' + hashPct + '% (' + formatBytes(hashedBytes) + ' / ' + formatBytes(totalBytes) + ')...';
                        callbacks.onProgress(overallPct, status, attempt);
                    }
                }
            });
            if (this.isStale(attempt, currentGen)) return attempt;

            await this.performInitiation(attempt, titleId, entryId, contentHash, callbacks, fetchFn, getCsrfHeaders);
            if (this.isStale(attempt, currentGen)) return attempt;

            await this.performPartsUpload(attempt, titleId, entryId, callbacks, fetchFn, getCsrfHeaders);
            if (this.isStale(attempt, currentGen)) return attempt;

            await this.performCompletion(attempt, titleId, entryId, contentHash, callbacks, fetchFn, getCsrfHeaders);
            return attempt;
        } catch (err) {
            if (this.currentAttempt !== attempt || (attempt.generation !== currentGen)) {
                return attempt;
            }
            if (attempt.isSettled) {
                return attempt;
            }
            attempt.error = err.message;
            attempt.errorCode = (err && err.errorCode) || attempt.errorCode || null;
            attempt.errorStatus = (err && err.status) || attempt.errorStatus || null;
            attempt.failedAtPhase = attempt.phase;
            const isNavCancelled = err && (err.code === HASH_NAVIGATION_CANCELLED || err.isCancellation === true);
            const isAborted = attempt.isCancelled || (attempt.abortController && attempt.abortController.signal && attempt.abortController.signal.aborted) || (err && err.code === HASH_ABORTED);
            if (isNavCancelled || isAborted) {
                attempt.phase = UploadPhase.ABORTED;
            } else {
                attempt.phase = UploadPhase.FAILED;
            }
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

        // Terminate any active hashing worker for this attempt
        if (attempt.hashWorker && typeof attempt.hashWorker.terminate === 'function') {
            try {
                attempt.hashWorker.terminate();
                attempt.hashWorker = null;
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
     * Standard error handler aligning UI state and controls with structured backend error codes
     * and upload lifecycle failure phases.
     *
     * Invariants enforced:
     * - MEDIA_STORAGE_INITIATION_REJECTED (HTTP 502): safe to retry with identical idempotencyKey; show retry button.
     * - MEDIA_UPLOAD_INITIATION_IN_PROGRESS (HTTP 409): concurrent initiation; hide retry button; prevent storm.
     * - MEDIA_UPLOAD_INITIATION_UNCONFIRMED_CLAIM (HTTP 409): unconfirmed claim; hide retry button; reconciliation required.
     * - MEDIA_STORAGE_INITIATION_AMBIGUOUS (HTTP 504): ambiguous provider outcome; hide retry button; reconciliation required.
     * - Lost network response (isLostResponse / isUncertainInitiation): safe to retry initiation with same key.
     * - Unknown JSON / non-JSON server errors: fail closed; hide retry button; prevent unsafe retry assertions.
     */
    function handleUploadError(err, attempt, ui) {
        ui = ui || {};
        const showAlert = (typeof ui.showAlert === 'function') ? ui.showAlert.bind(ui) : function () {};
        const retryInitBtn = ui.retryInitBtn || { style: {} };
        const startUploadBtn = ui.startUploadBtn || {};
        const fileInput = ui.fileInput || {};
        const abortUploadBtn = ui.abortUploadBtn || { style: {} };

        const failurePhase = (attempt && attempt.failedAtPhase) ? attempt.failedAtPhase : (attempt ? attempt.phase : null);
        const errorCode = (err && err.errorCode) || (attempt && attempt.errorCode) || null;
        const key = (attempt && attempt.idempotencyKey) ? attempt.idempotencyKey : 'N/A';
        const isInitiationFailure = (attempt && !attempt.sessionId && (failurePhase === UploadPhase.INITIATING || failurePhase === UploadPhase.FAILED));

        // B1: Definitive provider rejection (HTTP 502) - safe to retry with same key
        if (errorCode === 'MEDIA_STORAGE_INITIATION_REJECTED') {
            const msg = (err && err.message) ? err.message : 'Nhà cung cấp lưu trữ từ chối yêu cầu khởi tạo do cấu hình hoặc quyền truy cập không hợp lệ.';
            showAlert('error', msg + ' Khóa phiên được bảo lưu (Idempotency Key: ' + key + '). Bạn có thể bấm "Thử lại khởi tạo" với cùng khóa phiên sau khi kiểm tra lại cấu hình lưu trữ.');
            retryInitBtn.style.display = 'inline-block';
            startUploadBtn.disabled = true;
            fileInput.disabled = true;
            abortUploadBtn.style.display = 'none';
            return;
        }

        // B2: Concurrent initiation in progress (HTTP 409) - prevent repeat-click storms
        if (errorCode === 'MEDIA_UPLOAD_INITIATION_IN_PROGRESS') {
            const msg = (err && err.message) ? err.message : 'Phiên tải lên đang được khởi tạo bởi một tiến trình khác.';
            showAlert('warning', msg + ' Vui lòng chờ trong giây lát và không gửi yêu cầu liên tục. Khóa phiên bảo lưu: ' + key);
            retryInitBtn.style.display = 'none';
            startUploadBtn.disabled = true;
            fileInput.disabled = true;
            abortUploadBtn.style.display = 'none';
            return;
        }

        // B3: Unconfirmed claim exists on server (HTTP 409) - reconciliation required, no retry button
        if (errorCode === 'MEDIA_UPLOAD_INITIATION_UNCONFIRMED_CLAIM') {
            const msg = (err && err.message) ? err.message : 'Phiên tải lên tồn tại yêu cầu khởi tạo chưa xác nhận kết quả.';
            showAlert('error', msg + ' Cần thực hiện đối soát trên hệ thống trước khi tiếp tục. Không thể tự động thử lại để đảm bảo an toàn dữ liệu. Khóa phiên: ' + key);
            retryInitBtn.style.display = 'none';
            startUploadBtn.disabled = true;
            fileInput.disabled = true;
            abortUploadBtn.style.display = 'none';
            return;
        }

        // B4: Ambiguous provider initiation (HTTP 504) - reconciliation required, no retry button
        if (errorCode === 'MEDIA_STORAGE_INITIATION_AMBIGUOUS') {
            const msg = (err && err.message) ? err.message : 'Yêu cầu khởi tạo tới nhà cung cấp lưu trữ bị gián đoạn hoặc không thể xác nhận kết quả.';
            showAlert('error', msg + ' Kết quả khởi tạo chưa được xác nhận và cần đối soát trạng thái trước khi thử lại. Khóa phiên: ' + key);
            retryInitBtn.style.display = 'none';
            startUploadBtn.disabled = true;
            fileInput.disabled = true;
            abortUploadBtn.style.display = 'none';
            return;
        }

        // B5: Lost network response or pre-response cancel without fabricated server error code
        if (err && (err.isLostResponse || err.isUncertainInitiation)) {
            showAlert('error', (err.message || 'Lỗi kết nối khi gửi yêu cầu khởi tạo') + '. Trạng thái trên máy chủ chưa xác định. Vui lòng bấm "Thử lại khởi tạo" để tiếp tục an toàn với cùng khóa phiên bảo vệ (Idempotency Key: ' + key + '), không tạo phiên trùng lặp.');
            retryInitBtn.style.display = 'inline-block';
            startUploadBtn.disabled = true;
            fileInput.disabled = true;
            abortUploadBtn.style.display = 'none';
            return;
        }

        // B7: Other initiation failure (unknown JSON / non-JSON server error 500, 400 Bad Request, etc.)
        if (isInitiationFailure) {
            showAlert('error', 'Khởi tạo upload session thất bại: ' + (err && err.message ? err.message : 'Lỗi máy chủ không xác định') + '. Khóa phiên: ' + key + '. Cần kiểm tra hệ thống hoặc đối soát trước khi tiếp tục.');
            retryInitBtn.style.display = 'none';
            startUploadBtn.disabled = true;
            fileInput.disabled = true;
            abortUploadBtn.style.display = 'none';
            return;
        }

        // Chunk upload phase failure
        if (attempt && attempt.sessionId && failurePhase === UploadPhase.UPLOADING_PARTS) {
            showAlert('error', 'Lỗi trong quá trình truyền chunk lên B2: ' + (err && err.message ? err.message : '') + '. Session ID: ' + attempt.sessionId + '. Bạn có thể bấm "Hủy tải lên" để hủy phiên trên hệ thống, hoặc kiểm tra kết nối mạng/CORS trước khi thử lại.');
            abortUploadBtn.style.display = 'inline-block';
            abortUploadBtn.disabled = false;
            startUploadBtn.disabled = true;
            fileInput.disabled = true;
            retryInitBtn.style.display = 'none';
            return;
        }

        // Completion phase failure
        if (attempt && failurePhase === UploadPhase.COMPLETING) {
            showAlert('error', 'Lỗi khi xác nhận hoàn tất phiên tải trên B2: ' + (err && err.message ? err.message : '') + '. Session ID: ' + (attempt.sessionId || 'N/A') + '. CẢNH BÁO: Trạng thái gộp file trên B2 chưa xác định. Vui lòng KHÔNG bắt đầu tải file mới ngay để tránh xung đột dữ liệu. Bạn có thể làm mới trang để kiểm tra xem video đã được gắn hay chưa.');
            abortUploadBtn.style.display = 'none';
            startUploadBtn.disabled = true;
            fileInput.disabled = true;
            retryInitBtn.style.display = 'none';
            return;
        }

        // Pre-initiation hashing phase failure
        if (failurePhase === UploadPhase.HASHING) {
            showAlert('error', 'Lỗi trước khi tạo phiên: ' + (err && err.message ? err.message : ''));
            startUploadBtn.disabled = false;
            fileInput.disabled = false;
            abortUploadBtn.style.display = 'none';
            retryInitBtn.style.display = 'none';
            return;
        }

        // Generic / fallback error
        showAlert('error', 'Lỗi: ' + (err && err.message ? err.message : '') + (attempt && attempt.sessionId ? (' (Session ID: ' + attempt.sessionId + ')') : '') + '.');
        if (attempt && !attempt.isSettled) {
            startUploadBtn.disabled = true;
            fileInput.disabled = true;
            if (attempt.sessionId) {
                abortUploadBtn.style.display = 'inline-block';
                abortUploadBtn.disabled = false;
            }
        } else {
            startUploadBtn.disabled = false;
            fileInput.disabled = false;
            abortUploadBtn.style.display = 'none';
        }
        retryInitBtn.style.display = 'none';
    }

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
        MAX_VIDEO_SIZE_BYTES: MAX_VIDEO_SIZE_BYTES,
        MAX_BROWSER_SMOKE_SIZE: MAX_BROWSER_SMOKE_SIZE,
        HASH_CHUNK_SIZE: HASH_CHUNK_SIZE,
        UploadPhase: UploadPhase,
        generateId: generateId,
        createAttempt: createAttempt,
        UploadCoordinator: UploadCoordinator,
        IncrementalSha256: IncrementalSha256,
        formatBytes: formatBytes,
        validateFileConstraints: validateFileConstraints,
        handleUploadError: handleUploadError,
        HASH_NAVIGATION_CANCELLED: HASH_NAVIGATION_CANCELLED,
        HASH_ABORTED: HASH_ABORTED
    };
});
