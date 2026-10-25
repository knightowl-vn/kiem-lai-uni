const { describe, test } = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');
const {
    PART_SIZE,
    MAX_VIDEO_SIZE_BYTES,
    MAX_BROWSER_SMOKE_SIZE,
    HASH_CHUNK_SIZE,
    HASH_NAVIGATION_CANCELLED,
    HASH_ABORTED,
    UploadPhase,
    createAttempt,
    UploadCoordinator,
    IncrementalSha256,
    formatBytes,
    validateFileConstraints,
    handleUploadError
} = require('../../../main/resources/static/js/screen/screen-video-uploader.js');

describe('MS-08E2 Screen Video Upload Lifecycle & Browser Safety Tests', () => {

    const mockFile = {
        name: 'test-video.mp4',
        size: 52428800, // 50 MiB -> 2 parts of 25 MiB
        slice: (start, end) => ({ size: end - start })
    };

    const mockCryptoFn = async () => 'test-sha256-hash-1234567890abcdef';

    test('1. createAttempt generates a stable idempotencyKey prefixed with target entryId', () => {
        const entryId = 'entry-uuid-1';
        const attempt1 = createAttempt(mockFile, entryId);
        assert.ok(attempt1.idempotencyKey.startsWith('screen-entry-' + entryId));
        assert.strictEqual(attempt1.isCancelled, false);
        assert.strictEqual(attempt1.isSettled, false);
        assert.strictEqual(attempt1.phase, UploadPhase.IDLE);
    });

    test('2. startAttempt transmits idempotencyKey in the initiate session request payload', async () => {
        const coordinator = new UploadCoordinator();
        let capturedInitBody = null;

        const fakeFetch = async (url, options) => {
            if (url.endsWith('/video/upload-session') && options && options.method === 'POST') {
                capturedInitBody = JSON.parse(options.body);
                return {
                    ok: true,
                    json: async () => ({
                        sessionId: 'sess-123',
                        expectedPartCount: 1,
                        partSizeBytes: 52428800
                    })
                };
            }
            if (url.includes('/parts/1/url')) {
                return {
                    ok: true,
                    json: async () => ({ uploadUrl: 'https://b2.test/part-1' })
                };
            }
            if (url === 'https://b2.test/part-1') {
                return {
                    ok: true,
                    headers: { get: (name) => name === 'ETag' ? '"etag-chunk-1"' : null }
                };
            }
            if (url.endsWith('/complete')) {
                return {
                    ok: true,
                    json: async () => ({
                        videoMediaAssetId: 'asset-456',
                        playbackUrl: '/media/assets/asset-456/content'
                    })
                };
            }
            throw new Error('Unhandled URL in fakeFetch: ' + url);
        };

        const result = await coordinator.startAttempt({
            file: mockFile,
            titleId: 'title-1',
            entryId: 'entry-1',
            fetchFn: fakeFetch,
            cryptoFn: mockCryptoFn
        });

        assert.ok(capturedInitBody !== null, 'Initiation request was sent');
        assert.strictEqual(capturedInitBody.declaredSizeBytes, mockFile.size);
        assert.strictEqual(capturedInitBody.originalFilename, mockFile.name);
        assert.ok(capturedInitBody.idempotencyKey.startsWith('screen-entry-entry-1'));
        assert.strictEqual(result.phase, UploadPhase.COMPLETED);
        assert.strictEqual(result.isSettled, true);
        assert.strictEqual(result.registeredAssetId, 'asset-456');
    });

    test('3. Lost initiation response preserves idempotencyKey and does NOT generate a fresh key on retry', async () => {
        const coordinator = new UploadCoordinator();
        let initCallCount = 0;
        let sentKeys = [];

        const fakeFetchWithTimeoutFirst = async (url, options) => {
            if (url.endsWith('/video/upload-session') && options && options.method === 'POST') {
                initCallCount++;
                const body = JSON.parse(options.body);
                sentKeys.push(body.idempotencyKey);
                if (initCallCount === 1) {
                    // Simulate network drop / timeout where server received request but response was lost
                    throw new TypeError('Network request failed');
                }
                // Second call (retry) succeeds and returns the winning session
                return {
                    ok: true,
                    json: async () => ({
                        sessionId: 'sess-winner-789',
                        expectedPartCount: 1,
                        partSizeBytes: 52428800
                    })
                };
            }
            if (url.includes('/parts/1/url')) {
                return { ok: true, json: async () => ({ uploadUrl: 'https://b2.test/part-1' }) };
            }
            if (url === 'https://b2.test/part-1') {
                return { ok: true, headers: { get: () => '"etag-1"' } };
            }
            if (url.endsWith('/complete')) {
                return { ok: true, json: async () => ({ videoMediaAssetId: 'asset-789', playbackUrl: '/media/789' }) };
            }
            throw new Error('Unhandled URL: ' + url);
        };

        let reportedError = null;
        // First attempt: network failure
        await coordinator.startAttempt({
            file: mockFile,
            titleId: 'title-1',
            entryId: 'entry-1',
            callbacks: {
                onError: (err) => { reportedError = err; }
            },
            fetchFn: fakeFetchWithTimeoutFirst,
            cryptoFn: mockCryptoFn
        });

        const activeAttempt = coordinator.getCurrentAttempt();
        assert.ok(reportedError !== null);
        assert.strictEqual(activeAttempt.phase, UploadPhase.FAILED);
        assert.strictEqual(activeAttempt.failedAtPhase, UploadPhase.INITIATING);
        assert.strictEqual(activeAttempt.sessionId, null);
        const originalKey = activeAttempt.idempotencyKey;
        assert.ok(originalKey.length > 0);

        // Now perform retryInitiation: must reuse the exact same idempotencyKey!
        await coordinator.retryInitiation({
            titleId: 'title-1',
            entryId: 'entry-1',
            fetchFn: fakeFetchWithTimeoutFirst,
            cryptoFn: mockCryptoFn
        });

        assert.strictEqual(initCallCount, 2);
        assert.strictEqual(sentKeys.length, 2);
        assert.strictEqual(sentKeys[0], originalKey, 'First call used original key');
        assert.strictEqual(sentKeys[1], originalKey, 'Retry call reused the exact same key without generating a fresh key');
        assert.strictEqual(activeAttempt.phase, UploadPhase.COMPLETED);
        assert.strictEqual(activeAttempt.sessionId, 'sess-winner-789');
    });

    test('4. Cancel during chunk upload aborts in-flight fetch and prevents stale promises from mutating state or UI', async () => {
        const coordinator = new UploadCoordinator();
        let putPromiseDeferred = null;
        let putAborted = false;
        let progressCalls = [];
        let successCalled = false;

        const fakeFetch = async (url, options) => {
            if (url.endsWith('/video/upload-session') && options && options.method === 'POST') {
                return {
                    ok: true,
                    json: async () => ({
                        sessionId: 'sess-cancel-test',
                        expectedPartCount: 2,
                        partSizeBytes: 26214400
                    })
                };
            }
            if (url.includes('/parts/1/url')) {
                return { ok: true, json: async () => ({ uploadUrl: 'https://b2.test/part-1' }) };
            }
            if (url === 'https://b2.test/part-1') {
                // In-flight chunk PUT: register abort listener on signal
                return new Promise((resolve, reject) => {
                    putPromiseDeferred = { resolve, reject };
                    if (options && options.signal) {
                        options.signal.addEventListener('abort', () => {
                            putAborted = true;
                            reject(new Error('AbortError: The user aborted a request.'));
                        });
                    }
                });
            }
            if (url.endsWith('/abort')) {
                return {
                    ok: true,
                    json: async () => ({ sessionId: 'sess-cancel-test', status: 'ABORTED' })
                };
            }
            throw new Error('Unhandled URL: ' + url);
        };

        // Start attempt in background
        const startPromise = coordinator.startAttempt({
            file: mockFile,
            titleId: 'title-1',
            entryId: 'entry-1',
            callbacks: {
                onProgress: (p, msg) => { progressCalls.push({ p, msg }); },
                onSuccess: () => { successCalled = true; }
            },
            fetchFn: fakeFetch,
            cryptoFn: mockCryptoFn
        });

        // Advance promise ticks until chunk PUT is in flight
        while (!putPromiseDeferred) {
            await new Promise(r => setImmediate(r));
        }

        const attempt1 = coordinator.getCurrentAttempt();
        assert.strictEqual(attempt1.phase, UploadPhase.UPLOADING_PARTS);
        assert.ok(putPromiseDeferred !== null, 'Chunk PUT is in flight');

        // User clicks Abort
        let abortCallbackData = null;
        await coordinator.abortAttempt({
            titleId: 'title-1',
            entryId: 'entry-1',
            callbacks: {
                onAborted: (data) => { abortCallbackData = data; }
            },
            fetchFn: fakeFetch
        });

        assert.strictEqual(attempt1.isCancelled, true);
        assert.strictEqual(putAborted, true, 'AbortController triggered abort on in-flight PUT signal');
        assert.ok(abortCallbackData !== null, 'Abort callback received data');
        assert.strictEqual(abortCallbackData.status, 'ABORTED');
        assert.strictEqual(attempt1.phase, UploadPhase.ABORTED);
        assert.strictEqual(attempt1.isSettled, true);

        // Now simulate delayed resolution of the old PUT promise
        const progressCountBefore = progressCalls.length;
        try {
            putPromiseDeferred.resolve({
                ok: true,
                headers: { get: () => '"etag-stale"' }
            });
        } catch (e) { /* ignore */ }

        await startPromise;

        // Ensure stale promises did not trigger new progress or success
        assert.strictEqual(successCalled, false, 'Stale promise must never trigger onSuccess');
        assert.strictEqual(progressCalls.length, progressCountBefore, 'Stale promise must never update progress UI');
        assert.strictEqual(attempt1.phase, UploadPhase.ABORTED, 'Attempt state remains ABORTED');
    });

    test('5. Starting a new attempt while a previous attempt is unsettled is strictly rejected', async () => {
        const coordinator = new UploadCoordinator();
        let partUrlCalled = false;

        const fakeFetch = async (url) => {
            if (url.endsWith('/video/upload-session')) {
                return {
                    ok: true,
                    json: async () => ({
                        sessionId: 'sess-unsettled-1',
                        expectedPartCount: 1,
                        partSizeBytes: 52428800
                    })
                };
            }
            if (url.includes('/parts/1/url')) {
                partUrlCalled = true;
                return new Promise(() => {}); // stay in flight
            }
            throw new Error('Unhandled URL: ' + url);
        };

        // Start attempt 1
        coordinator.startAttempt({
            file: mockFile,
            titleId: 'title-1',
            entryId: 'entry-1',
            fetchFn: fakeFetch,
            cryptoFn: mockCryptoFn
        });

        while (!partUrlCalled) {
            await new Promise(r => setImmediate(r));
        }

        const attempt1 = coordinator.getCurrentAttempt();
        assert.strictEqual(attempt1.isSettled, false);

        // Attempting to start attempt 2 while attempt 1 is unsettled must throw
        await assert.rejects(
            async () => {
                await coordinator.startAttempt({
                    file: mockFile,
                    titleId: 'title-1',
                    entryId: 'entry-1',
                    fetchFn: fakeFetch,
                    cryptoFn: mockCryptoFn
                });
            },
            (err) => {
                assert.strictEqual(err.code, 'PREVIOUS_ATTEMPT_UNSETTLED');
                return true;
            }
        );
    });

    test('6. Abort failure does not settle attempt and preserves session ID', async () => {
        const coordinator = new UploadCoordinator();
        let partUrlCalled = false;

        const fakeFetch = async (url, options) => {
            if (url.endsWith('/video/upload-session') && options && options.method === 'POST') {
                return {
                    ok: true,
                    json: async () => ({
                        sessionId: 'sess-preserve-id',
                        expectedPartCount: 1,
                        partSizeBytes: 52428800
                    })
                };
            }
            if (url.includes('/parts/1/url')) {
                partUrlCalled = true;
                return new Promise(() => {}); // stay in flight
            }
            if (url.endsWith('/abort')) {
                // Server returns error on abort
                return {
                    ok: false,
                    status: 500,
                    json: async () => ({ message: 'B2 provider timeout on abort' })
                };
            }
            throw new Error('Unhandled URL: ' + url);
        };

        coordinator.startAttempt({
            file: mockFile,
            titleId: 'title-1',
            entryId: 'entry-1',
            fetchFn: fakeFetch,
            cryptoFn: mockCryptoFn
        });

        while (!partUrlCalled) {
            await new Promise(r => setImmediate(r));
        }

        const attempt = coordinator.getCurrentAttempt();
        assert.strictEqual(attempt.sessionId, 'sess-preserve-id');

        let reportedAbortError = null;
        await coordinator.abortAttempt({
            titleId: 'title-1',
            entryId: 'entry-1',
            callbacks: {
                onError: (err) => { reportedAbortError = err; }
            },
            fetchFn: fakeFetch
        });

        assert.ok(reportedAbortError !== null);
        assert.strictEqual(attempt.sessionId, 'sess-preserve-id', 'Session ID must NOT be cleared on failed abort');
        assert.strictEqual(attempt.isSettled, false, 'Attempt must NOT be marked settled on failed abort');
    });

    test('7. Dismissing settled attempt allows starting a clean second attempt', async () => {
        const coordinator = new UploadCoordinator();
        let partUrlCalled = false;

        const fakeFetch = async (url, options) => {
            if (url.endsWith('/video/upload-session') && options && options.method === 'POST') {
                return {
                    ok: true,
                    json: async () => ({
                        sessionId: 'sess-settled-1',
                        expectedPartCount: 1,
                        partSizeBytes: 52428800
                    })
                };
            }
            if (url.includes('/parts/1/url')) {
                partUrlCalled = true;
                return new Promise(() => {});
            }
            if (url.endsWith('/abort')) {
                return {
                    ok: true,
                    json: async () => ({ sessionId: 'sess-settled-1', status: 'ABORTED' })
                };
            }
            throw new Error('Unhandled URL: ' + url);
        };

        coordinator.startAttempt({
            file: mockFile,
            titleId: 'title-1',
            entryId: 'entry-1',
            fetchFn: fakeFetch,
            cryptoFn: mockCryptoFn
        });

        while (!partUrlCalled) {
            await new Promise(r => setImmediate(r));
        }

        await coordinator.abortAttempt({
            titleId: 'title-1',
            entryId: 'entry-1',
            fetchFn: fakeFetch
        });

        const attempt1 = coordinator.getCurrentAttempt();
        assert.strictEqual(attempt1.phase, UploadPhase.ABORTED);
        assert.strictEqual(attempt1.isSettled, true);

        // Dismiss settled attempt
        const dismissed = coordinator.dismissSettledAttempt();
        assert.strictEqual(dismissed, true);
        assert.strictEqual(coordinator.getCurrentAttempt(), null);

        // Can now start attempt 2 cleanly
        const attempt2 = createAttempt(mockFile, 'entry-1');
        assert.notStrictEqual(attempt2.id, attempt1.id);
        assert.notStrictEqual(attempt2.idempotencyKey, attempt1.idempotencyKey);
    });

    // =========================================================================
    // FIX 1 REPRODUCTION & VERIFICATION TESTS (Error Phase Loss)
    // =========================================================================

    test('8. FIX 1: Chunk PUT failure preserves failedAtPhase=UPLOADING_PARTS, phase=FAILED, isSettled=false, and retains sessionId for safe manual abort', async () => {
        const coordinator = new UploadCoordinator();
        let errorReported = null;

        const fakeFetchWithPutFailure = async (url, options) => {
            if (url.endsWith('/video/upload-session') && options && options.method === 'POST') {
                return {
                    ok: true,
                    json: async () => ({
                        sessionId: 'sess-part-fail-1',
                        expectedPartCount: 1,
                        partSizeBytes: 52428800
                    })
                };
            }
            if (url.includes('/parts/1/url')) {
                return { ok: true, json: async () => ({ uploadUrl: 'https://b2.test/part-1' }) };
            }
            if (url === 'https://b2.test/part-1') {
                // Provider rejects the chunk PUT (e.g. 503 or CORS failure)
                return { ok: false, status: 503 };
            }
            throw new Error('Unhandled URL: ' + url);
        };

        await coordinator.startAttempt({
            file: mockFile,
            titleId: 'title-1',
            entryId: 'entry-1',
            callbacks: {
                onError: (err, attempt) => { errorReported = { err, attempt }; }
            },
            fetchFn: fakeFetchWithPutFailure,
            cryptoFn: mockCryptoFn
        });

        assert.ok(errorReported !== null);
        const attempt = errorReported.attempt;
        assert.strictEqual(attempt.phase, UploadPhase.FAILED);
        assert.strictEqual(attempt.failedAtPhase, UploadPhase.UPLOADING_PARTS, 'failedAtPhase must preserve UPLOADING_PARTS');
        assert.strictEqual(attempt.isSettled, false, 'Unfinished upload with allocated session is NOT settled');
        assert.strictEqual(attempt.sessionId, 'sess-part-fail-1', 'Session ID must be preserved for manual abort');

        // Cannot start replacement attempt while previous failed upload has unsettled session
        await assert.rejects(
            async () => {
                await coordinator.startAttempt({
                    file: mockFile,
                    titleId: 'title-1',
                    entryId: 'entry-1',
                    fetchFn: fakeFetchWithPutFailure,
                    cryptoFn: mockCryptoFn
                });
            },
            (err) => err.code === 'PREVIOUS_ATTEMPT_UNSETTLED'
        );
    });

    test('9. FIX 1: Multipart completion failure preserves failedAtPhase=COMPLETING, phase=FAILED, isSettled=false, and retains sessionId', async () => {
        const coordinator = new UploadCoordinator();
        let errorReported = null;

        const fakeFetchWithCompleteFailure = async (url, options) => {
            if (url.endsWith('/video/upload-session') && options && options.method === 'POST') {
                return {
                    ok: true,
                    json: async () => ({
                        sessionId: 'sess-complete-fail-1',
                        expectedPartCount: 1,
                        partSizeBytes: 52428800
                    })
                };
            }
            if (url.includes('/parts/1/url')) {
                return { ok: true, json: async () => ({ uploadUrl: 'https://b2.test/part-1' }) };
            }
            if (url === 'https://b2.test/part-1') {
                return { ok: true, headers: { get: () => '"etag-1"' } };
            }
            if (url.endsWith('/complete')) {
                // Server/B2 fails during completion assembly
                return { ok: false, status: 500, json: async () => ({ message: 'B2 assembly timeout' }) };
            }
            throw new Error('Unhandled URL: ' + url);
        };

        await coordinator.startAttempt({
            file: mockFile,
            titleId: 'title-1',
            entryId: 'entry-1',
            callbacks: {
                onError: (err, attempt) => { errorReported = { err, attempt }; }
            },
            fetchFn: fakeFetchWithCompleteFailure,
            cryptoFn: mockCryptoFn
        });

        assert.ok(errorReported !== null);
        const attempt = errorReported.attempt;
        assert.strictEqual(attempt.phase, UploadPhase.FAILED);
        assert.strictEqual(attempt.failedAtPhase, UploadPhase.COMPLETING, 'failedAtPhase must preserve COMPLETING');
        assert.strictEqual(attempt.isSettled, false, 'Ambiguous completion error must NOT be marked settled');
        assert.strictEqual(attempt.sessionId, 'sess-complete-fail-1');
    });

    // =========================================================================
    // FIX 2 REPRODUCTION & VERIFICATION TESTS (Cancel During Uncertain Initiation)
    // =========================================================================

    test('10. FIX 2A: Cancel before initiation dispatch marks attempt safely ABORTED and settled', async () => {
        const coordinator = new UploadCoordinator();
        let abortCallbackData = null;
        let hashingDeferred = null;

        // Custom crypto function that stays deferred so we can cancel during HASHING
        const slowCryptoFn = () => new Promise(resolve => { hashingDeferred = resolve; });

        const fakeFetch = async () => {
            throw new Error('Fetch should not be called if cancelled during hashing');
        };

        const startPromise = coordinator.startAttempt({
            file: mockFile,
            titleId: 'title-1',
            entryId: 'entry-1',
            fetchFn: fakeFetch,
            cryptoFn: slowCryptoFn
        });

        while (!hashingDeferred) {
            await new Promise(r => setImmediate(r));
        }

        const attempt = coordinator.getCurrentAttempt();
        assert.strictEqual(attempt.phase, UploadPhase.HASHING);
        assert.strictEqual(attempt.initiationDispatched, false, 'Initiation was NOT dispatched yet');

        // Cancel during hashing
        await coordinator.abortAttempt({
            titleId: 'title-1',
            entryId: 'entry-1',
            callbacks: {
                onAborted: (data) => { abortCallbackData = data; }
            },
            fetchFn: fakeFetch
        });

        assert.strictEqual(attempt.phase, UploadPhase.ABORTED);
        assert.strictEqual(attempt.isSettled, true, 'Pre-dispatch cancellation is safely settled');
        assert.strictEqual(attempt.sessionId, null);
        assert.ok(abortCallbackData !== null);
        assert.strictEqual(abortCallbackData.status, 'ABORTED');

        // Resolve deferred hash - must be ignored
        hashingDeferred('late-hash');
        await startPromise;
        assert.strictEqual(attempt.phase, UploadPhase.ABORTED);
    });

    test('11. FIX 2B: Cancel after POST dispatch but before response marks isSettled=false, preserves idempotencyKey, and signals uncertain initiation', async () => {
        const coordinator = new UploadCoordinator();
        let initDeferred = null;
        let errorReported = null;

        const fakeFetchWithDeferredInit = async (url, options) => {
            if (url.endsWith('/video/upload-session') && options && options.method === 'POST') {
                return new Promise((resolve, reject) => {
                    initDeferred = { resolve, reject };
                    if (options.signal) {
                        options.signal.addEventListener('abort', () => {
                            reject(new Error('AbortError: fetch aborted'));
                        });
                    }
                });
            }
            throw new Error('Unhandled URL: ' + url);
        };

        const startPromise = coordinator.startAttempt({
            file: mockFile,
            titleId: 'title-1',
            entryId: 'entry-1',
            callbacks: {
                onError: (err, attempt) => { errorReported = { err, attempt }; }
            },
            fetchFn: fakeFetchWithDeferredInit,
            cryptoFn: mockCryptoFn
        });

        while (!initDeferred) {
            await new Promise(r => setImmediate(r));
        }

        const attempt = coordinator.getCurrentAttempt();
        assert.strictEqual(attempt.phase, UploadPhase.INITIATING);
        assert.strictEqual(attempt.initiationDispatched, true, 'Initiation POST was dispatched');
        assert.strictEqual(attempt.sessionId, null, 'Response has not arrived yet');

        // Operator cancels while initiation request is in-flight
        await coordinator.abortAttempt({
            titleId: 'title-1',
            entryId: 'entry-1',
            callbacks: {
                onError: (err) => { errorReported = { err, attempt }; }
            },
            fetchFn: fakeFetchWithDeferredInit
        });

        await startPromise;

        assert.ok(errorReported !== null);
        assert.strictEqual(errorReported.err.isUncertainInitiation, true, 'Must report isUncertainInitiation');
        assert.strictEqual(attempt.isSettled, false, 'Must NOT be marked settled because server may have allocated session');
        assert.strictEqual(attempt.failedAtPhase, UploadPhase.INITIATING);
        assert.ok(attempt.idempotencyKey.startsWith('screen-entry-entry-1'), 'Idempotency key must be preserved');
    });

    test('12. FIX 2C & 2E: Server creates session but response is lost; replacement upload is blocked until resolved', async () => {
        const coordinator = new UploadCoordinator();
        let errorReported = null;

        const fakeFetchLostResponse = async (url, options) => {
            if (url.endsWith('/video/upload-session') && options && options.method === 'POST') {
                // Server processed but connection dropped
                throw new TypeError('Failed to fetch');
            }
            throw new Error('Unhandled URL: ' + url);
        };

        await coordinator.startAttempt({
            file: mockFile,
            titleId: 'title-1',
            entryId: 'entry-1',
            callbacks: {
                onError: (err, attempt) => { errorReported = { err, attempt }; }
            },
            fetchFn: fakeFetchLostResponse,
            cryptoFn: mockCryptoFn
        });

        assert.ok(errorReported !== null);
        assert.strictEqual(errorReported.err.isLostResponse, true);
        const attempt = coordinator.getCurrentAttempt();
        assert.strictEqual(attempt.isSettled, false, 'Lost response attempt is NOT settled');

        // FIX 2E: No replacement upload may start while initiation outcome is unresolved
        await assert.rejects(
            async () => {
                await coordinator.startAttempt({
                    file: mockFile,
                    titleId: 'title-1',
                    entryId: 'entry-1',
                    fetchFn: fakeFetchLostResponse,
                    cryptoFn: mockCryptoFn
                });
            },
            (err) => err.code === 'PREVIOUS_ATTEMPT_UNSETTLED'
        );
    });

    test('13. FIX 2D: Late response arriving after local cancellation records sessionId for safe server-side cleanup', async () => {
        const coordinator = new UploadCoordinator();
        let initDeferred = null;

        const fakeFetch = async (url, options) => {
            if (url.endsWith('/video/upload-session') && options && options.method === 'POST') {
                return new Promise((resolve) => {
                    initDeferred = resolve;
                });
            }
            throw new Error('Unhandled URL: ' + url);
        };

        coordinator.startAttempt({
            file: mockFile,
            titleId: 'title-1',
            entryId: 'entry-1',
            fetchFn: fakeFetch,
            cryptoFn: mockCryptoFn
        });

        while (!initDeferred) {
            await new Promise(r => setImmediate(r));
        }

        const attempt = coordinator.getCurrentAttempt();
        assert.strictEqual(attempt.initiationDispatched, true);

        // Cancel while request in flight
        await coordinator.abortAttempt({
            titleId: 'title-1',
            entryId: 'entry-1',
            fetchFn: fakeFetch
        });

        assert.strictEqual(attempt.isCancelled, true);

        // Server response arrives late
        initDeferred({
            ok: true,
            json: async () => ({
                sessionId: 'sess-late-recovered-999',
                expectedPartCount: 2,
                partSizeBytes: 26214400
            })
        });

        await new Promise(r => setImmediate(r));
        await new Promise(r => setImmediate(r));

        // The coordinator captured the recovered sessionId even though cancelled!
        assert.strictEqual(attempt.sessionId, 'sess-late-recovered-999', 'Late response sessionId must be retained for remote cleanup');
        assert.strictEqual(attempt.isSettled, false, 'Remains unsettled until explicit server abort is confirmed');
    });

    // =========================================================================
    // FIX 3 REPRODUCTION & VERIFICATION TESTS (SHA-256 Must Fail Closed)
    // =========================================================================

    test('14. FIX 3: Missing Web Crypto support fails closed, throws explicit error, and never fabricates synthetic zero hash', async () => {
        const coordinator = new UploadCoordinator();
        let errorReported = null;
        let fetchDispatched = false;

        const fakeFetch = async () => {
            fetchDispatched = true;
            throw new Error('Should never reach fetch if crypto is unavailable');
        };

        // File without cryptoFn and with invalid/empty SubtleCrypto
        const mockFileWithoutCrypto = {
            name: 'test.mp4',
            size: 1024,
            arrayBuffer: async () => new ArrayBuffer(1024)
        };

        // Temporarily hide SubtleCrypto
        const originalCrypto = globalThis.crypto;
        try {
            delete globalThis.crypto;

            await coordinator.startAttempt({
                file: mockFileWithoutCrypto,
                titleId: 'title-1',
                entryId: 'entry-1',
                callbacks: {
                    onError: (err, attempt) => { errorReported = { err, attempt }; }
                },
                fetchFn: fakeFetch
                // No cryptoFn passed
            });
        } finally {
            globalThis.crypto = originalCrypto;
        }

        assert.ok(errorReported !== null);
        assert.ok(errorReported.err.message.includes('Web Crypto API (crypto.subtle) is not supported'), 'Must throw explicit crypto error');
        assert.strictEqual(fetchDispatched, false, 'Must fail closed: zero multipart requests dispatched to server');
        const attempt = coordinator.getCurrentAttempt();
        assert.strictEqual(attempt.phase, UploadPhase.FAILED);
        assert.strictEqual(attempt.failedAtPhase, UploadPhase.HASHING);
        assert.strictEqual(attempt.initiationDispatched, false);
        assert.strictEqual(attempt.isSettled, true, 'Pre-dispatch failure without server session is safely settled');
    });

    // =========================================================================
    // MS-08E2 TARGETED CANCELLATION & RETRY GENERATION FENCING TESTS
    // =========================================================================

    test('15. Retry initiation after abort uses fresh non-aborted AbortController and succeeds without AbortError', async () => {
        const coordinator = new UploadCoordinator();
        let initCallCount = 0;
        let initDeferred = null;
        let sentKeys = [];
        let signalsUsed = [];

        const fakeFetch = async (url, options) => {
            // Emulate standard W3C fetch behavior: if options.signal is already aborted, throw AbortError immediately
            if (options && options.signal && options.signal.aborted) {
                const abortErr = new Error('The user aborted a request.');
                abortErr.name = 'AbortError';
                throw abortErr;
            }

            if (url.endsWith('/video/upload-session') && options && options.method === 'POST') {
                initCallCount++;
                const body = JSON.parse(options.body);
                sentKeys.push(body.idempotencyKey);
                signalsUsed.push(options.signal);

                if (initCallCount === 1) {
                    // First attempt hangs in flight until aborted
                    return new Promise((resolve) => {
                        initDeferred = resolve;
                    });
                }
                // Second call (retry) returns session successfully
                return {
                    ok: true,
                    json: async () => ({
                        sessionId: 'sess-retry-recovered-456',
                        expectedPartCount: 1,
                        partSizeBytes: 52428800
                    })
                };
            }
            if (url.includes('/parts/1/url')) {
                return {
                    ok: true,
                    json: async () => ({ uploadUrl: 'https://b2.test/part-1' })
                };
            }
            if (url === 'https://b2.test/part-1') {
                return {
                    ok: true,
                    headers: { get: (name) => name === 'ETag' ? '"etag-retry-1"' : null }
                };
            }
            if (url.endsWith('/complete')) {
                return {
                    ok: true,
                    json: async () => ({
                        videoMediaAssetId: 'asset-retry-recovered',
                        playbackUrl: '/media/assets/asset-retry-recovered/content'
                    })
                };
            }
            throw new Error('Unhandled URL: ' + url);
        };

        // Start Generation 1
        coordinator.startAttempt({
            file: mockFile,
            titleId: 'title-1',
            entryId: 'entry-1',
            fetchFn: fakeFetch,
            cryptoFn: mockCryptoFn
        });

        while (!initDeferred) {
            await new Promise(r => setImmediate(r));
        }

        const attempt = coordinator.getCurrentAttempt();
        assert.strictEqual(attempt.generation, 1);
        assert.strictEqual(attempt.initiationDispatched, true);
        const originalIdempotencyKey = attempt.idempotencyKey;

        // Operator cancels while initiation is in flight
        await coordinator.abortAttempt({
            titleId: 'title-1',
            entryId: 'entry-1',
            fetchFn: fakeFetch
        });

        assert.strictEqual(attempt.isCancelled, true);
        assert.strictEqual(signalsUsed[0].aborted, true, 'Generation 1 signal must be aborted');

        // Operator clicks "Retry Initiation"
        const retryResult = await coordinator.retryInitiation({
            titleId: 'title-1',
            entryId: 'entry-1',
            fetchFn: fakeFetch,
            cryptoFn: mockCryptoFn
        });

        assert.strictEqual(retryResult.phase, UploadPhase.COMPLETED);
        assert.strictEqual(retryResult.isSettled, true);
        assert.strictEqual(retryResult.generation, 2, 'Generation counter must advance to 2');
        assert.strictEqual(signalsUsed.length, 2, 'Two initiation POSTs dispatched');
        assert.strictEqual(signalsUsed[1].aborted, false, 'Generation 2 signal must NOT be pre-aborted');
        assert.strictEqual(sentKeys[0], originalIdempotencyKey, 'Original idempotencyKey sent on first try');
        assert.strictEqual(sentKeys[1], originalIdempotencyKey, 'Exact same idempotencyKey reused on retry');
        assert.strictEqual(retryResult.registeredAssetId, 'asset-retry-recovered');
    });

    test('16. Late response from aborted Generation 1 arriving after Generation 2 retry is fenced out and does not corrupt Generation 2 state', async () => {
        const coordinator = new UploadCoordinator();
        let initDeferred1 = null;
        let initDeferred2 = null;
        let initCallCount = 0;

        const fakeFetch = async (url, options) => {
            if (options && options.signal && options.signal.aborted) {
                const abortErr = new Error('The user aborted a request.');
                abortErr.name = 'AbortError';
                throw abortErr;
            }

            if (url.endsWith('/video/upload-session') && options && options.method === 'POST') {
                initCallCount++;
                if (initCallCount === 1) {
                    return new Promise((resolve) => {
                        initDeferred1 = resolve;
                    });
                }
                return new Promise((resolve) => {
                    initDeferred2 = resolve;
                });
            }
            if (url.includes('/parts/1/url')) {
                return {
                    ok: true,
                    json: async () => ({ uploadUrl: 'https://b2.test/part-1' })
                };
            }
            if (url === 'https://b2.test/part-1') {
                return {
                    ok: true,
                    headers: { get: (name) => name === 'ETag' ? '"etag-gen2"' : null }
                };
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

        // Start Generation 1
        coordinator.startAttempt({
            file: mockFile,
            titleId: 'title-1',
            entryId: 'entry-1',
            fetchFn: fakeFetch,
            cryptoFn: mockCryptoFn
        });

        while (!initDeferred1) {
            await new Promise(r => setImmediate(r));
        }

        const attempt = coordinator.getCurrentAttempt();
        assert.strictEqual(attempt.generation, 1);

        // Operator cancels Generation 1
        await coordinator.abortAttempt({
            titleId: 'title-1',
            entryId: 'entry-1',
            fetchFn: fakeFetch
        });

        // Operator immediately clicks "Retry Initiation" (starts Generation 2)
        const retryPromise = coordinator.retryInitiation({
            titleId: 'title-1',
            entryId: 'entry-1',
            fetchFn: fakeFetch,
            cryptoFn: mockCryptoFn
        });

        while (!initDeferred2) {
            await new Promise(r => setImmediate(r));
        }

        assert.strictEqual(attempt.generation, 2, 'Attempt is now on Generation 2');

        // Now Generation 1's late response arrives from server!
        initDeferred1({
            ok: true,
            json: async () => ({
                sessionId: 'sess-stale-gen1',
                expectedPartCount: 1,
                partSizeBytes: 52428800
            })
        });

        await new Promise(r => setImmediate(r));
        await new Promise(r => setImmediate(r));

        // Generation 1's late response MUST BE FENCED OUT: it must not set attempt.sessionId
        assert.notStrictEqual(attempt.sessionId, 'sess-stale-gen1', 'Stale Generation 1 response must be fenced out and never overwrite state');

        // Now Generation 2's valid response arrives
        initDeferred2({
            ok: true,
            json: async () => ({
                sessionId: 'sess-valid-gen2',
                expectedPartCount: 1,
                partSizeBytes: 52428800
            })
        });

        const finalResult = await retryPromise;
        assert.strictEqual(finalResult.phase, UploadPhase.COMPLETED);
        assert.strictEqual(finalResult.sessionId, 'sess-valid-gen2', 'Session ID must come from the winning Generation 2 response');
        assert.strictEqual(finalResult.registeredAssetId, 'asset-gen2-winner');
    });

    test('17. Delayed network AbortError from Generation 1 does NOT trigger onError or fail Generation 2 retry', async () => {
        const coordinator = new UploadCoordinator();
        let initDeferredReject1 = null;
        let initCallCount = 0;
        let errorsReported = [];

        const fakeFetch = async (url, options) => {
            if (url.endsWith('/video/upload-session') && options && options.method === 'POST') {
                initCallCount++;
                if (initCallCount === 1) {
                    return new Promise((_, reject) => {
                        initDeferredReject1 = reject;
                    });
                }
                return {
                    ok: true,
                    json: async () => ({
                        sessionId: 'sess-gen2-success',
                        expectedPartCount: 1,
                        partSizeBytes: 52428800
                    })
                };
            }
            if (url.includes('/parts/1/url')) {
                return {
                    ok: true,
                    json: async () => ({ uploadUrl: 'https://b2.test/part-1' })
                };
            }
            if (url === 'https://b2.test/part-1') {
                return {
                    ok: true,
                    headers: { get: (name) => name === 'ETag' ? '"etag-gen2"' : null }
                };
            }
            if (url.endsWith('/complete')) {
                return {
                    ok: true,
                    json: async () => ({
                        videoMediaAssetId: 'asset-gen2-success',
                        playbackUrl: '/media/assets/asset-gen2-success/content'
                    })
                };
            }
            throw new Error('Unhandled URL: ' + url);
        };

        coordinator.startAttempt({
            file: mockFile,
            titleId: 'title-1',
            entryId: 'entry-1',
            callbacks: {
                onError: (err) => { errorsReported.push(err); }
            },
            fetchFn: fakeFetch,
            cryptoFn: mockCryptoFn
        });

        while (!initDeferredReject1) {
            await new Promise(r => setImmediate(r));
        }

        // Cancel Generation 1
        await coordinator.abortAttempt({
            titleId: 'title-1',
            entryId: 'entry-1',
            callbacks: {
                onError: (err) => { errorsReported.push(err); }
            },
            fetchFn: fakeFetch
        });

        // Exactly one uncertain error from abort
        assert.strictEqual(errorsReported.length, 1);
        assert.strictEqual(errorsReported[0].isUncertainInitiation, true);

        // Start Generation 2 retry
        const retryPromise = coordinator.retryInitiation({
            titleId: 'title-1',
            entryId: 'entry-1',
            callbacks: {
                onError: (err) => { errorsReported.push(err); }
            },
            fetchFn: fakeFetch,
            cryptoFn: mockCryptoFn
        });

        // Generation 1's fetch promise now throws AbortError after Generation 2 has started
        const lateAbortError = new Error('The user aborted a request.');
        lateAbortError.name = 'AbortError';
        initDeferredReject1(lateAbortError);

        await new Promise(r => setImmediate(r));
        await new Promise(r => setImmediate(r));

        const finalResult = await retryPromise;
        assert.strictEqual(finalResult.phase, UploadPhase.COMPLETED);
        // Errors count must still be 1! Late Generation 1 AbortError did not call onError on Generation 2
        assert.strictEqual(errorsReported.length, 1, 'Late Generation 1 AbortError must NOT report error to Generation 2 callbacks');
    });

    test('18. Monotonic generation advancement and idempotencyKey preservation across multiple uncertain attempts', async () => {
        const coordinator = new UploadCoordinator();
        let callCount = 0;
        let sentKeys = [];

        const fakeFetch = async (url, options) => {
            if (options && options.signal && options.signal.aborted) {
                const abortErr = new Error('The user aborted a request.');
                abortErr.name = 'AbortError';
                throw abortErr;
            }

            if (url.endsWith('/video/upload-session') && options && options.method === 'POST') {
                callCount++;
                const body = JSON.parse(options.body);
                sentKeys.push(body.idempotencyKey);
                if (callCount < 3) {
                    throw new TypeError('Connection timeout');
                }
                return {
                    ok: true,
                    json: async () => ({
                        sessionId: 'sess-gen3-win',
                        expectedPartCount: 1,
                        partSizeBytes: 52428800
                    })
                };
            }
            if (url.includes('/parts/1/url')) {
                return {
                    ok: true,
                    json: async () => ({ uploadUrl: 'https://b2.test/part-1' })
                };
            }
            if (url === 'https://b2.test/part-1') {
                return {
                    ok: true,
                    headers: { get: (name) => name === 'ETag' ? '"etag-gen3"' : null }
                };
            }
            if (url.endsWith('/complete')) {
                return {
                    ok: true,
                    json: async () => ({
                        videoMediaAssetId: 'asset-gen3-win',
                        playbackUrl: '/media/assets/asset-gen3-win/content'
                    })
                };
            }
            throw new Error('Unhandled URL: ' + url);
        };

        // Generation 1: connection drop
        await coordinator.startAttempt({
            file: mockFile,
            titleId: 'title-1',
            entryId: 'entry-1',
            fetchFn: fakeFetch,
            cryptoFn: mockCryptoFn
        });

        const attempt = coordinator.getCurrentAttempt();
        assert.strictEqual(attempt.generation, 1);
        assert.strictEqual(attempt.isSettled, false);
        const originalKey = attempt.idempotencyKey;

        // Generation 2: connection drop again
        await coordinator.retryInitiation({
            titleId: 'title-1',
            entryId: 'entry-1',
            fetchFn: fakeFetch,
            cryptoFn: mockCryptoFn
        });

        assert.strictEqual(attempt.generation, 2);
        assert.strictEqual(attempt.isSettled, false);
        assert.strictEqual(attempt.idempotencyKey, originalKey);

        // Generation 3: succeeds
        const result = await coordinator.retryInitiation({
            titleId: 'title-1',
            entryId: 'entry-1',
            fetchFn: fakeFetch,
            cryptoFn: mockCryptoFn
        });

        assert.strictEqual(attempt.generation, 3);
        assert.strictEqual(result.phase, UploadPhase.COMPLETED);
        assert.strictEqual(result.isSettled, true);
        assert.strictEqual(result.sessionId, 'sess-gen3-win');
        assert.strictEqual(sentKeys.length, 3);
        assert.strictEqual(sentKeys[0], originalKey);
        assert.strictEqual(sentKeys[1], originalKey);
        assert.strictEqual(sentKeys[2], originalKey);
    });

    // =========================================================================
    // MS-08E2 ASYNC RESPONSE BODY GENERATION FENCING REGRESSION TESTS
    // =========================================================================

    test('19. ASYNC BODY RACE: Generation 1 fetch resolves immediately but response.json() is delayed; retry starts Generation 2, and Generation 1 JSON resolving does NOT overwrite Generation 2 session state', async () => {
        const coordinator = new UploadCoordinator();
        let initCallCount = 0;
        let deferredJsonGen1 = null;
        let deferredJsonGen2 = null;

        const fakeFetch = async (url, options) => {
            if (options && options.signal && options.signal.aborted) {
                const abortErr = new Error('The user aborted a request.');
                abortErr.name = 'AbortError';
                throw abortErr;
            }

            if (url.endsWith('/video/upload-session') && options && options.method === 'POST') {
                initCallCount++;
                if (initCallCount === 1) {
                    // HTTP 200 response resolves immediately; only the JSON body stream is delayed
                    return {
                        ok: true,
                        json: () => new Promise(resolve => { deferredJsonGen1 = resolve; })
                    };
                }
                return {
                    ok: true,
                    json: () => new Promise(resolve => { deferredJsonGen2 = resolve; })
                };
            }
            if (url.includes('/parts/1/url')) {
                return {
                    ok: true,
                    json: async () => ({ uploadUrl: 'https://b2.test/part-1' })
                };
            }
            if (url === 'https://b2.test/part-1') {
                return {
                    ok: true,
                    headers: { get: (name) => name === 'ETag' ? '"etag-gen2"' : null }
                };
            }
            if (url.endsWith('/complete')) {
                return {
                    ok: true,
                    json: async () => ({
                        videoMediaAssetId: 'asset-gen2-success',
                        playbackUrl: '/media/assets/asset-gen2-success/content'
                    })
                };
            }
            throw new Error('Unhandled URL: ' + url);
        };

        // Start Generation 1
        coordinator.startAttempt({
            file: mockFile,
            titleId: 'title-1',
            entryId: 'entry-1',
            fetchFn: fakeFetch,
            cryptoFn: mockCryptoFn
        });

        // Wait until Generation 1's fetch has resolved and it has entered response.json()
        while (!deferredJsonGen1) {
            await new Promise(r => setImmediate(r));
        }

        const attempt = coordinator.getCurrentAttempt();
        assert.strictEqual(attempt.generation, 1);
        assert.strictEqual(attempt.sessionId, null, 'Session ID not yet set while JSON is pending');

        // Operator cancels Generation 1
        await coordinator.abortAttempt({
            titleId: 'title-1',
            entryId: 'entry-1',
            fetchFn: fakeFetch
        });

        // Operator immediately clicks "Retry Initiation" (starts Generation 2)
        const retryPromise = coordinator.retryInitiation({
            titleId: 'title-1',
            entryId: 'entry-1',
            fetchFn: fakeFetch,
            cryptoFn: mockCryptoFn
        });

        // Wait until Generation 2 is awaiting its response.json()
        while (!deferredJsonGen2) {
            await new Promise(r => setImmediate(r));
        }

        assert.strictEqual(attempt.generation, 2, 'Attempt has advanced to Generation 2');
        assert.strictEqual(attempt.sessionId, null, 'Generation 2 session ID is still pending');

        // Generation 1's delayed JSON body now resolves!
        deferredJsonGen1({
            sessionId: 'stale-session-from-generation-1',
            expectedPartCount: 99,
            partSizeBytes: 1000
        });

        await new Promise(r => setImmediate(r));
        await new Promise(r => setImmediate(r));

        // CRITICAL ASSERTION: Stale Generation 1 JSON body must NOT overwrite Generation 2 session state!
        assert.notStrictEqual(attempt.sessionId, 'stale-session-from-generation-1',
            'Generation 1 delayed JSON body must NOT overwrite attempt.sessionId in Generation 2');
        assert.notStrictEqual(attempt.totalParts, 99,
            'Generation 1 delayed JSON body must NOT overwrite attempt.totalParts in Generation 2');
        assert.notStrictEqual(attempt.partSizeBytes, 1000,
            'Generation 1 delayed JSON body must NOT overwrite attempt.partSizeBytes in Generation 2');

        // Now Generation 2's valid JSON body resolves
        deferredJsonGen2({
            sessionId: 'valid-session-gen2',
            expectedPartCount: 1,
            partSizeBytes: 52428800
        });

        const retryResult = await retryPromise;
        assert.strictEqual(retryResult.phase, UploadPhase.COMPLETED);
        assert.strictEqual(retryResult.sessionId, 'valid-session-gen2',
            'Final session ID must strictly match winning Generation 2 response');
        assert.strictEqual(retryResult.registeredAssetId, 'asset-gen2-success');
    });

    test('20. ASYNC BODY RACE: Cancelled request with delayed response.json() does not mutate session state if generation advanced before JSON resolved', async () => {
        const coordinator = new UploadCoordinator();
        let deferredJsonGen1 = null;

        const fakeFetch = async (url, options) => {
            if (options && options.signal && options.signal.aborted) {
                const abortErr = new Error('The user aborted a request.');
                abortErr.name = 'AbortError';
                throw abortErr;
            }

            if (url.endsWith('/video/upload-session') && options && options.method === 'POST') {
                return {
                    ok: true,
                    json: () => new Promise(resolve => { deferredJsonGen1 = resolve; })
                };
            }
            throw new Error('Unhandled URL: ' + url);
        };

        coordinator.startAttempt({
            file: mockFile,
            titleId: 'title-1',
            entryId: 'entry-1',
            fetchFn: fakeFetch,
            cryptoFn: mockCryptoFn
        });

        while (!deferredJsonGen1) {
            await new Promise(r => setImmediate(r));
        }

        const attempt = coordinator.getCurrentAttempt();

        // Operator cancels
        await coordinator.abortAttempt({
            titleId: 'title-1',
            entryId: 'entry-1',
            fetchFn: fakeFetch
        });

        // Generation manually advances or retries
        attempt.generation = 2;

        // Generation 1's delayed JSON resolves
        deferredJsonGen1({
            sessionId: 'stale-cancelled-gen1-session',
            expectedPartCount: 2,
            partSizeBytes: 26214400
        });

        await new Promise(r => setImmediate(r));
        await new Promise(r => setImmediate(r));

        assert.strictEqual(attempt.sessionId, null,
            'Delayed JSON from superseded generation must not mutate attempt.sessionId');
    });

    test('21. MALFORMED INITIATION: Successful HTTP 200 with malformed body fails closed without dispatching part PUT or completion', async () => {
        const malformedPayloads = [
            { desc: 'missing sessionId', body: { expectedPartCount: 1, partSizeBytes: 52428800 } },
            { desc: 'empty string sessionId', body: { sessionId: '   ', expectedPartCount: 1, partSizeBytes: 52428800 } },
            { desc: 'zero expectedPartCount', body: { sessionId: 's-1', expectedPartCount: 0, partSizeBytes: 52428800 } },
            { desc: 'negative expectedPartCount', body: { sessionId: 's-2', expectedPartCount: -1, partSizeBytes: 52428800 } },
            { desc: 'non-integer expectedPartCount', body: { sessionId: 's-3', expectedPartCount: 1.5, partSizeBytes: 52428800 } },
            { desc: 'missing partSizeBytes', body: { sessionId: 's-4', expectedPartCount: 1 } },
            { desc: 'zero partSizeBytes', body: { sessionId: 's-5', expectedPartCount: 1, partSizeBytes: 0 } },
            { desc: 'negative partSizeBytes', body: { sessionId: 's-6', expectedPartCount: 1, partSizeBytes: -100 } },
            { desc: 'empty object', body: {} }
        ];

        for (const testCase of malformedPayloads) {
            const coordinator = new UploadCoordinator();
            let partUrlDispatched = false;
            let partPutDispatched = false;
            let completeDispatched = false;
            let capturedError = null;

            const fakeFetch = async (url) => {
                if (url.endsWith('/video/upload-session')) {
                    return {
                        ok: true,
                        json: async () => testCase.body
                    };
                }
                if (url.includes('/parts/')) {
                    partUrlDispatched = true;
                    return { ok: true, json: async () => ({ uploadUrl: 'https://b2.test/part-1' }) };
                }
                if (url === 'https://b2.test/part-1') {
                    partPutDispatched = true;
                    return { ok: true, headers: { get: () => '"etag"' } };
                }
                if (url.endsWith('/complete')) {
                    completeDispatched = true;
                    return { ok: true, json: async () => ({}) };
                }
                throw new Error('Unhandled URL: ' + url);
            };

            await coordinator.startAttempt({
                file: mockFile,
                titleId: 'title-1',
                entryId: 'entry-1',
                callbacks: {
                    onError: (err) => { capturedError = err; }
                },
                fetchFn: fakeFetch,
                cryptoFn: mockCryptoFn
            });

            const attempt = coordinator.getCurrentAttempt();
            assert.strictEqual(attempt.phase, UploadPhase.FAILED, `Case [${testCase.desc}] must be FAILED`);
            assert.strictEqual(attempt.failedAtPhase, UploadPhase.INITIATING, `Case [${testCase.desc}] must fail at INITIATING`);
            assert.strictEqual(attempt.sessionId, null, `Case [${testCase.desc}] must NOT set sessionId`);
            assert.strictEqual(partUrlDispatched, false, `Case [${testCase.desc}] must NEVER request part URLs`);
            assert.strictEqual(partPutDispatched, false, `Case [${testCase.desc}] must NEVER dispatch part PUTs`);
            assert.strictEqual(completeDispatched, false, `Case [${testCase.desc}] must NEVER call completion`);
            assert.ok(capturedError !== null, `Case [${testCase.desc}] must invoke onError callback`);
            assert.ok(capturedError.message.includes('Phản hồi từ máy chủ không hợp lệ'), `Case [${testCase.desc}] message must indicate invalid response`);
        }
    });

    test('22. MALFORMED INITIATION: HTTP 200 with invalid JSON syntax fails closed without calling parts upload', async () => {
        const coordinator = new UploadCoordinator();
        let partUrlDispatched = false;
        let capturedError = null;

        const fakeFetch = async (url) => {
            if (url.endsWith('/video/upload-session')) {
                return {
                    ok: true,
                    json: async () => {
                        throw new SyntaxError('Unexpected token < in JSON at position 0');
                    }
                };
            }
            if (url.includes('/parts/')) {
                partUrlDispatched = true;
                return { ok: true, json: async () => ({ uploadUrl: 'https://b2.test/part-1' }) };
            }
            throw new Error('Unhandled URL: ' + url);
        };

        await coordinator.startAttempt({
            file: mockFile,
            titleId: 'title-1',
            entryId: 'entry-1',
            callbacks: {
                onError: (err) => { capturedError = err; }
            },
            fetchFn: fakeFetch,
            cryptoFn: mockCryptoFn
        });

        const attempt = coordinator.getCurrentAttempt();
        assert.strictEqual(attempt.phase, UploadPhase.FAILED);
        assert.strictEqual(attempt.failedAtPhase, UploadPhase.INITIATING);
        assert.strictEqual(attempt.sessionId, null);
        assert.strictEqual(partUrlDispatched, false, 'Must not dispatch part URLs on JSON parse error');
        assert.ok(capturedError !== null);
        assert.ok(capturedError.message.includes('Phản hồi không phải định dạng JSON hợp lệ'));
    });

    // =========================================================================
    // MS-08E3-S2 INCREMENTAL SHA-256, LARGE FILE & MEMORY SAFETY TESTS
    // =========================================================================

    test('MS-08E3-S2.1: IncrementalSha256 produces exact NIST standard test vectors', () => {
        const vectors = [
            { input: '', expected: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855' },
            { input: 'abc', expected: 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad' },
            { input: 'abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq', expected: '248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1' },
            { input: 'a'.repeat(100000), expected: crypto.createHash('sha256').update('a'.repeat(100000)).digest('hex') }
        ];

        for (const v of vectors) {
            const hasher = new IncrementalSha256();
            hasher.update(Buffer.from(v.input));
            const digest = hasher.digest();
            assert.strictEqual(digest, v.expected, `Digest mismatch for input: "${v.input.slice(0, 20)}..."`);
            assert.match(digest, /^[0-9a-f]{64}$/, 'Digest must be 64-char lowercase hex');
        }
    });

    test('MS-08E3-S2.2: IncrementalSha256 produces identical digest regardless of chunk size (chunk-boundary independence)', () => {
        const payload = Buffer.from('Kiếm Lai Universe Large-File Multipart Upload UX & Memory Safety MS-08E3-S2 testing arbitrary binary stream chunking independence across variable slice boundaries.'.repeat(50));
        const expectedDigest = crypto.createHash('sha256').update(payload).digest('hex');

        const testChunkSizes = [1, 2, 3, 7, 15, 32, 63, 64, 65, 127, 128, 255, 512, 1024, 4096, 7777];

        for (const chunkSize of testChunkSizes) {
            const hasher = new IncrementalSha256();
            for (let offset = 0; offset < payload.length; offset += chunkSize) {
                const slice = payload.subarray(offset, Math.min(offset + chunkSize, payload.length));
                hasher.update(slice);
            }
            const actualDigest = hasher.digest();
            assert.strictEqual(actualDigest, expectedDigest, `Chunk size ${chunkSize} must produce exact standard digest`);
        }
    });

    test('MS-08E3-S2.3: IncrementalSha256 handles message lengths exceeding 512 MiB with 64-bit length padding without 32-bit overflow', () => {
        // 520 MiB exceeds 512 MiB (2^32 bits = 536,870,912 bytes).
        // High 32 bits of bit length must equal 1 rather than overflowing or truncating to 0.
        const chunk10MB = Buffer.alloc(10 * 1024 * 1024, 0x4b); // 10 MiB of 'K'
        const repeatCount = 52; // 520 MiB total

        const nodeHash = crypto.createHash('sha256');
        for (let i = 0; i < repeatCount; i++) {
            nodeHash.update(chunk10MB);
        }
        const expectedDigest = nodeHash.digest('hex');

        const myHash = new IncrementalSha256();
        for (let i = 0; i < repeatCount; i++) {
            myHash.update(chunk10MB);
        }
        const actualDigest = myHash.digest();

        assert.strictEqual(actualDigest, expectedDigest, 'IncrementalSha256 must match native SHA-256 above 512 MiB');

        // Verify mathematical 64-bit length bit encoding across large boundaries up to 50 GiB
        const testBoundaries = [
            { bytes: 536870912, expectedHigh: 1, expectedLow: 0 }, // 512 MiB exactly (2^32 bits)
            { bytes: 1073741824, expectedHigh: 2, expectedLow: 0 }, // 1 GiB
            { bytes: 5 * 1073741824, expectedHigh: 10, expectedLow: 0 }, // 5 GiB
            { bytes: 53687091200, expectedHigh: 100, expectedLow: 0 }, // 50 GiB maximum allowed
            { bytes: 53687091200 - 1, expectedHigh: 99, expectedLow: 4294967288 } // 50 GiB - 1 byte
        ];

        for (const b of testBoundaries) {
            const totalBits = b.bytes * 8;
            const highBits = Math.floor(totalBits / 0x100000000);
            const lowBits = (totalBits >>> 0);
            assert.strictEqual(highBits, b.expectedHigh, `High bits mismatch for ${b.bytes} bytes`);
            assert.strictEqual(lowBits, b.expectedLow, `Low bits mismatch for ${b.bytes} bytes`);
            assert.strictEqual(highBits * 0x100000000 + lowBits, totalBits, `Total bits reconstruction mismatch for ${b.bytes} bytes`);
        }
    });

    test('MS-08E3-S2.4: IncrementalSha256 output encoding and state machine guard (disallow update/digest after finalize)', () => {
        const hasher = new IncrementalSha256();
        hasher.update(Buffer.from('immutable test'));
        const digest = hasher.digest();

        assert.strictEqual(typeof digest, 'string');
        assert.strictEqual(digest.length, 64);
        assert.match(digest, /^[0-9a-f]{64}$/);

        assert.throws(() => hasher.update(Buffer.from('after finalized')), /Cannot update finalized hash/);
        assert.throws(() => hasher.digest(), /Digest already called/);
    });

    test('MS-08E3-S2.5: calculateSha256Chunked reads file in bounded slices without loading full file into memory', async () => {
        const coordinator = new UploadCoordinator();
        const totalSize = 10 * 1024 * 1024; // 10 MiB synthetic file
        const testChunkSize = 2 * 1024 * 1024; // 2 MiB slices

        const requestedSlices = [];
        let fullArrayBufferCalled = false;

        const syntheticFile = {
            name: 'synthetic-large.mp4',
            size: totalSize,
            slice: (start, end) => {
                requestedSlices.push({ start, end, size: end - start });
                // Return a slice buffer of 2 MiB, NOT full 10 MiB!
                const sliceBuf = Buffer.alloc(end - start, 0x5a);
                return {
                    size: end - start,
                    arrayBuffer: async () => sliceBuf.buffer.slice(sliceBuf.byteOffset, sliceBuf.byteOffset + sliceBuf.byteLength)
                };
            },
            arrayBuffer: async () => {
                fullArrayBufferCalled = true;
                throw new Error('arrayBuffer() on full file must NEVER be called');
            }
        };

        const digest = await coordinator.calculateSha256Chunked(syntheticFile, {
            chunkSize: testChunkSize
        });

        assert.strictEqual(fullArrayBufferCalled, false, 'Must never allocate whole file via file.arrayBuffer()');
        assert.strictEqual(requestedSlices.length, 5, 'Must read file in exactly 5 slices of 2 MiB');
        assert.deepStrictEqual(requestedSlices[0], { start: 0, end: 2097152, size: 2097152 });
        assert.deepStrictEqual(requestedSlices[4], { start: 8388608, end: 10485760, size: 2097152 });
        assert.match(digest, /^[0-9a-f]{64}$/);
    });

    test('MS-08E3-S2.6: calculateSha256Chunked emits granular progress events during hashing', async () => {
        const coordinator = new UploadCoordinator();
        const totalSize = 8 * 1024 * 1024; // 8 MiB
        const testChunkSize = 2 * 1024 * 1024; // 2 MiB

        const progressCalls = [];
        const syntheticFile = {
            name: 'progress-test.mp4',
            size: totalSize,
            slice: (start, end) => {
                const buf = Buffer.alloc(end - start, 0x11);
                return {
                    size: end - start,
                    arrayBuffer: async () => buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength)
                };
            }
        };

        await coordinator.calculateSha256Chunked(syntheticFile, {
            chunkSize: testChunkSize,
            onProgress: (pct, hashedBytes, totalBytes) => {
                progressCalls.push({ pct, hashedBytes, totalBytes });
            }
        });

        assert.strictEqual(progressCalls.length, 4);
        assert.deepStrictEqual(progressCalls[0], { pct: 25, hashedBytes: 2097152, totalBytes: totalSize });
        assert.deepStrictEqual(progressCalls[1], { pct: 50, hashedBytes: 4194304, totalBytes: totalSize });
        assert.deepStrictEqual(progressCalls[2], { pct: 75, hashedBytes: 6291456, totalBytes: totalSize });
        assert.deepStrictEqual(progressCalls[3], { pct: 100, hashedBytes: totalSize, totalBytes: totalSize });
    });

    test('MS-08E3-S2.7: calculateSha256Chunked stops immediately and cleans up when upload attempt is cancelled', async () => {
        const coordinator = new UploadCoordinator();
        const attempt = createAttempt({ name: 'cancel-test.mp4', size: 10 * 1024 * 1024 }, 'entry-cancel');
        coordinator.currentAttempt = attempt;

        let sliceCount = 0;
        const syntheticFile = {
            name: 'cancel-test.mp4',
            size: 10 * 1024 * 1024,
            slice: (start, end) => {
                sliceCount++;
                if (sliceCount === 2) {
                    // Cancel attempt while hashing is in flight
                    attempt.isCancelled = true;
                }
                const buf = Buffer.alloc(end - start, 0x22);
                return {
                    size: end - start,
                    arrayBuffer: async () => buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength)
                };
            }
        };

        await assert.rejects(
            async () => {
                await coordinator.calculateSha256Chunked(syntheticFile, {
                    attempt: attempt,
                    chunkSize: 1024 * 1024
                });
            },
            /Tính toán SHA-256 đã bị hủy/
        );

        // Slice processing halted after slice 2, not reading all 10 slices
        assert.strictEqual(sliceCount, 2, 'Hashing loop must break immediately on attempt cancellation');
    });

    test('MS-08E3-S2.8: calculateSha256Chunked captures chunk read errors safely and marks attempt failed at HASHING phase', async () => {
        const coordinator = new UploadCoordinator();
        let reportedError = null;

        const faultyFile = {
            name: 'faulty.mp4',
            size: 5 * 1024 * 1024,
            slice: () => {
                return {
                    size: 1024 * 1024,
                    arrayBuffer: async () => {
                        throw new Error('I/O read failure from disk');
                    }
                };
            }
        };

        const fakeFetch = async () => ({ ok: true });

        await coordinator.startAttempt({
            file: faultyFile,
            titleId: 'title-1',
            entryId: 'entry-1',
            fetchFn: fakeFetch,
            callbacks: {
                onError: (err) => { reportedError = err; }
            }
        });

        const attempt = coordinator.getCurrentAttempt();
        assert.strictEqual(attempt.phase, UploadPhase.FAILED);
        assert.strictEqual(attempt.failedAtPhase, UploadPhase.HASHING);
        assert.strictEqual(attempt.isSettled, true, 'Pre-initiation read failure is locally settled');
        assert.ok(reportedError !== null);
        assert.ok(reportedError.message.includes('I/O read failure from disk'));
    });

    test('MS-08E3-S2.9: formatBytes displays consistent binary units (KiB, MiB, GiB) and avoids decimal confusion', () => {
        assert.strictEqual(formatBytes(0), '0 B');
        assert.strictEqual(formatBytes(500), '500 B');
        assert.strictEqual(formatBytes(1024), '1.00 KiB');
        assert.strictEqual(formatBytes(1536), '1.50 KiB');
        assert.strictEqual(formatBytes(1048576), '1.00 MiB');
        assert.strictEqual(formatBytes(26214400), '25.00 MiB');
        assert.strictEqual(formatBytes(1073741824), '1.00 GiB');
        assert.strictEqual(formatBytes(53687091200), '50.00 GiB');
        assert.strictEqual(formatBytes(-10), '0 B');
        assert.strictEqual(formatBytes(null), '0 B');
    });

    test('MS-08E3-S2.10: validateFileConstraints accepts small video, 5 GiB video, and video exactly at 50 GiB limit', () => {
        const small = validateFileConstraints({ name: 'small.mp4', size: 1024 * 1024 });
        assert.strictEqual(small.valid, true);
        assert.strictEqual(small.expectedParts, 1);
        assert.strictEqual(small.formattedSize, '1.00 MiB');

        const fiveGiB = validateFileConstraints({ name: 'movie-5gib.mp4', size: 5 * 1073741824 });
        assert.strictEqual(fiveGiB.valid, true);
        assert.strictEqual(fiveGiB.formattedSize, '5.00 GiB');
        assert.strictEqual(fiveGiB.expectedParts, 205);

        const exact50GiB = validateFileConstraints({ name: 'large-50gib.mp4', size: MAX_VIDEO_SIZE_BYTES });
        assert.strictEqual(exact50GiB.valid, true);
        assert.strictEqual(exact50GiB.formattedSize, '50.00 GiB');
        assert.strictEqual(exact50GiB.expectedParts, 2048);
    });

    test('MS-08E3-S2.11: validateFileConstraints rejects 0-byte video and video exceeding 50 GiB', () => {
        assert.throws(() => validateFileConstraints(null), /Vui lòng chọn file video/);
        assert.throws(() => validateFileConstraints({ name: 'empty.mp4', size: 0 }), /File video không được để trống \(0 byte\)/);
        assert.throws(() => validateFileConstraints({ name: 'audio.mp3', size: 1000 }), /Chỉ chấp nhận file định dạng MP4/);
        assert.throws(() => validateFileConstraints({ name: 'huge.mp4', size: MAX_VIDEO_SIZE_BYTES + 1 }), /vượt quá giới hạn tối đa cho phép \(50 GiB\)/);
    });

    test('MS-08E3-S2.12: startAttempt accepts 5 GiB video without legacy 500 MiB rejection and bounds in-flight parts', async () => {
        const coordinator = new UploadCoordinator();
        const fiveGiBSize = 5 * 1073741824; // 5 GiB > 500 MiB legacy limit
        const expectedParts = Math.ceil(fiveGiBSize / PART_SIZE); // 205 parts

        let maxInFlightParts = 0;
        let activeInFlightParts = 0;
        const uploadedPartNumbers = [];

        const fakeFetch = async (url, options) => {
            if (url.endsWith('/video/upload-session')) {
                return {
                    ok: true,
                    json: async () => ({
                        sessionId: 'sess-5gib-ok',
                        expectedPartCount: expectedParts,
                        partSizeBytes: PART_SIZE
                    })
                };
            }
            if (url.includes('/parts/') && url.endsWith('/url')) {
                const match = url.match(/\/parts\/(\d+)\/url/);
                const partNum = parseInt(match[1], 10);
                return {
                    ok: true,
                    json: async () => ({ uploadUrl: `https://b2.test/part-${partNum}` })
                };
            }
            if (url.startsWith('https://b2.test/part-')) {
                activeInFlightParts++;
                if (activeInFlightParts > maxInFlightParts) {
                    maxInFlightParts = activeInFlightParts;
                }
                const match = url.match(/\/part-(\d+)/);
                const partNum = parseInt(match[1], 10);
                uploadedPartNumbers.push(partNum);

                // Simulate slight network delay
                await new Promise(r => setImmediate(r));
                activeInFlightParts--;

                return {
                    ok: true,
                    headers: { get: () => `"etag-part-${partNum}"` }
                };
            }
            if (url.endsWith('/complete')) {
                return {
                    ok: true,
                    json: async () => ({
                        videoMediaAssetId: 'asset-5gib-success',
                        playbackUrl: '/media/assets/asset-5gib-success/content'
                    })
                };
            }
            throw new Error('Unhandled URL: ' + url);
        };

        const mockFile5GiB = {
            name: 'movie-5gib.mp4',
            size: fiveGiBSize,
            slice: (start, end) => ({
                size: end - start,
                arrayBuffer: async () => new ArrayBuffer(end - start)
            })
        };

        const result = await coordinator.startAttempt({
            file: mockFile5GiB,
            titleId: 'title-5gib',
            entryId: 'entry-5gib',
            fetchFn: fakeFetch,
            cryptoFn: async () => '5gib-mock-sha256-hash-value-0000000000000000000000000000000000000000'
        });

        assert.strictEqual(result.phase, UploadPhase.COMPLETED);
        assert.strictEqual(result.isSettled, true);
        assert.strictEqual(result.registeredAssetId, 'asset-5gib-success');
        assert.strictEqual(uploadedPartNumbers.length, expectedParts);
        assert.strictEqual(uploadedPartNumbers[0], 1);
        assert.strictEqual(uploadedPartNumbers[expectedParts - 1], expectedParts);
        // Sequential part execution guarantees max in-flight parts is strictly bounded to 1
        assert.strictEqual(maxInFlightParts, 1, 'In-flight part concurrency must be strictly bounded to 1 to preserve memory');
    });

    // =========================================================================
    // MS-08E3-S2 REVIEW CORRECTION TESTS: WORKER LIFECYCLE & BOUNDED-MEMORY FALLBACK
    // =========================================================================

    test('MS-08E3-S2.13: calculateSha256Chunked rejects non-slice input safely and NEVER calls full-file arrayBuffer()', async () => {
        const coordinator = new UploadCoordinator();
        let arrayBufferCalled = false;

        const nonSliceFile = {
            name: 'unsupported-file.mp4',
            size: 10 * 1024 * 1024,
            // Notice: slice method is intentionally absent
            arrayBuffer: async () => {
                arrayBufferCalled = true;
                throw new Error('Full-file arrayBuffer() was illegally called!');
            }
        };

        await assert.rejects(
            async () => {
                await coordinator.calculateSha256Chunked(nonSliceFile);
            },
            /Đối tượng file không hỗ trợ đọc phân đoạn \(file\.slice không khả dụng\)/
        );

        assert.strictEqual(arrayBufferCalled, false, 'full-file arrayBuffer() must NEVER be invoked on unsupported input');
    });

    test('MS-08E3-S2.14: screen-hash-worker.js executes in simulated Worker environment with 100% digest parity against node:crypto', async () => {
        const vm = require('node:vm');
        const fs = require('node:fs');
        const path = require('node:path');

        const workerFilePath = path.resolve(__dirname, '../../../main/resources/static/js/screen/screen-hash-worker.js');
        const workerSource = fs.readFileSync(workerFilePath, 'utf-8');

        // Create 1.5 MiB of pseudorandom deterministic data
        const testData = Buffer.alloc(1572864);
        for (let i = 0; i < testData.length; i++) {
            testData[i] = (i * 31 + 17) & 0xff;
        }
        const expectedDigest = crypto.createHash('sha256').update(testData).digest('hex');

        const postedMessages = [];
        const mockSelf = {
            postMessage: (msg) => { postedMessages.push(msg); }
        };

        const context = vm.createContext({
            self: mockSelf,
            Int32Array,
            Uint8Array,
            ArrayBuffer,
            Math,
            Error,
            console
        });
        vm.runInContext(workerSource, context);

        assert.strictEqual(typeof mockSelf.onmessage, 'function', 'Worker must register self.onmessage');

        const simulatedFile = {
            name: 'worker-test.mp4',
            size: testData.length,
            slice: (start, end) => {
                const subBuf = testData.subarray(start, end);
                return {
                    size: end - start,
                    arrayBuffer: async () => subBuf.buffer.slice(subBuf.byteOffset, subBuf.byteOffset + subBuf.byteLength)
                };
            }
        };

        // Execute onmessage with 256 KiB chunks
        await mockSelf.onmessage({
            data: {
                type: 'HASH',
                file: simulatedFile,
                chunkSize: 256 * 1024
            }
        });

        // Verify progress messages were emitted
        const progressMsgs = postedMessages.filter(m => m.type === 'PROGRESS');
        assert.ok(progressMsgs.length >= 6, 'Worker must emit progress messages for multiple chunks');
        assert.strictEqual(progressMsgs[progressMsgs.length - 1].percent, 100);

        // Verify completion message and exact cryptographic parity against node:crypto
        const completeMsg = postedMessages.find(m => m.type === 'COMPLETE');
        assert.ok(completeMsg, 'Worker must emit COMPLETE message');
        assert.strictEqual(completeMsg.digest, expectedDigest, 'Worker digest must match node:crypto SHA-256');
    });

    test('MS-08E3-S2.15: calculateSha256WithWorker lifecycle cleanup on normal completion releases worker and event listeners', async () => {
        const coordinator = new UploadCoordinator();
        const attempt = createAttempt({ name: 'worker-life.mp4', size: 1024 }, 'entry-wl');
        coordinator.currentAttempt = attempt;

        let abortListenerCount = 0;
        attempt.abortController.signal = {
            aborted: false,
            addEventListener: (event) => {
                if (event === 'abort') abortListenerCount++;
            },
            removeEventListener: (event) => {
                if (event === 'abort') abortListenerCount--;
            }
        };

        let beforeunloadCount = 0;
        const originalWindow = global.window;
        global.window = {
            addEventListener: (event) => {
                if (event === 'beforeunload') beforeunloadCount++;
            },
            removeEventListener: (event) => {
                if (event === 'beforeunload') beforeunloadCount--;
            }
        };

        let terminated = false;
        const originalWorker = global.Worker;
        global.Worker = function (url) {
            this.url = url;
            this.terminate = () => { terminated = true; };
            this.postMessage = () => {
                // Simulate async completion
                setImmediate(() => {
                    if (this.onmessage) {
                        this.onmessage({ data: { type: 'PROGRESS', percent: 50, hashedBytes: 512, totalBytes: 1024 } });
                        this.onmessage({ data: { type: 'COMPLETE', digest: 'worker-digest-1234567890abcdef' } });
                    }
                });
            };
        };

        try {
            const digest = await coordinator.calculateSha256WithWorker(attempt.file, { attempt: attempt });

            assert.strictEqual(digest, 'worker-digest-1234567890abcdef');
            assert.strictEqual(terminated, true, 'Worker must be terminated on completion');
            assert.strictEqual(attempt.hashWorker, null, 'attempt.hashWorker must be cleared to null after completion');
            assert.strictEqual(abortListenerCount, 0, 'Abort listener must be removed from signal');
            assert.strictEqual(beforeunloadCount, 0, 'beforeunload listener must be removed from window');
        } finally {
            global.window = originalWindow;
            global.Worker = originalWorker;
        }
    });

    test('MS-08E3-S2.16: calculateSha256WithWorker lifecycle cleanup on worker error releases worker and rejects safely', async () => {
        const coordinator = new UploadCoordinator();
        const attempt = createAttempt({ name: 'worker-err.mp4', size: 1024 }, 'entry-we');
        coordinator.currentAttempt = attempt;

        let abortListenerCount = 0;
        attempt.abortController.signal = {
            aborted: false,
            addEventListener: () => { abortListenerCount++; },
            removeEventListener: () => { abortListenerCount--; }
        };

        let beforeunloadCount = 0;
        const originalWindow = global.window;
        global.window = {
            addEventListener: () => { beforeunloadCount++; },
            removeEventListener: () => { beforeunloadCount--; }
        };

        let terminated = false;
        const originalWorker = global.Worker;
        global.Worker = function () {
            this.terminate = () => { terminated = true; };
            this.postMessage = () => {
                setImmediate(() => {
                    if (this.onerror) {
                        this.onerror(new Error('Simulated worker runtime failure'));
                    }
                });
            };
        };

        try {
            await assert.rejects(
                async () => {
                    await coordinator.calculateSha256WithWorker(attempt.file, { attempt: attempt });
                },
                /Simulated worker runtime failure/
            );

            assert.strictEqual(terminated, true, 'Worker must be terminated on error');
            assert.strictEqual(attempt.hashWorker, null, 'attempt.hashWorker must be cleared to null on error');
            assert.strictEqual(abortListenerCount, 0, 'Abort listener must be removed on error');
            assert.strictEqual(beforeunloadCount, 0, 'beforeunload listener must be removed on error');
        } finally {
            global.window = originalWindow;
            global.Worker = originalWorker;
        }
    });

    test('MS-08E3-S2.17: calculateSha256WithWorker handles postMessage failure safely with full resource cleanup', async () => {
        const coordinator = new UploadCoordinator();
        const attempt = createAttempt({ name: 'worker-post-fail.mp4', size: 1024 }, 'entry-wpf');
        coordinator.currentAttempt = attempt;

        let abortListenerCount = 0;
        attempt.abortController.signal = {
            aborted: false,
            addEventListener: () => { abortListenerCount++; },
            removeEventListener: () => { abortListenerCount--; }
        };

        let beforeunloadCount = 0;
        const originalWindow = global.window;
        global.window = {
            addEventListener: () => { beforeunloadCount++; },
            removeEventListener: () => { beforeunloadCount--; }
        };

        let terminated = false;
        const originalWorker = global.Worker;
        global.Worker = function () {
            this.terminate = () => { terminated = true; };
            this.postMessage = () => {
                throw new Error('DataCloneError: file object cannot be cloned');
            };
        };

        try {
            await assert.rejects(
                async () => {
                    await coordinator.calculateSha256WithWorker(attempt.file, { attempt: attempt });
                },
                /DataCloneError: file object cannot be cloned/
            );

            assert.strictEqual(terminated, true, 'Worker must be terminated on postMessage failure');
            assert.strictEqual(attempt.hashWorker, null, 'attempt.hashWorker must be cleared on postMessage failure');
            assert.strictEqual(abortListenerCount, 0, 'Abort listener must be removed on postMessage failure');
            assert.strictEqual(beforeunloadCount, 0, 'beforeunload listener must be removed on postMessage failure');
        } finally {
            global.window = originalWindow;
            global.Worker = originalWorker;
        }
    });

    test('MS-08E3-S2.18: calculateSha256WithWorker fences out stale worker messages when attempt is cancelled or generation advanced', async () => {
        const coordinator = new UploadCoordinator();
        const attempt = createAttempt({ name: 'worker-fence.mp4', size: 2048 }, 'entry-wf', { generation: 1 });
        coordinator.currentAttempt = attempt;

        let progressEvents = [];
        let terminated = false;
        let mockWorkerInstance = null;

        const originalWorker = global.Worker;
        global.Worker = function () {
            this.terminate = () => { terminated = true; };
            this.postMessage = () => {};
            mockWorkerInstance = this;
        };

        try {
            const workerPromise = coordinator.calculateSha256WithWorker(attempt.file, {
                attempt: attempt,
                generation: 1,
                onProgress: (pct) => { progressEvents.push(pct); }
            });

            // Simulate progress while active
            mockWorkerInstance.onmessage({ data: { type: 'PROGRESS', percent: 25, hashedBytes: 512, totalBytes: 2048 } });
            assert.strictEqual(progressEvents.length, 1);
            assert.strictEqual(progressEvents[0], 25);

            // Now cancel attempt / advance generation
            attempt.isCancelled = true;

            // Worker emits delayed progress and completion from stale generation
            mockWorkerInstance.onmessage({ data: { type: 'PROGRESS', percent: 75, hashedBytes: 1536, totalBytes: 2048 } });
            mockWorkerInstance.onmessage({ data: { type: 'COMPLETE', digest: 'stale-hash' } });

            // Ensure progressEvents did NOT receive the 75% update
            assert.strictEqual(progressEvents.length, 1, 'Stale worker progress must be fenced out and never reach UI callback');

            // The worker promise should reject due to cancellation
            await assert.rejects(
                workerPromise,
                /Tính toán SHA-256 đã bị hủy/
            );

            assert.strictEqual(terminated, true, 'Worker must be terminated when stale event is fenced out');
            assert.strictEqual(attempt.hashWorker, null, 'hashWorker reference must be cleared');
        } finally {
            global.Worker = originalWorker;
        }
    });

    // =========================================================================
    // MS-08E3-S2 CANCELLATION CORRECTIVE TESTS: T1 - T7
    // =========================================================================

    test('T1: Navigation cancellation (beforeunload) settles hashing Promise within bounded time, terminates worker and cleans up listeners', async () => {
        const coordinator = new UploadCoordinator();
        const attempt = createAttempt({ name: 'nav-test.mp4', size: 10 * 1024 * 1024 }, 'entry-t1');
        coordinator.currentAttempt = attempt;

        let capturedBeforeUnload = null;
        let beforeunloadRemoved = false;
        const originalWindow = global.window;
        global.window = {
            addEventListener: (event, handler) => {
                if (event === 'beforeunload') capturedBeforeUnload = handler;
            },
            removeEventListener: (event) => {
                if (event === 'beforeunload') beforeunloadRemoved = true;
            }
        };

        let workerTerminated = false;
        const originalWorker = global.Worker;
        global.Worker = function () {
            this.terminate = () => { workerTerminated = true; };
            this.postMessage = () => {};
        };

        try {
            const hashPromise = coordinator.calculateSha256WithWorker(attempt.file, {
                attempt: attempt
            });

            assert.strictEqual(typeof capturedBeforeUnload, 'function', 'beforeunload handler must be registered');
            assert.strictEqual(attempt.hashWorker !== null, true, 'attempt.hashWorker must reference active worker');

            // Dispatch navigation cancellation
            capturedBeforeUnload();

            // Guard against indefinitely pending Promise via bounded timeout
            const timeoutPromise = new Promise((_, reject) => setTimeout(() => reject(new Error('TIMEOUT_PENDING_PROMISE')), 500));

            const rejectedErr = await Promise.race([
                hashPromise.then(
                    () => { throw new Error('Promise should have rejected'); },
                    (err) => err
                ),
                timeoutPromise
            ]);

            assert.strictEqual(rejectedErr.code, HASH_NAVIGATION_CANCELLED);
            assert.strictEqual(rejectedErr.isCancellation, true);
            assert.strictEqual(workerTerminated, true, 'Worker must be terminated on beforeunload');
            assert.strictEqual(attempt.hashWorker, null, 'attempt.hashWorker reference must be cleared');
            assert.strictEqual(beforeunloadRemoved, true, 'beforeunload listener must be removed');
        } finally {
            global.window = originalWindow;
            global.Worker = originalWorker;
        }
    });

    test('T2: Navigation cancellation in calculateSha256 propagates error and does NOT trigger fallback chunked hashing', async () => {
        const coordinator = new UploadCoordinator();
        const testFile = {
            name: 'nav-nofallback.mp4',
            size: 10 * 1024 * 1024,
            slice: () => ({ arrayBuffer: async () => new ArrayBuffer(10) })
        };
        const attempt = createAttempt(testFile, 'entry-t2');
        coordinator.currentAttempt = attempt;

        let capturedBeforeUnload = null;
        const originalWindow = global.window;
        const originalWorker = global.Worker;

        const mockWorkerClass = function () {
            this.terminate = () => {};
            this.postMessage = () => {};
        };
        global.Worker = mockWorkerClass;
        global.window = {
            Worker: mockWorkerClass,
            addEventListener: (event, handler) => {
                if (event === 'beforeunload') capturedBeforeUnload = handler;
            },
            removeEventListener: () => {}
        };

        let chunkedFallbackCalled = false;
        const originalChunked = coordinator.calculateSha256Chunked;
        coordinator.calculateSha256Chunked = async function (...args) {
            chunkedFallbackCalled = true;
            return originalChunked.apply(this, args);
        };

        try {
            const calculatePromise = coordinator.calculateSha256(attempt.file, null, {
                attempt: attempt
            });

            assert.strictEqual(typeof capturedBeforeUnload, 'function');
            capturedBeforeUnload();

            await assert.rejects(
                calculatePromise,
                (err) => {
                    assert.strictEqual(err.code, HASH_NAVIGATION_CANCELLED);
                    return true;
                }
            );

            assert.strictEqual(chunkedFallbackCalled, false, 'Main-thread chunked fallback must NEVER be triggered on cancellation');
        } finally {
            coordinator.calculateSha256Chunked = originalChunked;
            global.window = originalWindow;
            global.Worker = originalWorker;
        }
    });

    test('T3: startAttempt with navigation cancellation during hashing never calls initiation/parts/completion endpoints and settles attempt', async () => {
        const coordinator = new UploadCoordinator();
        const fetchUrls = [];

        let capturedBeforeUnload = null;
        const originalWindow = global.window;
        const originalWorker = global.Worker;

        const mockWorkerClass = function () {
            this.terminate = () => {};
            this.postMessage = () => {
                // Trigger beforeunload while hashing
                setImmediate(() => {
                    if (capturedBeforeUnload) capturedBeforeUnload();
                });
            };
        };
        global.Worker = mockWorkerClass;
        global.window = {
            Worker: mockWorkerClass,
            addEventListener: (event, handler) => {
                if (event === 'beforeunload') capturedBeforeUnload = handler;
            },
            removeEventListener: () => {}
        };

        const fakeFetch = async (url) => {
            fetchUrls.push(url);
            return { ok: true, json: async () => ({ sessionId: 'illegal-sess' }) };
        };

        let errorReported = null;
        try {
            const result = await coordinator.startAttempt({
                file: { name: 'test-t3.mp4', size: 10 * 1024 * 1024, slice: () => ({ arrayBuffer: async () => new ArrayBuffer(10) }) },
                titleId: 'title-t3',
                entryId: 'entry-t3',
                fetchFn: fakeFetch,
                callbacks: {
                    onError: (err) => { errorReported = err; }
                }
            });

            assert.strictEqual(fetchUrls.length, 0, 'No fetch calls must be made after navigation cancellation');
            assert.strictEqual(result.sessionId, null, 'sessionId must remain null');
            assert.strictEqual(result.initiationDispatched, false, 'initiationDispatched must remain false');
            assert.strictEqual(result.isSettled, true, 'Cancelled pre-initiation attempt must be marked settled');
            assert.strictEqual(result.phase, UploadPhase.ABORTED, 'Attempt phase must be ABORTED');
            assert.strictEqual(errorReported !== null, true, 'onError callback must be invoked with cancellation error');
            assert.strictEqual(errorReported.code, HASH_NAVIGATION_CANCELLED);
        } finally {
            global.window = originalWindow;
            global.Worker = originalWorker;
        }
    });

    test('T4: When user remains on page after beforeunload, attempt settles safely and a new startAttempt succeeds without PREVIOUS_ATTEMPT_UNSETTLED', async () => {
        const coordinator = new UploadCoordinator();
        let capturedBeforeUnload = null;

        const originalWindow = global.window;
        const originalWorker = global.Worker;

        let workerCount = 0;
        const mockWorkerClass = function () {
            workerCount++;
            const id = workerCount;
            this.id = id;
            this.terminate = () => {};
            this.postMessage = () => {
                if (id === 1) {
                    // Attempt 1: Trigger beforeunload
                    setImmediate(() => {
                        if (capturedBeforeUnload) capturedBeforeUnload();
                    });
                } else if (id === 2) {
                    // Attempt 2: Normal completion
                    setImmediate(() => {
                        if (this.onmessage) {
                            this.onmessage({ data: { type: 'COMPLETE', digest: 'second-attempt-valid-sha256-hash-00000000000000000000000000000000' } });
                        }
                    });
                }
            };
        };
        global.Worker = mockWorkerClass;
        global.window = {
            Worker: mockWorkerClass,
            addEventListener: (event, handler) => {
                if (event === 'beforeunload') capturedBeforeUnload = handler;
            },
            removeEventListener: () => {}
        };

        const file1 = { name: 'file-1.mp4', size: 10 * 1024 * 1024, slice: () => ({ arrayBuffer: async () => new ArrayBuffer(10) }) };
        const file2 = { name: 'file-2.mp4', size: 5 * 1024 * 1024, slice: () => ({ arrayBuffer: async () => new ArrayBuffer(5) }) };

        let fetchCalls = [];
        const fakeFetch = async (url) => {
            fetchCalls.push(url);
            if (url.endsWith('/video/upload-session')) {
                return {
                    ok: true,
                    json: async () => ({
                        sessionId: 'sess-attempt-2',
                        expectedPartCount: 1,
                        partSizeBytes: 52428800
                    })
                };
            }
            if (url.includes('/parts/1/url')) {
                return { ok: true, json: async () => ({ uploadUrl: 'https://b2.test/part-1' }) };
            }
            if (url.startsWith('https://b2.test/part-1')) {
                return { ok: true, headers: { get: () => '"etag-1"' } };
            }
            if (url.endsWith('/complete')) {
                return {
                    ok: true,
                    json: async () => ({
                        videoMediaAssetId: 'asset-2-ok',
                        playbackUrl: '/media/video/content'
                    })
                };
            }
            throw new Error('Unknown URL: ' + url);
        };

        try {
            // Attempt 1 starts and is cancelled by beforeunload
            const attempt1Result = await coordinator.startAttempt({
                file: file1,
                titleId: 'title-t4',
                entryId: 'entry-t4',
                fetchFn: fakeFetch
            });

            assert.strictEqual(attempt1Result.phase, UploadPhase.ABORTED);
            assert.strictEqual(attempt1Result.isSettled, true, 'Attempt 1 must be settled');

            // User stays on page and clicks upload for file 2
            // This MUST NOT throw PREVIOUS_ATTEMPT_UNSETTLED
            const attempt2Result = await coordinator.startAttempt({
                file: file2,
                titleId: 'title-t4',
                entryId: 'entry-t4',
                fetchFn: fakeFetch
            });

            assert.strictEqual(attempt2Result.phase, UploadPhase.COMPLETED);
            assert.strictEqual(attempt2Result.isSettled, true);
            assert.strictEqual(attempt2Result.registeredAssetId, 'asset-2-ok');
            assert.strictEqual(attempt2Result.sessionId, 'sess-attempt-2');
        } finally {
            global.window = originalWindow;
            global.Worker = originalWorker;
        }
    });

    test('T5: Late Worker PROGRESS, COMPLETE, and ERROR messages arriving after cancellation do not alter settled state or trigger callbacks', async () => {
        const coordinator = new UploadCoordinator();
        const attempt = createAttempt({ name: 'late-events.mp4', size: 1024 }, 'entry-t5');
        coordinator.currentAttempt = attempt;

        let capturedBeforeUnload = null;
        let mockWorker = null;

        const originalWindow = global.window;
        global.window = {
            addEventListener: (event, handler) => {
                if (event === 'beforeunload') capturedBeforeUnload = handler;
            },
            removeEventListener: () => {}
        };

        const originalWorker = global.Worker;
        global.Worker = function () {
            this.terminate = () => {};
            this.postMessage = () => {};
            mockWorker = this;
        };

        let progressCount = 0;
        try {
            const hashPromise = coordinator.calculateSha256WithWorker(attempt.file, {
                attempt: attempt,
                onProgress: () => { progressCount++; }
            });

            // Cancel via beforeunload
            capturedBeforeUnload();

            await assert.rejects(
                hashPromise,
                (err) => err.code === HASH_NAVIGATION_CANCELLED
            );

            const initialProgress = progressCount;

            // Simulate late Worker events on the terminated worker
            mockWorker.onmessage({ data: { type: 'PROGRESS', percent: 99, hashedBytes: 990, totalBytes: 1024 } });
            mockWorker.onmessage({ data: { type: 'COMPLETE', digest: 'late-stale-digest' } });
            mockWorker.onerror(new Error('late-stale-worker-error'));

            // Verify late events were completely dropped
            assert.strictEqual(progressCount, initialProgress, 'Late PROGRESS event must not invoke onProgress');
            assert.strictEqual(attempt.hashWorker, null, 'attempt.hashWorker must remain null');
        } finally {
            global.window = originalWindow;
            global.Worker = originalWorker;
        }
    });

    test('T6: Competing termination events settle Promise exactly once and ensure idempotent cleanup', async () => {
        const originalWorker = global.Worker;
        const originalWindow = global.window;

        try {
            // Case 1: Abort -> COMPLETE
            {
                const coordinator = new UploadCoordinator();
                const attempt = createAttempt({ name: 'c1.mp4', size: 1024 }, 'entry-c1');
                coordinator.currentAttempt = attempt;
                let workerInstance = null;
                global.Worker = function () {
                    this.terminate = () => {};
                    this.postMessage = () => {};
                    workerInstance = this;
                };

                const promise1 = coordinator.calculateSha256WithWorker(attempt.file, { attempt });
                attempt.abortController.abort();
                // Late complete
                workerInstance.onmessage({ data: { type: 'COMPLETE', digest: 'late-digest' } });

                await assert.rejects(promise1, (err) => err.code === HASH_ABORTED);
                assert.strictEqual(attempt.hashWorker, null);
            }

            // Case 2: COMPLETE -> Abort
            {
                const coordinator = new UploadCoordinator();
                const attempt = createAttempt({ name: 'c2.mp4', size: 1024 }, 'entry-c2');
                coordinator.currentAttempt = attempt;
                let workerInstance = null;
                global.Worker = function () {
                    this.terminate = () => {};
                    this.postMessage = () => {};
                    workerInstance = this;
                };

                const promise2 = coordinator.calculateSha256WithWorker(attempt.file, { attempt });
                workerInstance.onmessage({ data: { type: 'COMPLETE', digest: 'valid-digest-c2' } });
                attempt.abortController.abort();

                const digest = await promise2;
                assert.strictEqual(digest, 'valid-digest-c2');
                assert.strictEqual(attempt.hashWorker, null);
            }

            // Case 3: Beforeunload -> ERROR
            {
                const coordinator = new UploadCoordinator();
                const attempt = createAttempt({ name: 'c3.mp4', size: 1024 }, 'entry-c3');
                coordinator.currentAttempt = attempt;
                let capturedBeforeUnload = null;
                let workerInstance = null;

                global.window = {
                    addEventListener: (event, handler) => { if (event === 'beforeunload') capturedBeforeUnload = handler; },
                    removeEventListener: () => {}
                };
                global.Worker = function () {
                    this.terminate = () => {};
                    this.postMessage = () => {};
                    workerInstance = this;
                };

                const promise3 = coordinator.calculateSha256WithWorker(attempt.file, { attempt });
                capturedBeforeUnload();
                workerInstance.onerror(new Error('Late worker error'));

                await assert.rejects(promise3, (err) => err.code === HASH_NAVIGATION_CANCELLED);
                assert.strictEqual(attempt.hashWorker, null);
            }

            // Case 4: ERROR -> Beforeunload
            {
                const coordinator = new UploadCoordinator();
                const attempt = createAttempt({ name: 'c4.mp4', size: 1024 }, 'entry-c4');
                coordinator.currentAttempt = attempt;
                let capturedBeforeUnload = null;
                let workerInstance = null;

                global.window = {
                    addEventListener: (event, handler) => { if (event === 'beforeunload') capturedBeforeUnload = handler; },
                    removeEventListener: () => {}
                };
                global.Worker = function () {
                    this.terminate = () => {};
                    this.postMessage = () => {};
                    workerInstance = this;
                };

                const promise4 = coordinator.calculateSha256WithWorker(attempt.file, { attempt });
                workerInstance.onerror(new Error('Primary worker runtime error'));
                capturedBeforeUnload();

                await assert.rejects(promise4, (err) => err.code === 'WORKER_ERROR' || err.message.includes('Primary worker runtime error'));
                assert.strictEqual(attempt.hashWorker, null);
            }
        } finally {
            global.Worker = originalWorker;
            global.window = originalWindow;
        }
    });

    test('T7: Genuine Worker initialization error falls back to bounded-memory chunked hashing and calculates exact SHA-256', async () => {
        const coordinator = new UploadCoordinator();
        const testData = Buffer.alloc(1024 * 1024, 0x3c); // 1 MiB deterministic data
        const expectedDigest = crypto.createHash('sha256').update(testData).digest('hex');

        let fullArrayBufferCalled = false;
        let sliceCount = 0;
        const file = {
            name: 'test-fallback.mp4',
            size: testData.length,
            slice: (start, end) => {
                sliceCount++;
                const sub = testData.subarray(start, end);
                return {
                    size: end - start,
                    arrayBuffer: async () => sub.buffer.slice(sub.byteOffset, sub.byteOffset + sub.byteLength)
                };
            },
            arrayBuffer: async () => {
                fullArrayBufferCalled = true;
                throw new Error('Whole-file arrayBuffer() must never be called!');
            }
        };

        const attempt = createAttempt(file, 'entry-t7');
        coordinator.currentAttempt = attempt;

        const originalWorker = global.Worker;
        const originalWindow = global.window;
        global.window = {}; // Simulate browser window present
        global.Worker = function () {
            throw new Error('DOMException: The operation is insecure (CSP worker-src restricted)');
        };

        try {
            const digest = await coordinator.calculateSha256(file, null, {
                attempt: attempt,
                chunkSize: 256 * 1024
            });

            assert.strictEqual(digest, expectedDigest, 'Fallback must produce exact standard SHA-256 digest');
            assert.strictEqual(fullArrayBufferCalled, false, 'Fallback must not allocate full file');
            assert.strictEqual(sliceCount, 4, 'Must process file in bounded slices');
        } finally {
            global.Worker = originalWorker;
            global.window = originalWindow;
        }
    });

    // =========================================================================
    // MS-08E3-S2 NAVIGATION CANCELLATION ACROSS WORKER & FALLBACK: T9 - T13
    // =========================================================================

    const waitUntil = async (predicate, description, timeoutMs = 2000) => {
        const start = Date.now();
        while (!predicate()) {
            if (Date.now() - start > timeoutMs) {
                throw new Error(`Timeout waiting for ${description}`);
            }
            await new Promise(r => setImmediate(r));
        }
    };

    test('T9: When Worker fails with recoverable error and navigation occurs during fallback hashing, cancellation is honored, no initiation occurs and attempt settles', async () => {
        const coordinator = new UploadCoordinator();
        let capturedBeforeUnload = null;
        let beforeunloadRemovedCount = 0;
        let beforeunloadAddedCount = 0;

        const originalWindow = global.window;
        const originalWorker = global.Worker;

        const mockWorkerClass = function () {
            this.terminate = () => {};
            this.postMessage = () => {
                // Trigger recoverable Worker error immediately
                setImmediate(() => {
                    if (this.onerror) {
                        this.onerror(new Error('Recoverable Worker initialization error (e.g. CSP restriction)'));
                    }
                });
            };
        };
        global.Worker = mockWorkerClass;
        global.window = {
            Worker: mockWorkerClass,
            addEventListener: (event, handler) => {
                if (event === 'beforeunload') {
                    beforeunloadAddedCount++;
                    capturedBeforeUnload = handler;
                }
            },
            removeEventListener: (event) => {
                if (event === 'beforeunload') beforeunloadRemovedCount++;
            }
        };

        const fetchUrls = [];
        const fakeFetch = async (url) => {
            fetchUrls.push(url);
            return { ok: true, json: async () => ({ sessionId: 'illegal-sess' }) };
        };

        let sliceIndex = 0;
        let resumeDeferred = null;
        const testFile = {
            name: 'fallback-nav-test.mp4',
            size: 10 * 1024 * 1024,
            slice: (start, end) => {
                sliceIndex++;
                if (sliceIndex === 1) {
                    return {
                        size: end - start,
                        arrayBuffer: async () => {
                            // Hold chunked fallback pending while navigation is dispatched
                            await new Promise(resolve => { resumeDeferred = resolve; });
                            return new ArrayBuffer(end - start);
                        }
                    };
                }
                return {
                    size: end - start,
                    arrayBuffer: async () => new ArrayBuffer(end - start)
                };
            }
        };

        let reportedError = null;
        const startPromise = coordinator.startAttempt({
            file: testFile,
            titleId: 'title-t9',
            entryId: 'entry-t9',
            fetchFn: fakeFetch,
            callbacks: {
                onError: (err) => { reportedError = err; }
            }
        });

        // Wait until fallback is paused inside slice 1
        await waitUntil(() => resumeDeferred !== null, 'resumeDeferred in slice 1');

        // Dispatch beforeunload while fallback is paused
        assert.strictEqual(typeof capturedBeforeUnload, 'function', 'Fallback must have registered beforeunload listener');
        capturedBeforeUnload();

        // Resume fallback
        resumeDeferred();

        const result = await startPromise;

        assert.strictEqual(fetchUrls.length, 0, 'No fetch/initiation requests must be dispatched after navigation cancellation');
        assert.strictEqual(result.sessionId, null, 'No session must be created');
        assert.strictEqual(result.phase, UploadPhase.ABORTED, 'Attempt must be marked ABORTED');
        assert.strictEqual(result.isSettled, true, 'Pre-initiation cancelled attempt must be marked safely settled');
        assert.strictEqual(reportedError !== null, true, 'onError callback must receive cancellation error');
        assert.strictEqual(reportedError.code, HASH_NAVIGATION_CANCELLED);
        assert.ok(beforeunloadRemovedCount >= 2, 'Both Worker and fallback beforeunload listeners must be removed');
    });

    test('T10: When Worker is unavailable from start, navigation during fallback halts hashing and does not initiate upload', async () => {
        const coordinator = new UploadCoordinator();
        let capturedBeforeUnload = null;

        const originalWindow = global.window;
        const originalWorker = global.Worker;
        delete global.Worker; // No Worker available

        global.window = {
            addEventListener: (event, handler) => {
                if (event === 'beforeunload') capturedBeforeUnload = handler;
            },
            removeEventListener: () => {}
        };

        const fetchUrls = [];
        const fakeFetch = async (url) => {
            fetchUrls.push(url);
            return { ok: true, json: async () => ({ sessionId: 'illegal-sess' }) };
        };

        let sliceIndex = 0;
        let resumeDeferred = null;
        const testFile = {
            name: 'no-worker-nav-test.mp4',
            size: 10 * 1024 * 1024,
            slice: (start, end) => {
                sliceIndex++;
                if (sliceIndex === 1) {
                    return {
                        size: end - start,
                        arrayBuffer: async () => {
                            await new Promise(resolve => { resumeDeferred = resolve; });
                            return new ArrayBuffer(end - start);
                        }
                    };
                }
                return {
                    size: end - start,
                    arrayBuffer: async () => new ArrayBuffer(end - start)
                };
            }
        };

        let reportedError = null;
        const startPromise = coordinator.startAttempt({
            file: testFile,
            titleId: 'title-t10',
            entryId: 'entry-t10',
            fetchFn: fakeFetch,
            callbacks: {
                onError: (err) => { reportedError = err; }
            }
        });

        await waitUntil(() => resumeDeferred !== null, 'resumeDeferred in slice 1');

        assert.strictEqual(typeof capturedBeforeUnload, 'function', 'beforeunload listener must be registered even when Worker is absent');
        capturedBeforeUnload();

        resumeDeferred();

        const result = await startPromise;

        assert.strictEqual(fetchUrls.length, 0, 'No fetch calls must be made after navigation cancellation');
        assert.strictEqual(result.sessionId, null);
        assert.strictEqual(result.phase, UploadPhase.ABORTED);
        assert.strictEqual(result.isSettled, true);
        assert.strictEqual(reportedError !== null, true);
        assert.strictEqual(reportedError.code, HASH_NAVIGATION_CANCELLED);
    });

    test('T11: When Worker fails with recoverable error and no navigation occurs, fallback calculates exact SHA-256 and upload proceeds normally', async () => {
        const coordinator = new UploadCoordinator();
        const testData = Buffer.alloc(1024 * 1024, 0x4d); // 1 MiB deterministic data
        const expectedDigest = crypto.createHash('sha256').update(testData).digest('hex');

        const originalWindow = global.window;
        const originalWorker = global.Worker;

        const mockWorkerClass = function () {
            this.terminate = () => {};
            this.postMessage = () => {
                setImmediate(() => {
                    if (this.onerror) {
                        this.onerror(new Error('Simulated recoverable worker load failure'));
                    }
                });
            };
        };
        global.Worker = mockWorkerClass;
        global.window = {
            Worker: mockWorkerClass,
            addEventListener: () => {},
            removeEventListener: () => {}
        };

        const testFile = {
            name: 'functional-fallback.mp4',
            size: testData.length,
            slice: (start, end) => {
                const sub = testData.subarray(start, end);
                return {
                    size: end - start,
                    arrayBuffer: async () => sub.buffer.slice(sub.byteOffset, sub.byteOffset + sub.byteLength)
                };
            }
        };

        let initBody = null;
        let completeBody = null;
        const fakeFetch = async (url, options = {}) => {
            if (url.endsWith('/video/upload-session')) {
                initBody = JSON.parse(options.body);
                return {
                    ok: true,
                    json: async () => ({
                        sessionId: 'sess-t11-ok',
                        expectedPartCount: 1,
                        partSizeBytes: 52428800
                    })
                };
            }
            if (url.includes('/parts/1/url')) {
                return { ok: true, json: async () => ({ uploadUrl: 'https://b2.test/part-1' }) };
            }
            if (url.startsWith('https://b2.test/part-1')) {
                return { ok: true, headers: { get: () => '"etag-t11"' } };
            }
            if (url.endsWith('/complete')) {
                completeBody = JSON.parse(options.body);
                return {
                    ok: true,
                    json: async () => ({
                        videoMediaAssetId: 'asset-t11-success',
                        playbackUrl: '/media/video/t11'
                    })
                };
            }
            throw new Error('Unknown URL: ' + url);
        };

        const result = await coordinator.startAttempt({
            file: testFile,
            titleId: 'title-t11',
            entryId: 'entry-t11',
            fetchFn: fakeFetch
        });

        assert.strictEqual(result.phase, UploadPhase.COMPLETED);
        assert.strictEqual(result.isSettled, true);
        assert.strictEqual(result.registeredAssetId, 'asset-t11-success');
        assert.strictEqual(completeBody.declaredContentHash, expectedDigest, 'Fallback must transmit exact SHA-256 hash');
    });

    test('T12: When navigation occurs during retry hashing after uncertain initiation, session uncertainty is retained and attempt is NOT settled', async () => {
        const coordinator = new UploadCoordinator();
        let capturedBeforeUnload = null;

        const originalWindow = global.window;
        const originalWorker = global.Worker;

        const mockWorkerClass = function () {
            this.terminate = () => {};
            this.postMessage = () => {
                setImmediate(() => {
                    if (capturedBeforeUnload) capturedBeforeUnload();
                });
            };
        };
        global.Worker = mockWorkerClass;
        global.window = {
            Worker: mockWorkerClass,
            addEventListener: (event, handler) => {
                if (event === 'beforeunload') capturedBeforeUnload = handler;
            },
            removeEventListener: () => {}
        };

        let fetchCallCount = 0;
        const fakeFetch = async (url) => {
            fetchCallCount++;
            if (url.endsWith('/video/upload-session')) {
                // Initial attempt: network drops after dispatching POST
                throw new Error('Network error: connection lost after POST dispatch');
            }
            return { ok: true };
        };

        const testFile = {
            name: 'retry-nav-test.mp4',
            size: 10 * 1024 * 1024,
            slice: () => ({ arrayBuffer: async () => new ArrayBuffer(10) })
        };

        // 1. Initial attempt fails after dispatching initiation
        await coordinator.startAttempt({
            file: testFile,
            titleId: 'title-t12',
            entryId: 'entry-t12',
            fetchFn: fakeFetch,
            cryptoFn: async () => 'init-hash-111111111111111111111111111111111111111111111111111111111111'
        });

        const attempt = coordinator.getCurrentAttempt();
        assert.strictEqual(attempt.initiationDispatched, true, 'Initiation was dispatched in Generation 1');
        assert.strictEqual(attempt.isSettled, false, 'Attempt must be unsettled after dropped initiation');
        assert.strictEqual(attempt.phase, UploadPhase.FAILED);

        // 2. Administrator clicks "Retry Initiation" (starts Generation 2 hashing)
        let retryError = null;
        const retryResult = await coordinator.retryInitiation({
            titleId: 'title-t12',
            entryId: 'entry-t12',
            fetchFn: fakeFetch,
            callbacks: {
                onError: (err) => { retryError = err; }
            }
        });

        // 3. Navigation was triggered during retry hashing
        assert.strictEqual(retryResult.phase, UploadPhase.ABORTED);
        // CRITICAL INVARIANT: Because initiation was already dispatched in Generation 1,
        // session uncertainty must be retained and attempt must NOT be marked settled!
        assert.strictEqual(retryResult.isSettled, false, 'Attempt with prior dispatched initiation MUST remain unsettled');
        assert.strictEqual(retryResult.initiationDispatched, true, 'initiationDispatched flag must be preserved');
        assert.strictEqual(retryError !== null, true);
        assert.strictEqual(retryError.code, HASH_NAVIGATION_CANCELLED);
    });

    test('T13: Listeners are completely removed after terminal outcomes; new upload can begin after pre-initiation cancellation and old callbacks are fenced out', async () => {
        const coordinator = new UploadCoordinator();
        let beforeunloadListeners = [];
        let beforeunloadRemoved = [];

        const originalWindow = global.window;
        const originalWorker = global.Worker;

        let workerInstances = [];
        const mockWorkerClass = function () {
            workerInstances.push(this);
            this.id = workerInstances.length;
            this.terminate = () => {};
            this.postMessage = () => {
                if (this.id === 1) {
                    // Attempt 1: Trigger beforeunload
                    setImmediate(() => {
                        const handler = beforeunloadListeners[beforeunloadListeners.length - 1];
                        if (handler) handler();
                    });
                } else if (this.id === 2) {
                    // Attempt 2: Normal completion
                    setImmediate(() => {
                        if (this.onmessage) {
                            this.onmessage({ data: { type: 'COMPLETE', digest: 'attempt-2-sha256-hash-00000000000000000000000000000000000000000000' } });
                        }
                    });
                }
            };
        };
        global.Worker = mockWorkerClass;
        global.window = {
            Worker: mockWorkerClass,
            addEventListener: (event, handler) => {
                if (event === 'beforeunload') beforeunloadListeners.push(handler);
            },
            removeEventListener: (event, handler) => {
                if (event === 'beforeunload') beforeunloadRemoved.push(handler);
            }
        };

        const file1 = { name: 'f1.mp4', size: 10 * 1024 * 1024, slice: () => ({ arrayBuffer: async () => new ArrayBuffer(10) }) };
        const file2 = { name: 'f2.mp4', size: 5 * 1024 * 1024, slice: () => ({ arrayBuffer: async () => new ArrayBuffer(5) }) };

        const fakeFetch = async (url) => {
            if (url.endsWith('/video/upload-session')) {
                return {
                    ok: true,
                    json: async () => ({ sessionId: 'sess-t13-ok', expectedPartCount: 1, partSizeBytes: 52428800 })
                };
            }
            if (url.includes('/parts/1/url')) {
                return { ok: true, json: async () => ({ uploadUrl: 'https://b2.test/part-1' }) };
            }
            if (url.startsWith('https://b2.test/part-1')) {
                return { ok: true, headers: { get: () => '"etag-t13"' } };
            }
            if (url.endsWith('/complete')) {
                return {
                    ok: true,
                    json: async () => ({ videoMediaAssetId: 'asset-t13-ok', playbackUrl: '/media/video/t13' })
                };
            }
            throw new Error('Unknown URL: ' + url);
        };

        // 1. Attempt 1 starts and is cancelled by beforeunload
        let attempt1Error = null;
        let attempt1ProgressCalls = 0;
        const attempt1Result = await coordinator.startAttempt({
            file: file1,
            titleId: 'title-t13',
            entryId: 'entry-t13',
            fetchFn: fakeFetch,
            callbacks: {
                onError: (err) => { attempt1Error = err; },
                onProgress: () => { attempt1ProgressCalls++; }
            }
        });

        assert.strictEqual(attempt1Result.phase, UploadPhase.ABORTED);
        assert.strictEqual(attempt1Result.isSettled, true, 'Attempt 1 must be settled');
        assert.strictEqual(beforeunloadRemoved.length >= 1, true, 'Attempt 1 beforeunload listener must be removed');

        // 2. Attempt 2 starts cleanly without PREVIOUS_ATTEMPT_UNSETTLED
        let attempt2ProgressCalls = 0;
        let attempt2Success = null;
        const attempt2Result = await coordinator.startAttempt({
            file: file2,
            titleId: 'title-t13',
            entryId: 'entry-t13',
            fetchFn: fakeFetch,
            callbacks: {
                onProgress: () => { attempt2ProgressCalls++; },
                onSuccess: (data) => { attempt2Success = data; }
            }
        });

        assert.strictEqual(attempt2Result.phase, UploadPhase.COMPLETED);
        assert.strictEqual(attempt2Result.isSettled, true);
        assert.strictEqual(attempt2Success !== null, true);

        // 3. Stale callbacks from Attempt 1 worker must not mutate Attempt 2
        const initialAttempt2Progress = attempt2ProgressCalls;
        workerInstances[0].onmessage({ data: { type: 'PROGRESS', percent: 99, hashedBytes: 990, totalBytes: 1024 } });
        workerInstances[0].onmessage({ data: { type: 'COMPLETE', digest: 'stale-override' } });
        workerInstances[0].onerror(new Error('stale-err'));

        assert.strictEqual(attempt2ProgressCalls, initialAttempt2Progress, 'Stale Attempt 1 callbacks must not trigger Attempt 2 onProgress');
        assert.strictEqual(coordinator.getCurrentAttempt(), attempt2Result, 'Current attempt must remain Attempt 2');
        assert.strictEqual(attempt2Result.registeredAssetId, 'asset-t13-ok');
    });

    describe('MS-08E3-S4 Backend Error Code Alignment & UI Callback Tests (B1 - B8)', () => {

        const testFile = {
            name: 'test-contract.mp4',
            size: 26214400, // 25 MiB -> 1 part
            slice: (start, end) => ({ size: end - start })
        };
        const mockCryptoFn = async () => 'test-sha256-hash-b1-b8';

        function createMockUi() {
            const alerts = [];
            return {
                alerts: alerts,
                showAlert: function (type, message) {
                    alerts.push({ type, message });
                },
                retryInitBtn: { style: { display: 'none' } },
                startUploadBtn: { disabled: false },
                fileInput: { disabled: false },
                abortUploadBtn: { style: { display: 'none' }, disabled: false }
            };
        }

        test('B1: MEDIA_STORAGE_INITIATION_REJECTED (HTTP 502) preserves attempt/key and enables same-key retry in UI', async () => {
            const coordinator = new UploadCoordinator();
            const ui = createMockUi();

            const fakeFetch = async (url, options) => {
                if (url.includes('/video/upload-session')) {
                    return {
                        ok: false,
                        status: 502,
                        json: async () => ({
                            errorCode: 'MEDIA_STORAGE_INITIATION_REJECTED',
                            message: 'Nhà cung cấp lưu trữ từ chối yêu cầu khởi tạo do cấu hình hoặc quyền truy cập không hợp lệ.'
                        })
                    };
                }
                throw new Error('Unexpected URL: ' + url);
            };

            const result = await coordinator.startAttempt({
                file: testFile,
                titleId: 'title-b1',
                entryId: 'entry-b1',
                fetchFn: fakeFetch,
                cryptoFn: mockCryptoFn,
                callbacks: {
                    onError: (err, attempt) => {
                        handleUploadError(err, attempt, ui);
                    }
                }
            });

            assert.strictEqual(result.phase, UploadPhase.FAILED);
            assert.strictEqual(result.isSettled, false, 'Attempt must remain unsettled to protect against concurrent new attempts');
            assert.strictEqual(result.errorCode, 'MEDIA_STORAGE_INITIATION_REJECTED');
            assert.strictEqual(result.errorStatus, 502);
            assert.ok(result.idempotencyKey.startsWith('screen-entry-entry-b1'));
            assert.strictEqual(ui.retryInitBtn.style.display, 'inline-block', 'Retry button must be visible for safe same-key retry');
            assert.strictEqual(ui.startUploadBtn.disabled, true, 'Start upload button must be disabled');
            assert.strictEqual(ui.fileInput.disabled, true, 'File input must be disabled');
            assert.strictEqual(ui.alerts.length, 1);
            assert.strictEqual(ui.alerts[0].type, 'error');
            assert.ok(ui.alerts[0].message.includes('từ chối yêu cầu khởi tạo'));
            assert.ok(ui.alerts[0].message.includes(result.idempotencyKey));
        });

        test('B2: MEDIA_UPLOAD_INITIATION_IN_PROGRESS (HTTP 409) explains concurrent initiation and prevents click storms', async () => {
            const coordinator = new UploadCoordinator();
            const ui = createMockUi();

            const fakeFetch = async (url, options) => {
                return {
                    ok: false,
                    status: 409,
                    json: async () => ({
                        errorCode: 'MEDIA_UPLOAD_INITIATION_IN_PROGRESS',
                        message: 'Phiên tải lên đang được khởi tạo bởi một tiến trình khác.'
                    })
                };
            };

            const result = await coordinator.startAttempt({
                file: testFile,
                titleId: 'title-b2',
                entryId: 'entry-b2',
                fetchFn: fakeFetch,
                cryptoFn: mockCryptoFn,
                callbacks: {
                    onError: (err, attempt) => {
                        handleUploadError(err, attempt, ui);
                    }
                }
            });

            assert.strictEqual(result.phase, UploadPhase.FAILED);
            assert.strictEqual(result.isSettled, false);
            assert.strictEqual(result.errorCode, 'MEDIA_UPLOAD_INITIATION_IN_PROGRESS');
            assert.strictEqual(ui.retryInitBtn.style.display, 'none', 'Retry button must NOT be shown for concurrent in-progress initiation');
            assert.strictEqual(ui.startUploadBtn.disabled, true);
            assert.strictEqual(ui.fileInput.disabled, true);
            assert.strictEqual(ui.alerts.length, 1);
            assert.strictEqual(ui.alerts[0].type, 'warning');
            assert.ok(ui.alerts[0].message.includes('tiến trình khác'));
            assert.ok(ui.alerts[0].message.includes(result.idempotencyKey));
        });

        test('B3: MEDIA_UPLOAD_INITIATION_UNCONFIRMED_CLAIM (HTTP 409) suppresses retry button and requires reconciliation', async () => {
            const coordinator = new UploadCoordinator();
            const ui = createMockUi();

            const fakeFetch = async (url, options) => {
                return {
                    ok: false,
                    status: 409,
                    json: async () => ({
                        errorCode: 'MEDIA_UPLOAD_INITIATION_UNCONFIRMED_CLAIM',
                        message: 'Phiên tải lên tồn tại yêu cầu khởi tạo chưa xác nhận kết quả.'
                    })
                };
            };

            const result = await coordinator.startAttempt({
                file: testFile,
                titleId: 'title-b3',
                entryId: 'entry-b3',
                fetchFn: fakeFetch,
                cryptoFn: mockCryptoFn,
                callbacks: {
                    onError: (err, attempt) => {
                        handleUploadError(err, attempt, ui);
                    }
                }
            });

            assert.strictEqual(result.phase, UploadPhase.FAILED);
            assert.strictEqual(result.isSettled, false);
            assert.strictEqual(result.errorCode, 'MEDIA_UPLOAD_INITIATION_UNCONFIRMED_CLAIM');
            assert.strictEqual(ui.retryInitBtn.style.display, 'none', 'Actionable retry button must NOT be displayed for unconfirmed claims');
            assert.strictEqual(ui.startUploadBtn.disabled, true);
            assert.strictEqual(ui.fileInput.disabled, true);
            assert.strictEqual(ui.alerts.length, 1);
            assert.strictEqual(ui.alerts[0].type, 'error');
            assert.ok(ui.alerts[0].message.includes('đối soát'));
            assert.ok(ui.alerts[0].message.includes(result.idempotencyKey));
        });

        test('B4: MEDIA_STORAGE_INITIATION_AMBIGUOUS (HTTP 504) surfaces reconciliation guidance and hides retry button', async () => {
            const coordinator = new UploadCoordinator();
            const ui = createMockUi();

            const fakeFetch = async (url, options) => {
                return {
                    ok: false,
                    status: 504,
                    json: async () => ({
                        errorCode: 'MEDIA_STORAGE_INITIATION_AMBIGUOUS',
                        message: 'Yêu cầu khởi tạo tải lên nhiều phần tới nhà cung cấp lưu trữ chưa được xác nhận hoặc bị gián đoạn.'
                    })
                };
            };

            const result = await coordinator.startAttempt({
                file: testFile,
                titleId: 'title-b4',
                entryId: 'entry-b4',
                fetchFn: fakeFetch,
                cryptoFn: mockCryptoFn,
                callbacks: {
                    onError: (err, attempt) => {
                        handleUploadError(err, attempt, ui);
                    }
                }
            });

            assert.strictEqual(result.phase, UploadPhase.FAILED);
            assert.strictEqual(result.isSettled, false);
            assert.strictEqual(result.errorCode, 'MEDIA_STORAGE_INITIATION_AMBIGUOUS');
            assert.strictEqual(ui.retryInitBtn.style.display, 'none', 'Retry button must NOT be shown for ambiguous initiation');
            assert.strictEqual(ui.startUploadBtn.disabled, true);
            assert.strictEqual(ui.fileInput.disabled, true);
            assert.strictEqual(ui.alerts.length, 1);
            assert.strictEqual(ui.alerts[0].type, 'error');
            assert.ok(ui.alerts[0].message.includes('đối soát trạng thái'));
            assert.ok(ui.alerts[0].message.includes(result.idempotencyKey));
        });

        test('B5: Lost network response preserves uncertain initiation protections without fabricating server error code', async () => {
            const coordinator = new UploadCoordinator();
            const ui = createMockUi();

            const fakeFetch = async (url, options) => {
                throw new TypeError('Failed to fetch (network drop)');
            };

            const result = await coordinator.startAttempt({
                file: testFile,
                titleId: 'title-b5',
                entryId: 'entry-b5',
                fetchFn: fakeFetch,
                cryptoFn: mockCryptoFn,
                callbacks: {
                    onError: (err, attempt) => {
                        assert.strictEqual(err.isLostResponse, true);
                        assert.strictEqual(err.errorCode, undefined, 'Must not fabricate a server error code on lost network response');
                        handleUploadError(err, attempt, ui);
                    }
                }
            });

            assert.strictEqual(result.phase, UploadPhase.FAILED);
            assert.strictEqual(result.isSettled, false);
            assert.strictEqual(result.errorCode, null, 'Attempt errorCode must remain null when no server response was received');
            assert.strictEqual(ui.retryInitBtn.style.display, 'inline-block', 'Retry button displayed for network drop with same key');
            assert.strictEqual(ui.startUploadBtn.disabled, true);
            assert.strictEqual(ui.fileInput.disabled, true);
            assert.strictEqual(ui.alerts.length, 1);
            assert.ok(ui.alerts[0].message.includes('Phản hồi từ máy chủ bị gián đoạn'));
            assert.ok(ui.alerts[0].message.includes(result.idempotencyKey));
        });

        test('B6: Successful verified same-key retry after B1 continues upload normally to completion', async () => {
            const coordinator = new UploadCoordinator();
            const ui = createMockUi();
            let initAttempts = 0;
            let sentKeys = [];

            const fakeFetch = async (url, options) => {
                if (url.endsWith('/video/upload-session') && options && options.method === 'POST') {
                    initAttempts++;
                    const body = JSON.parse(options.body);
                    sentKeys.push(body.idempotencyKey);
                    if (initAttempts === 1) {
                        return {
                            ok: false,
                            status: 502,
                            json: async () => ({
                                errorCode: 'MEDIA_STORAGE_INITIATION_REJECTED',
                                message: 'Provider rejected'
                            })
                        };
                    }
                    return {
                        ok: true,
                        json: async () => ({
                            sessionId: 'sess-b6-success',
                            expectedPartCount: 1,
                            partSizeBytes: 26214400
                        })
                    };
                }
                if (url.includes('/parts/1/url')) {
                    return { ok: true, json: async () => ({ uploadUrl: 'https://b2.test/part-1' }) };
                }
                if (url === 'https://b2.test/part-1') {
                    return { ok: true, headers: { get: (name) => name === 'ETag' ? '"etag-1"' : null } };
                }
                if (url.endsWith('/complete')) {
                    return { ok: true, json: async () => ({ videoMediaAssetId: 'asset-b6', playbackUrl: '/media/b6' }) };
                }
                throw new Error('Unexpected URL: ' + url);
            };

            // 1. Initial attempt fails with 502
            const attempt1 = await coordinator.startAttempt({
                file: testFile,
                titleId: 'title-b6',
                entryId: 'entry-b6',
                fetchFn: fakeFetch,
                cryptoFn: mockCryptoFn,
                callbacks: {
                    onError: (err, attempt) => {
                        handleUploadError(err, attempt, ui);
                    }
                }
            });

            assert.strictEqual(attempt1.phase, UploadPhase.FAILED);
            assert.strictEqual(attempt1.errorCode, 'MEDIA_STORAGE_INITIATION_REJECTED');
            assert.strictEqual(ui.retryInitBtn.style.display, 'inline-block');

            // 2. Verified same-key retry succeeds and completes upload
            const retryResult = await coordinator.retryInitiation({
                titleId: 'title-b6',
                entryId: 'entry-b6',
                fetchFn: fakeFetch,
                cryptoFn: mockCryptoFn,
                callbacks: {
                    onError: (err, attempt) => {
                        handleUploadError(err, attempt, ui);
                    }
                }
            });

            assert.strictEqual(initAttempts, 2);
            assert.strictEqual(sentKeys.length, 2);
            assert.strictEqual(sentKeys[0], sentKeys[1], 'Retry must reuse the exact same idempotencyKey');
            assert.strictEqual(retryResult.phase, UploadPhase.COMPLETED);
            assert.strictEqual(retryResult.isSettled, true);
            assert.strictEqual(retryResult.registeredAssetId, 'asset-b6');
            assert.strictEqual(retryResult.errorCode, null, 'ErrorCode must be reset after successful retry');
        });

        test('B7: Unknown non-JSON (e.g. 500 HTML) or unknown JSON server errors fail conservatively and hide retry button', async () => {
            const coordinator = new UploadCoordinator();
            const ui = createMockUi();

            const fakeFetchHtml500 = async (url, options) => {
                return {
                    ok: false,
                    status: 500,
                    json: async () => {
                        throw new SyntaxError('Unexpected token < in JSON at position 0');
                    }
                };
            };

            const result = await coordinator.startAttempt({
                file: testFile,
                titleId: 'title-b7',
                entryId: 'entry-b7',
                fetchFn: fakeFetchHtml500,
                cryptoFn: mockCryptoFn,
                callbacks: {
                    onError: (err, attempt) => {
                        handleUploadError(err, attempt, ui);
                    }
                }
            });

            assert.strictEqual(result.phase, UploadPhase.FAILED);
            assert.strictEqual(result.isSettled, false);
            assert.strictEqual(result.errorCode, null, 'Non-JSON server error must not have an errorCode');
            assert.strictEqual(ui.retryInitBtn.style.display, 'none', 'Retry button must NOT be shown for unclassified server errors');
            assert.strictEqual(ui.startUploadBtn.disabled, true);
            assert.strictEqual(ui.fileInput.disabled, true);
            assert.strictEqual(ui.alerts.length, 1);
            assert.strictEqual(ui.alerts[0].type, 'error');
            assert.ok(ui.alerts[0].message.includes('Cần kiểm tra hệ thống hoặc đối soát'));
        });
    });
});
