const { describe, test } = require('node:test');
const assert = require('node:assert');
const {
    PART_SIZE,
    MAX_VIDEO_SIZE_BYTES,
    MAX_BROWSER_SMOKE_SIZE,
    UploadPhase,
    createAttempt,
    UploadCoordinator,
    IncrementalSha256,
    formatBytes,
    validateFileConstraints,
    handleUploadError
} = require('../../../main/resources/static/js/screen/screen-video-uploader.js');

describe('MS-08E2 Offline Browser E2E Upload Contract & Lifecycle Tests', () => {

    const titleId = 'title-uuid-101';
    const entryId = 'entry-uuid-202';

    // 50 MiB mock file = 2 chunks of 25 MiB (PART_SIZE = 26,214,400 bytes)
    const mockFile50MiB = {
        name: 'episode-01-synthetic.mp4',
        size: 52428800,
        slice: function (start, end) {
            return {
                start: start,
                end: end,
                size: end - start,
                name: 'chunk-' + start + '-' + end
            };
        }
    };

    const mockCryptoFn = async (file) => 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855';

    const getCsrfHeaders = (headers = {}) => {
        headers['X-CSRF-TOKEN'] = 'test-admin-csrf-token-xyz';
        return headers;
    };

    // =========================================================================
    // 1. COMPLETE HAPPY-PATH BROWSER JOURNEY (CONTRACT POINTS 1 TO 12)
    // =========================================================================

    test('1. Full Happy Path: Validates selection, SHA-256, CSRF, stable idempotency, chunking, PUT headers, ETags, complete and playback URL', async () => {
        const coordinator = new UploadCoordinator();
        const callTrace = [];
        const progressTrace = [];
        let capturedInitPayload = null;
        let capturedCompletePayload = null;
        let capturedCsrfTokens = [];

        // Simulated DOM / UI validation helpers (mirroring entry-detail.html)
        const validateFileSelection = (file) => {
            if (!file.name.toLowerCase().endsWith('.mp4')) {
                throw new Error('Only MP4 format is accepted (*.mp4).');
            }
            if (file.size > MAX_BROWSER_SMOKE_SIZE) {
                throw new Error('File exceeds max browser smoke size (500 MiB).');
            }
            const expectedPartCount = Math.max(1, Math.ceil(file.size / PART_SIZE));
            return { expectedPartCount, partSize: PART_SIZE };
        };

        // Point 1: File selected by authenticated admin & client-side file constraints validated
        const fileValidation = validateFileSelection(mockFile50MiB);
        assert.strictEqual(fileValidation.expectedPartCount, 2);

        // Mock Fetch verifying exact protocol interactions
        const fakeFetch = async (url, options = {}) => {
            callTrace.push({ url, method: options.method || 'GET' });

            // Point 3: Record CSRF header on all mutating requests
            if (options.headers && options.headers['X-CSRF-TOKEN']) {
                capturedCsrfTokens.push({ url, token: options.headers['X-CSRF-TOKEN'] });
            }

            // Point 4 & 5: Upload session initiation
            if (url === `/admin/screen/titles/${titleId}/entries/${entryId}/video/upload-session` && options.method === 'POST') {
                assert.ok(options.headers['X-CSRF-TOKEN'], 'Initiation POST must include CSRF token');
                assert.strictEqual(options.headers['Content-Type'], 'application/json');
                capturedInitPayload = JSON.parse(options.body);

                return {
                    ok: true,
                    status: 200,
                    json: async () => ({
                        sessionId: 'sess-e2e-approved-777',
                        expectedPartCount: 2,
                        partSizeBytes: 26214400
                    })
                };
            }

            // Point 6 & 7: Part URL presigning
            if (url.includes('/video/upload-session/sess-e2e-approved-777/parts/1/url')) {
                assert.ok(options.headers['X-CSRF-TOKEN'], 'Part 1 URL request must include CSRF token');
                return {
                    ok: true,
                    status: 200,
                    json: async () => ({ uploadUrl: 'https://b2.test-storage.local/part-upload?partNumber=1&uploadId=up-1' })
                };
            }

            if (url.includes('/video/upload-session/sess-e2e-approved-777/parts/2/url')) {
                assert.ok(options.headers['X-CSRF-TOKEN'], 'Part 2 URL request must include CSRF token');
                return {
                    ok: true,
                    status: 200,
                    json: async () => ({ uploadUrl: 'https://b2.test-storage.local/part-upload?partNumber=2&uploadId=up-1' })
                };
            }

            // Point 7 & 8: Direct B2 PUT requests & ETag returns
            if (url.startsWith('https://b2.test-storage.local/part-upload?partNumber=1')) {
                assert.strictEqual(options.method, 'PUT');
                assert.strictEqual(options.body.size, 26214400, 'Chunk 1 must be exact 25 MiB slice');
                assert.strictEqual(options.body.start, 0);
                assert.strictEqual(options.body.end, 26214400);
                return {
                    ok: true,
                    status: 200,
                    headers: { get: (header) => header.toLowerCase() === 'etag' ? '"etag-chunk-part-1"' : null }
                };
            }

            if (url.startsWith('https://b2.test-storage.local/part-upload?partNumber=2')) {
                assert.strictEqual(options.method, 'PUT');
                assert.strictEqual(options.body.size, 26214400, 'Chunk 2 must be exact 25 MiB slice');
                assert.strictEqual(options.body.start, 26214400);
                assert.strictEqual(options.body.end, 52428800);
                return {
                    ok: true,
                    status: 200,
                    headers: { get: (header) => header.toLowerCase() === 'etag' ? '"etag-chunk-part-2"' : null }
                };
            }

            // Point 9 & 10: Multipart complete and ScreenEntry attachment
            if (url === `/admin/screen/titles/${titleId}/entries/${entryId}/video/upload-session/sess-e2e-approved-777/complete` && options.method === 'POST') {
                assert.ok(options.headers['X-CSRF-TOKEN'], 'Complete POST must include CSRF token');
                assert.strictEqual(options.headers['Content-Type'], 'application/json');
                capturedCompletePayload = JSON.parse(options.body);

                // Point 11 & 12: Returns registered asset ID and playback URL
                return {
                    ok: true,
                    status: 200,
                    json: async () => ({
                        videoMediaAssetId: 'media-asset-uuid-8888',
                        playbackUrl: '/media/assets/media-asset-uuid-8888/content'
                    })
                };
            }

            throw new Error('Unexpected URL called: ' + url);
        };

        // Execution of startAttempt
        let successResult = null;
        const uploadResult = await coordinator.startAttempt({
            file: mockFile50MiB,
            titleId: titleId,
            entryId: entryId,
            fetchFn: fakeFetch,
            cryptoFn: mockCryptoFn,
            getCsrfHeaders: getCsrfHeaders,
            callbacks: {
                onProgress: (pct, msg) => { progressTrace.push({ pct, msg }); },
                onSuccess: (data) => { successResult = data; }
            }
        });

        // Point 2: SHA-256 computed
        assert.strictEqual(capturedCompletePayload.declaredContentHash, 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855');

        // Point 4: Stable idempotency key generated and transmitted
        assert.ok(capturedInitPayload.idempotencyKey.startsWith(`screen-entry-${entryId}-`));
        assert.strictEqual(capturedInitPayload.declaredSizeBytes, mockFile50MiB.size);
        assert.strictEqual(capturedInitPayload.originalFilename, mockFile50MiB.name);

        // Point 8: Manifest ordering strictly 1-indexed ascending
        assert.strictEqual(capturedCompletePayload.parts.length, 2);
        assert.deepStrictEqual(capturedCompletePayload.parts, [
            { partNumber: 1, sizeBytes: 26214400, eTag: '"etag-chunk-part-1"' },
            { partNumber: 2, sizeBytes: 26214400, eTag: '"etag-chunk-part-2"' }
        ]);

        // Point 11 & 12: Final UI state and playback URL rendering
        assert.strictEqual(uploadResult.phase, UploadPhase.COMPLETED);
        assert.strictEqual(uploadResult.isSettled, true);
        assert.strictEqual(uploadResult.registeredAssetId, 'media-asset-uuid-8888');
        assert.strictEqual(uploadResult.playbackUrl, '/media/assets/media-asset-uuid-8888/content');
        assert.strictEqual(successResult.videoMediaAssetId, 'media-asset-uuid-8888');
        assert.strictEqual(successResult.playbackUrl, '/media/assets/media-asset-uuid-8888/content');

        // Monotonic progress progression
        assert.ok(progressTrace.length >= 4);
        assert.strictEqual(progressTrace[progressTrace.length - 1].pct, 100);
    });

    // =========================================================================
    // 2. UNCERTAIN INITIATION & SAME-KEY RECOVERY (CONTRACT POINT 13 & 17)
    // =========================================================================

    test('2. Contract Point 13 & 17: Initiation network drop and cancel preserves same idempotencyKey across recovery', async () => {
        const coordinator = new UploadCoordinator();
        let initAttempts = 0;
        let sentKeys = [];

        const fakeFetch = async (url, options) => {
            if (url.endsWith('/video/upload-session') && options.method === 'POST') {
                initAttempts++;
                const payload = JSON.parse(options.body);
                sentKeys.push(payload.idempotencyKey);

                if (initAttempts === 1) {
                    throw new TypeError('Network connection timed out');
                }
                return {
                    ok: true,
                    json: async () => ({
                        sessionId: 'sess-recovered-101',
                        expectedPartCount: 1,
                        partSizeBytes: 52428800
                    })
                };
            }
            if (url.includes('/parts/1/url')) {
                return { ok: true, json: async () => ({ uploadUrl: 'https://b2.test/part-1' }) };
            }
            if (url === 'https://b2.test/part-1') {
                return { ok: true, headers: { get: (name) => name === 'ETag' ? '"etag-recovered"' : null } };
            }
            if (url.endsWith('/complete')) {
                return {
                    ok: true,
                    json: async () => ({
                        videoMediaAssetId: 'asset-recovered-101',
                        playbackUrl: '/media/assets/asset-recovered-101/content'
                    })
                };
            }
            throw new Error('Unhandled URL: ' + url);
        };

        // First attempt: network timeout
        await coordinator.startAttempt({
            file: mockFile50MiB,
            titleId: titleId,
            entryId: entryId,
            fetchFn: fakeFetch,
            cryptoFn: mockCryptoFn
        });

        const attempt = coordinator.getCurrentAttempt();
        assert.strictEqual(attempt.phase, UploadPhase.FAILED);
        assert.strictEqual(attempt.failedAtPhase, UploadPhase.INITIATING);
        assert.strictEqual(attempt.isSettled, false, 'Uncertain initiation must NOT be settled');
        const initialKey = attempt.idempotencyKey;

        // Controlled retry with same key
        const retryResult = await coordinator.retryInitiation({
            titleId: titleId,
            entryId: entryId,
            fetchFn: fakeFetch,
            cryptoFn: mockCryptoFn
        });

        assert.strictEqual(retryResult.phase, UploadPhase.COMPLETED);
        assert.strictEqual(retryResult.isSettled, true);
        assert.strictEqual(sentKeys.length, 2);
        assert.strictEqual(sentKeys[0], initialKey);
        assert.strictEqual(sentKeys[1], initialKey, 'Retry must reuse exact same idempotencyKey');
    });

    // =========================================================================
    // 3. PUT FAILURE WITH UNSETTLED SESSION (CONTRACT POINT 14 & 16)
    // =========================================================================

    test('3. Contract Point 14 & 16: Chunk PUT failure or cancellation leaves session unsettled and blocks fresh upload', async () => {
        const coordinator = new UploadCoordinator();
        let putDispatched = false;

        const fakeFetch = async (url, options) => {
            if (url.endsWith('/video/upload-session')) {
                return {
                    ok: true,
                    json: async () => ({
                        sessionId: 'sess-failing-put-303',
                        expectedPartCount: 2,
                        partSizeBytes: 26214400
                    })
                };
            }
            if (url.includes('/parts/1/url')) {
                return { ok: true, json: async () => ({ uploadUrl: 'https://b2.test/part-1' }) };
            }
            if (url === 'https://b2.test/part-1') {
                putDispatched = true;
                throw new Error('Connection reset by B2 peer during chunk upload');
            }
            throw new Error('Unhandled URL: ' + url);
        };

        await coordinator.startAttempt({
            file: mockFile50MiB,
            titleId: titleId,
            entryId: entryId,
            fetchFn: fakeFetch,
            cryptoFn: mockCryptoFn
        });

        const attempt = coordinator.getCurrentAttempt();
        assert.strictEqual(putDispatched, true);
        assert.strictEqual(attempt.phase, UploadPhase.FAILED);
        assert.strictEqual(attempt.failedAtPhase, UploadPhase.UPLOADING_PARTS);
        assert.strictEqual(attempt.sessionId, 'sess-failing-put-303');
        assert.strictEqual(attempt.isSettled, false, 'Must not be settled after PUT failure');

        // Verify starting a fresh upload is strictly blocked
        await assert.rejects(
            async () => {
                await coordinator.startAttempt({
                    file: mockFile50MiB,
                    titleId: titleId,
                    entryId: entryId,
                    fetchFn: fakeFetch,
                    cryptoFn: mockCryptoFn
                });
            },
            (err) => err.code === 'PREVIOUS_ATTEMPT_UNSETTLED'
        );
    });

    // =========================================================================
    // 4. AMBIGUOUS COMPLETION (CONTRACT POINT 15)
    // =========================================================================

    test('4. Contract Point 15: Ambiguous completion error preserves sessionId and prevents unsafe replacement upload', async () => {
        const coordinator = new UploadCoordinator();

        const fakeFetch = async (url) => {
            if (url.endsWith('/video/upload-session')) {
                return {
                    ok: true,
                    json: async () => ({ sessionId: 'sess-ambig-complete-404', expectedPartCount: 1, partSizeBytes: 52428800 })
                };
            }
            if (url.includes('/parts/1/url')) {
                return { ok: true, json: async () => ({ uploadUrl: 'https://b2.test/part-1' }) };
            }
            if (url === 'https://b2.test/part-1') {
                return { ok: true, headers: { get: () => '"etag-1"' } };
            }
            if (url.endsWith('/complete')) {
                // HTTP 500 or timeout: server may or may not have assembled object!
                return { ok: false, status: 500, json: async () => ({ message: 'Internal Storage Timeout' }) };
            }
            throw new Error('Unhandled URL: ' + url);
        };

        await coordinator.startAttempt({
            file: mockFile50MiB,
            titleId: titleId,
            entryId: entryId,
            fetchFn: fakeFetch,
            cryptoFn: mockCryptoFn
        });

        const attempt = coordinator.getCurrentAttempt();
        assert.strictEqual(attempt.phase, UploadPhase.FAILED);
        assert.strictEqual(attempt.failedAtPhase, UploadPhase.COMPLETING);
        assert.strictEqual(attempt.sessionId, 'sess-ambig-complete-404');
        assert.strictEqual(attempt.isSettled, false, 'Completion failure must remain unsettled to protect against duplicate uploads');
    });

    // =========================================================================
    // 5. MISSING OR MALFORMED ETAG (CONTRACT POINT 19)
    // =========================================================================

    test('5. Contract Point 19: Missing ETag in B2 PUT response fails closed without corrupted completion payload', async () => {
        const coordinator = new UploadCoordinator();
        let completeDispatched = false;

        const fakeFetch = async (url) => {
            if (url.endsWith('/video/upload-session')) {
                return {
                    ok: true,
                    json: async () => ({ sessionId: 'sess-missing-etag-505', expectedPartCount: 1, partSizeBytes: 52428800 })
                };
            }
            if (url.includes('/parts/1/url')) {
                return { ok: true, json: async () => ({ uploadUrl: 'https://b2.test/part-1' }) };
            }
            if (url === 'https://b2.test/part-1') {
                // Emulate B2 response lacking ETag header (e.g. CORS ExposeHeaders misconfigured)
                return { ok: true, headers: { get: () => null } };
            }
            if (url.endsWith('/complete')) {
                completeDispatched = true;
                return { ok: true, json: async () => ({}) };
            }
            throw new Error('Unhandled URL: ' + url);
        };

        await coordinator.startAttempt({
            file: mockFile50MiB,
            titleId: titleId,
            entryId: entryId,
            fetchFn: fakeFetch,
            cryptoFn: mockCryptoFn
        });

        const attempt = coordinator.getCurrentAttempt();
        assert.strictEqual(attempt.phase, UploadPhase.FAILED);
        assert.strictEqual(attempt.failedAtPhase, UploadPhase.UPLOADING_PARTS);
        assert.strictEqual(completeDispatched, false, 'Must never dispatch completion when ETag is missing');
        assert.ok(attempt.error.includes('không trả về header ETag'), 'Error must explicitly identify missing ETag');
    });

    // =========================================================================
    // 6. MISSING WEB CRYPTO FAILS CLOSED (CONTRACT POINT 20)
    // =========================================================================

    test('6. Contract Point 20: Missing Web Crypto implementation fails closed immediately without synthetic hashes', async () => {
        const coordinator = new UploadCoordinator();
        let networkDispatched = false;

        const fakeFetch = async () => {
            networkDispatched = true;
            return { ok: true };
        };

        const fileWithoutCrypto = {
            name: 'video.mp4',
            size: 1024,
            arrayBuffer: async () => new ArrayBuffer(1024)
        };

        const originalCrypto = globalThis.crypto;
        try {
            delete globalThis.crypto;

            await coordinator.startAttempt({
                file: fileWithoutCrypto,
                titleId: titleId,
                entryId: entryId,
                fetchFn: fakeFetch
            });
        } finally {
            globalThis.crypto = originalCrypto;
        }

        const attempt = coordinator.getCurrentAttempt();
        assert.strictEqual(attempt.phase, UploadPhase.FAILED);
        assert.strictEqual(attempt.failedAtPhase, UploadPhase.HASHING);
        assert.strictEqual(networkDispatched, false, 'Zero network requests allowed when crypto fails closed');
        assert.strictEqual(attempt.isSettled, true);
    });

    // =========================================================================
    // 7. ASYNC RESPONSE.JSON() GENERATION RACE (CONTRACT POINT 18)
    // =========================================================================

    test('7. Contract Point 18: Delayed response.json() generation race does not corrupt session state across retry', async () => {
        const coordinator = new UploadCoordinator();
        let initCount = 0;
        let deferredJson1 = null;
        let deferredJson2 = null;

        const fakeFetch = async (url, options) => {
            if (options && options.signal && options.signal.aborted) {
                const abortErr = new Error('The user aborted a request.');
                abortErr.name = 'AbortError';
                throw abortErr;
            }

            if (url.endsWith('/video/upload-session')) {
                initCount++;
                if (initCount === 1) {
                    return { ok: true, json: () => new Promise(r => { deferredJson1 = r; }) };
                }
                return { ok: true, json: () => new Promise(r => { deferredJson2 = r; }) };
            }
            if (url.includes('/parts/1/url')) {
                return { ok: true, json: async () => ({ uploadUrl: 'https://b2.test/part-1' }) };
            }
            if (url === 'https://b2.test/part-1') {
                return { ok: true, headers: { get: () => '"etag-gen2"' } };
            }
            if (url.endsWith('/complete')) {
                return {
                    ok: true,
                    json: async () => ({
                        videoMediaAssetId: 'asset-gen2-winner',
                        playbackUrl: '/media/assets/asset-gen2-winner/content'
                    })
                };
            }
            throw new Error('Unhandled URL: ' + url);
        };

        // Bounded wait helper to prevent indefinite hangs
        const waitUntil = async (predicate, description, timeoutMs = 2000) => {
            const start = Date.now();
            while (!predicate()) {
                if (Date.now() - start > timeoutMs) {
                    throw new Error(`Timeout waiting for: ${description} (${timeoutMs}ms)`);
                }
                await new Promise(r => setImmediate(r));
            }
        };

        // Start Generation 1
        coordinator.startAttempt({
            file: mockFile50MiB,
            titleId: titleId,
            entryId: entryId,
            fetchFn: fakeFetch,
            cryptoFn: mockCryptoFn
        });

        await waitUntil(() => deferredJson1, 'deferredJson1 initialization promise');

        // Cancel Generation 1
        await coordinator.abortAttempt({
            titleId: titleId,
            entryId: entryId,
            fetchFn: fakeFetch
        });

        // Retry into Generation 2
        const retryPromise = coordinator.retryInitiation({
            titleId: titleId,
            entryId: entryId,
            fetchFn: fakeFetch,
            cryptoFn: mockCryptoFn
        });

        await waitUntil(() => deferredJson2, 'deferredJson2 initialization promise');

        const attempt = coordinator.getCurrentAttempt();
        assert.strictEqual(attempt.generation, 2);

        // Generation 1's delayed JSON resolves
        deferredJson1({
            sessionId: 'stale-sess-from-gen1',
            expectedPartCount: 99,
            partSizeBytes: 1000
        });

        await new Promise(r => setImmediate(r));
        await new Promise(r => setImmediate(r));

        // Stale JSON must be fenced out
        assert.notStrictEqual(attempt.sessionId, 'stale-sess-from-gen1');
        assert.notStrictEqual(attempt.totalParts, 99);

        // Generation 2 valid JSON resolves
        deferredJson2({
            sessionId: 'valid-sess-from-gen2',
            expectedPartCount: 1,
            partSizeBytes: 52428800
        });

        const finalResult = await retryPromise;
        assert.strictEqual(finalResult.phase, UploadPhase.COMPLETED);
        assert.strictEqual(finalResult.sessionId, 'valid-sess-from-gen2');
        assert.strictEqual(finalResult.registeredAssetId, 'asset-gen2-winner');
    });

    // =========================================================================
    // 8. MS-08E2-L1 REPLACEMENT FLOW CONTRACT TESTS (FINDING E2L1-05)
    // =========================================================================

    test('8. Replacement Happy Path: Verifies initiation payload, completion payload, REPLACED response and callbacks', async () => {
        const coordinator = new UploadCoordinator();
        const oldAssetId = 'asset-old-111-uuid';
        const newAssetId = 'asset-new-222-uuid';
        let capturedInitBody = null;
        let capturedCompleteBody = null;
        let progressMessages = [];
        let successResult = null;

        const fakeFetch = async (url, options = {}) => {
            if (url.includes('/video/upload-session') && !url.includes('/parts') && !url.includes('/complete') && options.method === 'POST') {
                capturedInitBody = JSON.parse(options.body);
                return {
                    ok: true,
                    status: 200,
                    json: async () => ({
                        sessionId: 'sess-replace-888',
                        expectedPartCount: 1,
                        partSizeBytes: 52428800
                    })
                };
            }
            if (url.includes('/parts/1/url')) {
                return {
                    ok: true,
                    status: 200,
                    json: async () => ({ uploadUrl: 'https://b2.test-storage.local/replace-part-1' })
                };
            }
            if (url === 'https://b2.test-storage.local/replace-part-1') {
                return {
                    ok: true,
                    status: 200,
                    headers: { get: (name) => (name.toLowerCase() === 'etag' ? '"replace-etag-1"' : null) }
                };
            }
            if (url.includes('/complete') && options.method === 'POST') {
                capturedCompleteBody = JSON.parse(options.body);
                return {
                    ok: true,
                    status: 200,
                    json: async () => ({
                        videoMediaAssetId: newAssetId,
                        playbackUrl: '/media/assets/' + newAssetId + '/content',
                        status: 'REPLACED',
                        message: 'Video đã được thay thế thành công trên Screen Entry.'
                    })
                };
            }
            throw new Error('Unexpected URL: ' + url);
        };

        const result = await coordinator.startAttempt({
            file: mockFile50MiB,
            titleId: titleId,
            entryId: entryId,
            isReplacement: true,
            expectedCurrentVideoMediaAssetId: oldAssetId,
            fetchFn: fakeFetch,
            cryptoFn: mockCryptoFn,
            getCsrfHeaders: getCsrfHeaders,
            callbacks: {
                onProgress: (pct, msg) => progressMessages.push({ pct, msg }),
                onSuccess: (data) => { successResult = data; }
            }
        });

        // Verify initiation body contract
        assert.ok(capturedInitBody, 'Initiation body must be captured');
        assert.strictEqual(capturedInitBody.isReplacement, true);
        assert.strictEqual(capturedInitBody.expectedCurrentVideoMediaAssetId, oldAssetId);

        // Verify completion body contract
        assert.ok(capturedCompleteBody, 'Completion body must be captured');
        assert.strictEqual(capturedCompleteBody.expectedCurrentVideoMediaAssetId, oldAssetId);
        assert.strictEqual(capturedCompleteBody.parts.length, 1);
        assert.strictEqual(capturedCompleteBody.parts[0].eTag, '"replace-etag-1"');

        // Verify result and callbacks
        assert.strictEqual(result.phase, UploadPhase.COMPLETED);
        assert.strictEqual(result.registeredAssetId, newAssetId);
        assert.strictEqual(result.playbackUrl, '/media/assets/' + newAssetId + '/content');
        assert.ok(successResult, 'onSuccess must be called');
        assert.strictEqual(successResult.status, 'REPLACED');

        const finalProgress = progressMessages[progressMessages.length - 1];
        assert.strictEqual(finalProgress.pct, 100);
        assert.ok(finalProgress.msg.includes('thay thế video thành công'));
    });

    test('9. Replacement Retry: Preserves isReplacement and expectedCurrentVideoMediaAssetId across retry generation', async () => {
        const coordinator = new UploadCoordinator();
        const oldAssetId = 'asset-old-retry-333';
        const newAssetId = 'asset-new-retry-444';
        let initCallCount = 0;
        let capturedInitBodies = [];

        const fakeFetch = async (url, options = {}) => {
            if (url.includes('/video/upload-session') && !url.includes('/parts') && !url.includes('/complete') && options.method === 'POST') {
                initCallCount++;
                capturedInitBodies.push(JSON.parse(options.body));
                if (initCallCount === 1) {
                    throw new Error('Simulated network drop on first initiation');
                }
                return {
                    ok: true,
                    status: 200,
                    json: async () => ({
                        sessionId: 'sess-replace-retry-999',
                        expectedPartCount: 1,
                        partSizeBytes: 52428800
                    })
                };
            }
            if (url.includes('/parts/1/url')) {
                return {
                    ok: true,
                    status: 200,
                    json: async () => ({ uploadUrl: 'https://b2.test-storage.local/replace-retry-part-1' })
                };
            }
            if (url === 'https://b2.test-storage.local/replace-retry-part-1') {
                return {
                    ok: true,
                    status: 200,
                    headers: { get: (name) => (name.toLowerCase() === 'etag' ? '"retry-etag-1"' : null) }
                };
            }
            if (url.includes('/complete') && options.method === 'POST') {
                return {
                    ok: true,
                    status: 200,
                    json: async () => ({
                        videoMediaAssetId: newAssetId,
                        playbackUrl: '/media/assets/' + newAssetId + '/content',
                        status: 'REPLACED'
                    })
                };
            }
            throw new Error('Unexpected URL: ' + url);
        };

        // Attempt 1 fails on network
        await coordinator.startAttempt({
            file: mockFile50MiB,
            titleId: titleId,
            entryId: entryId,
            isReplacement: true,
            expectedCurrentVideoMediaAssetId: oldAssetId,
            fetchFn: fakeFetch,
            cryptoFn: mockCryptoFn,
            getCsrfHeaders: getCsrfHeaders
        });

        const failedAttempt = coordinator.getCurrentAttempt();
        assert.strictEqual(failedAttempt.phase, UploadPhase.FAILED);
        assert.strictEqual(failedAttempt.failedAtPhase, UploadPhase.INITIATING);
        assert.strictEqual(failedAttempt.isReplacement, true);
        assert.strictEqual(failedAttempt.expectedCurrentVideoMediaAssetId, oldAssetId);

        // Retry into Generation 2
        const retryResult = await coordinator.retryInitiation({
            titleId: titleId,
            entryId: entryId,
            fetchFn: fakeFetch,
            cryptoFn: mockCryptoFn,
            getCsrfHeaders: getCsrfHeaders
        });

        assert.strictEqual(retryResult.phase, UploadPhase.COMPLETED);
        assert.strictEqual(initCallCount, 2);
        assert.strictEqual(capturedInitBodies.length, 2);

        // Both generations must retain replacement flag and expected ID
        assert.strictEqual(capturedInitBodies[0].isReplacement, true);
        assert.strictEqual(capturedInitBodies[0].expectedCurrentVideoMediaAssetId, oldAssetId);
        assert.strictEqual(capturedInitBodies[1].isReplacement, true);
        assert.strictEqual(capturedInitBodies[1].expectedCurrentVideoMediaAssetId, oldAssetId);
        assert.strictEqual(capturedInitBodies[0].idempotencyKey, capturedInitBodies[1].idempotencyKey);
    });

    test('10. Replacement Fail-Closed: Rejects startAttempt immediately when isReplacement is true but expectedCurrentVideoMediaAssetId is missing', async () => {
        const coordinator = new UploadCoordinator();
        let fetchCalled = false;
        const fakeFetch = async () => {
            fetchCalled = true;
            return { ok: true };
        };

        await assert.rejects(
            async () => {
                await coordinator.startAttempt({
                    file: mockFile50MiB,
                    titleId: titleId,
                    entryId: entryId,
                    isReplacement: true,
                    expectedCurrentVideoMediaAssetId: null, // MISSING
                    fetchFn: fakeFetch,
                    cryptoFn: mockCryptoFn
                });
            },
            (err) => {
                assert.strictEqual(err.code, 'MISSING_EXPECTED_ASSET_ID');
                assert.ok(err.message.includes('expectedCurrentVideoMediaAssetId'));
                return true;
            }
        );

        assert.strictEqual(fetchCalled, false, 'No fetch calls must be dispatched when replacement ID is missing');
    });

    test('11. Replacement Conflict Error: Preserves attempt state when server returns SCREEN_ENTRY_VIDEO_CONFLICT', async () => {
        const coordinator = new UploadCoordinator();
        const oldAssetId = 'asset-stale-555';
        let capturedError = null;

        const fakeFetch = async (url, options = {}) => {
            if (url.includes('/video/upload-session') && options.method === 'POST') {
                return {
                    ok: false,
                    status: 400,
                    json: async () => ({
                        error: 'SCREEN_ENTRY_VIDEO_CONFLICT',
                        message: 'Xung đột video trên ScreenEntry: Video hiện tại không khớp với video mong đợi.'
                    })
                };
            }
            throw new Error('Unexpected URL: ' + url);
        };

        const result = await coordinator.startAttempt({
            file: mockFile50MiB,
            titleId: titleId,
            entryId: entryId,
            isReplacement: true,
            expectedCurrentVideoMediaAssetId: oldAssetId,
            fetchFn: fakeFetch,
            cryptoFn: mockCryptoFn,
            getCsrfHeaders: getCsrfHeaders,
            callbacks: {
                onError: (err) => { capturedError = err; }
            }
        });

        assert.strictEqual(result.phase, UploadPhase.FAILED);
        assert.strictEqual(result.failedAtPhase, UploadPhase.INITIATING);
        assert.ok(result.error.includes('Xung đột video'));
        assert.ok(capturedError, 'onError callback must be invoked');
        assert.ok(capturedError.message.includes('Xung đột video'));
    });

    // =========================================================================
    // MS-08E3-S2 LARGE FILE E2E, MEMORY BOUNDS & PROGRESS SEPARATION TESTS
    // =========================================================================

    test('12. MS-08E3-S2 Synthetic 50 GiB Contract Test: Mocked file hash, slicing, and backend/B2 network responses (not real browser/B2 transfer) with bounded memory slice traversal and complete playback URL', async () => {
        const coordinator = new UploadCoordinator();
        const callTrace = [];
        const progressTrace = [];
        let capturedInitPayload = null;
        let capturedCompletePayload = null;
        let capturedCsrfTokens = [];

        // 50 GiB synthetic file (2048 parts of 25 MiB)
        // Mocks file hash and network responses; does not execute real browser or live B2 transfer.
        const mock50GiBFile = {
            name: 'epic-movie-4k-50gib.mp4',
            size: MAX_VIDEO_SIZE_BYTES, // 53,687,091,200 bytes
            slice: function (start, end) {
                return {
                    start: start,
                    end: end,
                    size: end - start,
                    name: `slice-${start}-${end}`,
                    arrayBuffer: async () => new ArrayBuffer(end - start)
                };
            }
        };

        // DOM-level validation
        const constraints = validateFileConstraints(mock50GiBFile);
        assert.strictEqual(constraints.valid, true);
        assert.strictEqual(constraints.expectedParts, 2048);
        assert.strictEqual(constraints.formattedSize, '50.00 GiB');

        const fakeFetch = async (url, options = {}) => {
            callTrace.push({ url, method: options.method || 'GET' });

            if (options.headers && options.headers['X-CSRF-TOKEN']) {
                capturedCsrfTokens.push({ url, token: options.headers['X-CSRF-TOKEN'] });
            }

            if (url === `/admin/screen/titles/${titleId}/entries/${entryId}/video/upload-session` && options.method === 'POST') {
                capturedInitPayload = JSON.parse(options.body);
                return {
                    ok: true,
                    status: 200,
                    json: async () => ({
                        sessionId: 'sess-50gib-approved-001',
                        expectedPartCount: 2048,
                        partSizeBytes: PART_SIZE
                    })
                };
            }

            if (url.includes(`/parts/`) && url.endsWith('/url')) {
                const match = url.match(/\/parts\/(\d+)\/url/);
                const partNum = match[1];
                return {
                    ok: true,
                    status: 200,
                    json: async () => ({
                        uploadUrl: `https://b2.test/bucket-kiemlai/video-50gib/part-${partNum}`
                    })
                };
            }

            if (url.startsWith('https://b2.test/bucket-kiemlai/video-50gib/part-')) {
                assert.strictEqual(options.method, 'PUT');
                const match = url.match(/\/part-(\d+)/);
                const partNum = match[1];
                return {
                    ok: true,
                    status: 200,
                    headers: {
                        get: (h) => (h.toLowerCase() === 'etag' ? `"b2-etag-part-${partNum}"` : null)
                    }
                };
            }

            if (url === `/admin/screen/titles/${titleId}/entries/${entryId}/video/upload-session/sess-50gib-approved-001/complete` && options.method === 'POST') {
                capturedCompletePayload = JSON.parse(options.body);
                return {
                    ok: true,
                    status: 200,
                    json: async () => ({
                        videoMediaAssetId: 'asset-50gib-registered-uuid',
                        playbackUrl: `/admin/screen/titles/${titleId}/entries/${entryId}/video/content`,
                        status: 'COMPLETED',
                        message: 'Video đã được tải lên và gán thành công vào Screen Entry.'
                    })
                };
            }

            throw new Error('Unhandled E2E URL: ' + url);
        };

        const validMockSha256 = '50000000000000000000000000000000000000000000000000000000000050ab';

        const result = await coordinator.startAttempt({
            file: mock50GiBFile,
            titleId: titleId,
            entryId: entryId,
            fetchFn: fakeFetch,
            getCsrfHeaders: getCsrfHeaders,
            cryptoFn: async () => validMockSha256,
            callbacks: {
                onProgress: (pct, msg) => { progressTrace.push({ pct, msg }); }
            }
        });

        assert.strictEqual(result.phase, UploadPhase.COMPLETED);
        assert.strictEqual(result.isSettled, true);
        assert.strictEqual(result.registeredAssetId, 'asset-50gib-registered-uuid');
        assert.strictEqual(result.playbackUrl, `/admin/screen/titles/${titleId}/entries/${entryId}/video/content`);

        // Check initiation payload
        assert.strictEqual(capturedInitPayload.declaredSizeBytes, MAX_VIDEO_SIZE_BYTES);
        assert.strictEqual(capturedInitPayload.originalFilename, 'epic-movie-4k-50gib.mp4');

        // Check completion manifest: exactly 2048 parts
        assert.strictEqual(capturedCompletePayload.parts.length, 2048);
        assert.strictEqual(capturedCompletePayload.parts[0].partNumber, 1);
        assert.strictEqual(capturedCompletePayload.parts[0].eTag, '"b2-etag-part-1"');
        assert.strictEqual(capturedCompletePayload.parts[2047].partNumber, 2048);
        assert.strictEqual(capturedCompletePayload.parts[2047].eTag, '"b2-etag-part-2048"');
        assert.strictEqual(capturedCompletePayload.declaredContentHash, validMockSha256);
    });

    test('13. MS-08E3-S2 Large File UX: Progressive hashing updates distinct from multipart upload progress', async () => {
        const coordinator = new UploadCoordinator();
        const progressSteps = [];

        const syntheticFile = {
            name: 'ux-test.mp4',
            size: 6 * 1024 * 1024, // 6 MiB -> 3 chunks of 2 MiB
            slice: (start, end) => ({
                size: end - start,
                arrayBuffer: async () => new ArrayBuffer(end - start)
            })
        };

        const fakeFetch = async (url) => {
            if (url.endsWith('/video/upload-session')) {
                return {
                    ok: true,
                    json: async () => ({
                        sessionId: 'sess-ux-test',
                        expectedPartCount: 1,
                        partSizeBytes: 6 * 1024 * 1024
                    })
                };
            }
            if (url.includes('/parts/1/url')) {
                return { ok: true, json: async () => ({ uploadUrl: 'https://b2.test/part-1' }) };
            }
            if (url === 'https://b2.test/part-1') {
                return { ok: true, headers: { get: () => '"etag-ux"' } };
            }
            if (url.endsWith('/complete')) {
                return {
                    ok: true,
                    json: async () => ({
                        videoMediaAssetId: 'asset-ux',
                        playbackUrl: '/admin/screen/video/content'
                    })
                };
            }
            throw new Error('Unhandled: ' + url);
        };

        await coordinator.startAttempt({
            file: syntheticFile,
            titleId: 'title-ux',
            entryId: 'entry-ux',
            fetchFn: fakeFetch,
            callbacks: {
                onProgress: (pct, msg) => {
                    progressSteps.push({ pct, msg });
                }
            }
        });

        // Filter hashing vs upload messages
        const hashingMessages = progressSteps.filter(p => p.msg.includes('mã băm SHA-256'));
        const uploadMessages = progressSteps.filter(p => p.msg.includes('Đang truyền dữ liệu phần'));

        assert.ok(hashingMessages.length >= 3, 'Hashing must report progressive updates across chunks');
        assert.ok(uploadMessages.length >= 1, 'Upload must report progressive part uploads');
        assert.ok(progressSteps[progressSteps.length - 1].pct === 100, 'Final step must be 100%');
    });

    test('14. MS-08E3-S2 Fail-Closed: Zero-byte file rejected prior to session initiation', async () => {
        const coordinator = new UploadCoordinator();
        let fetchCalled = false;
        let capturedError = null;

        const emptyFile = {
            name: 'zero.mp4',
            size: 0
        };

        const fakeFetch = async () => {
            fetchCalled = true;
            return { ok: true };
        };

        await assert.rejects(
            async () => {
                await coordinator.startAttempt({
                    file: emptyFile,
                    titleId: 'title-zero',
                    entryId: 'entry-zero',
                    fetchFn: fakeFetch,
                    callbacks: {
                        onError: (err) => { capturedError = err; }
                    }
                });
            },
            /File video không được để trống \(0 byte\)/
        );

        assert.strictEqual(fetchCalled, false, 'Fetch must never be called for zero-byte file');
        assert.ok(capturedError !== null);
        assert.strictEqual(capturedError.code, 'ZERO_SIZE_FILE');
    });

    test('15. MS-08E3-S2 Fail-Closed: File exceeding 50 GiB rejected prior to session initiation', async () => {
        const coordinator = new UploadCoordinator();
        let fetchCalled = false;
        let capturedError = null;

        const oversizedFile = {
            name: 'too-big.mp4',
            size: MAX_VIDEO_SIZE_BYTES + 1
        };

        const fakeFetch = async () => {
            fetchCalled = true;
            return { ok: true };
        };

        await assert.rejects(
            async () => {
                await coordinator.startAttempt({
                    file: oversizedFile,
                    titleId: 'title-huge',
                    entryId: 'entry-huge',
                    fetchFn: fakeFetch,
                    callbacks: {
                        onError: (err) => { capturedError = err; }
                    }
                });
            },
            /vượt quá giới hạn tối đa cho phép \(50 GiB\)/
        );

        assert.strictEqual(fetchCalled, false, 'Fetch must never be called for file exceeding 50 GiB');
        assert.ok(capturedError !== null);
        assert.strictEqual(capturedError.code, 'FILE_EXCEEDS_MAX_SIZE');
    });

    test('16. MS-08E3-S2 Abort during incremental hashing terminates hashing cleanly without server side-effects', async () => {
        const coordinator = new UploadCoordinator();
        let fetchCalled = false;
        let abortedCallbackInvoked = false;

        const syntheticFile = {
            name: 'abort-during-hash.mp4',
            size: 20 * 1024 * 1024,
            slice: (start, end) => ({
                size: end - start,
                arrayBuffer: async () => {
                    // Trigger abort during hashing slice read
                    if (start > 0) {
                        await coordinator.abortAttempt({
                            titleId: 'title-abort',
                            entryId: 'entry-abort',
                            fetchFn: async () => ({ ok: true }),
                            callbacks: {
                                onAborted: () => { abortedCallbackInvoked = true; }
                            }
                        });
                    }
                    return new ArrayBuffer(end - start);
                }
            })
        };

        const fakeFetch = async () => {
            fetchCalled = true;
            return { ok: true };
        };

        const result = await coordinator.startAttempt({
            file: syntheticFile,
            titleId: 'title-abort',
            entryId: 'entry-abort',
            fetchFn: fakeFetch
        });

        assert.strictEqual(result.phase, UploadPhase.ABORTED);
        assert.strictEqual(result.isSettled, true);
        assert.strictEqual(result.sessionId, null);
        assert.strictEqual(fetchCalled, false, 'Server session must never be initiated when aborted during hashing');
        assert.strictEqual(abortedCallbackInvoked, true, 'onAborted callback must be invoked');
    });

    // =========================================================================
    // 17. IDEMPOTENCY KEY ISOLATION & FRESH UPLOAD GUARANTEES (R2-02 LIVE FIX)
    // =========================================================================

    test('17. Fresh upload without explicit idempotencyKey generates distinct unique keys across independent attempts', async () => {
        const coordinator = new UploadCoordinator();
        const capturedKeys = [];

        const fakeFetch = async (url, options) => {
            if (url.endsWith('/video/upload-session') && options.method === 'POST') {
                const body = JSON.parse(options.body);
                capturedKeys.push(body.idempotencyKey);
                return {
                    ok: true,
                    json: async () => ({
                        sessionId: 'sess-' + capturedKeys.length,
                        expectedPartCount: 1,
                        partSizeBytes: 52428800
                    })
                };
            }
            if (url.includes('/parts/1/url')) {
                return { ok: true, json: async () => ({ uploadUrl: 'https://r2.test/part-1' }) };
            }
            if (url === 'https://r2.test/part-1') {
                return { ok: true, headers: { get: () => '"etag-1"' } };
            }
            if (url.endsWith('/complete')) {
                return {
                    ok: true,
                    json: async () => ({
                        videoMediaAssetId: 'asset-fresh-' + capturedKeys.length,
                        playbackUrl: '/media/assets/asset-fresh-' + capturedKeys.length + '/content'
                    })
                };
            }
            throw new Error('Unhandled URL: ' + url);
        };

        // Attempt 1: First fresh logical upload
        await coordinator.startAttempt({
            file: mockFile50MiB,
            titleId: titleId,
            entryId: entryId,
            fetchFn: fakeFetch,
            cryptoFn: mockCryptoFn
        });

        // Dismiss settled attempt 1 to permit attempt 2
        coordinator.dismissSettledAttempt();

        // Attempt 2: Second fresh logical upload with the EXACT same file
        await coordinator.startAttempt({
            file: mockFile50MiB,
            titleId: titleId,
            entryId: entryId,
            fetchFn: fakeFetch,
            cryptoFn: mockCryptoFn
        });

        assert.strictEqual(capturedKeys.length, 2);
        assert.ok(capturedKeys[0].startsWith('screen-entry-' + entryId + '-upload-'));
        assert.ok(capturedKeys[1].startsWith('screen-entry-' + entryId + '-upload-'));
        assert.notStrictEqual(capturedKeys[0], capturedKeys[1], 'Consecutive fresh uploads must use distinct unique idempotency keys');
    });

    test('18. Explicit same-attempt retry preserves exact same idempotencyKey while fresh attempt uses a new key', async () => {
        const coordinator = new UploadCoordinator();
        const dispatchedKeys = [];
        let shouldFailFirst = true;

        const fakeFetch = async (url, options) => {
            if (url.endsWith('/video/upload-session') && options.method === 'POST') {
                const body = JSON.parse(options.body);
                dispatchedKeys.push(body.idempotencyKey);
                if (shouldFailFirst) {
                    shouldFailFirst = false;
                    throw new TypeError('Network connection reset');
                }
                return {
                    ok: true,
                    json: async () => ({
                        sessionId: 'sess-retry-ok',
                        expectedPartCount: 1,
                        partSizeBytes: 52428800
                    })
                };
            }
            if (url.includes('/parts/1/url')) {
                return { ok: true, json: async () => ({ uploadUrl: 'https://r2.test/part-1' }) };
            }
            if (url === 'https://r2.test/part-1') {
                return { ok: true, headers: { get: () => '"etag-1"' } };
            }
            if (url.endsWith('/complete')) {
                return {
                    ok: true,
                    json: async () => ({
                        videoMediaAssetId: 'asset-retry-ok',
                        playbackUrl: '/media/assets/asset-retry-ok/content'
                    })
                };
            }
            throw new Error('Unhandled URL: ' + url);
        };

        // Dispatch initial attempt (fails with network error)
        await coordinator.startAttempt({
            file: mockFile50MiB,
            titleId: titleId,
            entryId: entryId,
            fetchFn: fakeFetch,
            cryptoFn: mockCryptoFn
        });

        const initialKey = coordinator.getCurrentAttempt().idempotencyKey;

        // Controlled retry of SAME logical attempt via retryInitiation
        await coordinator.retryInitiation({
            titleId: titleId,
            entryId: entryId,
            fetchFn: fakeFetch,
            cryptoFn: mockCryptoFn
        });

        assert.strictEqual(dispatchedKeys.length, 2);
        assert.strictEqual(dispatchedKeys[0], initialKey);
        assert.strictEqual(dispatchedKeys[1], initialKey, 'retryInitiation must preserve identical idempotency key');
    });

    test('19. MEDIA_UPLOAD_INITIATION_UNCONFIRMED_CLAIM error handler suppresses retry and preserves unconfirmed fencing', () => {
        const mockUi = {
            retryInitBtn: { style: { display: 'inline-block' } },
            startUploadBtn: { disabled: false },
            fileInput: { disabled: false },
            abortUploadBtn: { style: { display: 'inline-block' } },
            alerts: [],
            showAlert: function (type, msg) { this.alerts.push({ type, msg }); }
        };

        const mockAttempt = {
            idempotencyKey: 'screen-entry-test-upload-unconfirmed-uuid',
            phase: UploadPhase.FAILED,
            failedAtPhase: UploadPhase.INITIATING,
            sessionId: null,
            errorCode: 'MEDIA_UPLOAD_INITIATION_UNCONFIRMED_CLAIM'
        };

        const error = new Error('Upload session has an unconfirmed earlier initiation claim with missing providerUploadId.');
        error.errorCode = 'MEDIA_UPLOAD_INITIATION_UNCONFIRMED_CLAIM';
        error.status = 409;

        handleUploadError(error, mockAttempt, mockUi);

        // Verification of invariant B3:
        assert.strictEqual(mockUi.retryInitBtn.style.display, 'none', 'Retry button must be strictly hidden');
        assert.strictEqual(mockUi.startUploadBtn.disabled, true, 'Start button must be disabled');
        assert.strictEqual(mockUi.fileInput.disabled, true, 'File input must be disabled');
        assert.strictEqual(mockUi.abortUploadBtn.style.display, 'none', 'Abort button must be hidden');
        assert.strictEqual(mockUi.alerts.length, 1);
        assert.strictEqual(mockUi.alerts[0].type, 'error');
        assert.ok(mockUi.alerts[0].msg.includes('đối soát trên hệ thống trước khi tiếp tục'));
        assert.ok(mockUi.alerts[0].msg.includes('Không thể tự động thử lại'));
    });
});
