const { describe, test } = require('node:test');
const assert = require('node:assert');
const {
    UploadPhase,
    createAttempt,
    UploadCoordinator
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
});
