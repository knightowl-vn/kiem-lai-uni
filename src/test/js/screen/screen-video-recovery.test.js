const { describe, test } = require('node:test');
const assert = require('node:assert');
const {
    RecoveryClassification,
    formatBytes,
    formatDate,
    determineAllowedActions,
    clearMatchingLocalAttempt,
    isUploaderActive,
    RecoveryCoordinator,
    RecoveryViewRenderer
} = require('../../../main/resources/static/js/screen/screen-video-recovery.js');

describe('MS-08E3-S3 Screen Video Recovery & Replacement Policy Tests', () => {

    const ENTRY_ID = '22222222-2222-2222-2222-222222222222';
    const TITLE_ID = '11111111-1111-1111-1111-111111111111';
    const SESSION_ID = '33333333-3333-3333-3333-333333333333';
    const ASSET_ID = '44444444-4444-4444-4444-444444444444';
    const OLD_ASSET_ID = '55555555-5555-5555-5555-555555555555';

    describe('1. Classification and Allowed Actions Mapping', () => {

        test('UNATTACHED initial session without existing attached video allows attach and discard', () => {
            const session = {
                sessionId: SESSION_ID,
                videoMediaAssetId: ASSET_ID,
                originalFilename: 'episode1.mp4',
                declaredSizeBytes: 104857600,
                completedAt: '2026-10-21T06:00:00Z',
                intent: 'INITIAL',
                replacementExpectedAssetId: null,
                classification: RecoveryClassification.UNATTACHED,
                canRetry: true,
                canDiscard: true
            };

            const actions = determineAllowedActions(session, null);
            assert.strictEqual(actions.canAttach, true);
            assert.strictEqual(actions.canReplace, false);
            assert.strictEqual(actions.canDiscard, true);
            assert.strictEqual(actions.statusText, 'Đã tải lên, chờ gắn video');
            assert.strictEqual(actions.badgeClass, 'badge-info');
            assert.strictEqual(actions.reason, null);
        });

        test('UNATTACHED initial session when entry already has an attached video forbids attach over it', () => {
            const session = {
                sessionId: SESSION_ID,
                videoMediaAssetId: ASSET_ID,
                originalFilename: 'episode1.mp4',
                declaredSizeBytes: 104857600,
                intent: 'INITIAL',
                classification: RecoveryClassification.UNATTACHED,
                canRetry: true,
                canDiscard: true
            };

            const actions = determineAllowedActions(session, OLD_ASSET_ID);
            assert.strictEqual(actions.canAttach, false);
            assert.strictEqual(actions.canReplace, false);
            assert.strictEqual(actions.canDiscard, true);
            assert.strictEqual(actions.statusText, 'Tập phim đã có video');
            assert.ok(actions.reason.includes('không thể dùng để thay thế'));
        });

        test('UNATTACHED replacement session when current video matches expected allows replace and discard', () => {
            const session = {
                sessionId: SESSION_ID,
                videoMediaAssetId: ASSET_ID,
                originalFilename: 'episode1-fixed.mp4',
                declaredSizeBytes: 104857600,
                intent: 'REPLACEMENT',
                replacementExpectedAssetId: OLD_ASSET_ID,
                classification: RecoveryClassification.UNATTACHED,
                canRetry: true,
                canDiscard: true
            };

            const actions = determineAllowedActions(session, OLD_ASSET_ID);
            assert.strictEqual(actions.canAttach, false);
            assert.strictEqual(actions.canReplace, true);
            assert.strictEqual(actions.canDiscard, true);
            assert.strictEqual(actions.statusText, 'Đã tải lên, chờ thay thế video');
            assert.strictEqual(actions.badgeClass, 'badge-info');
            assert.strictEqual(actions.reason, null);
        });

        test('UNATTACHED replacement session when current video changed detects conflict and forbids replace', () => {
            const session = {
                sessionId: SESSION_ID,
                videoMediaAssetId: ASSET_ID,
                originalFilename: 'episode1-fixed.mp4',
                declaredSizeBytes: 104857600,
                intent: 'REPLACEMENT',
                replacementExpectedAssetId: OLD_ASSET_ID,
                classification: RecoveryClassification.UNATTACHED,
                canRetry: true,
                canDiscard: true
            };

            const differentAssetId = '99999999-9999-9999-9999-999999999999';
            const actions = determineAllowedActions(session, differentAssetId);
            assert.strictEqual(actions.canAttach, false);
            assert.strictEqual(actions.canReplace, false);
            assert.strictEqual(actions.canDiscard, true);
            assert.strictEqual(actions.statusText, 'Xung đột thay thế video');
            assert.ok(actions.reason.includes('đã thay đổi'));
        });

        test('UNATTACHED session with canRetry=false forbids attach and replace but allows discard if canDiscard', () => {
            const session = {
                sessionId: SESSION_ID,
                videoMediaAssetId: ASSET_ID,
                intent: 'INITIAL',
                classification: RecoveryClassification.UNATTACHED,
                canRetry: false,
                canDiscard: true
            };

            const actions = determineAllowedActions(session, null);
            assert.strictEqual(actions.canAttach, false);
            assert.strictEqual(actions.canReplace, false);
            assert.strictEqual(actions.canDiscard, true);
            assert.strictEqual(actions.statusText, 'Không thể gắn video');
        });

        test('ATTACHED_TO_TARGET is read-only, forbids attach and discard', () => {
            const session = {
                sessionId: SESSION_ID,
                videoMediaAssetId: ASSET_ID,
                classification: RecoveryClassification.ATTACHED_TO_TARGET,
                canRetry: false,
                canDiscard: false
            };

            const actions = determineAllowedActions(session, ASSET_ID);
            assert.strictEqual(actions.canAttach, false);
            assert.strictEqual(actions.canReplace, false);
            assert.strictEqual(actions.canDiscard, false);
            assert.strictEqual(actions.statusText, 'Đã gắn vào tập phim này');
            assert.strictEqual(actions.badgeClass, 'badge-success');
        });

        test('REFERENCED_ELSEWHERE is read-only, forbids attach and discard', () => {
            const session = {
                sessionId: SESSION_ID,
                videoMediaAssetId: ASSET_ID,
                classification: RecoveryClassification.REFERENCED_ELSEWHERE,
                canRetry: false,
                canDiscard: false
            };

            const actions = determineAllowedActions(session, null);
            assert.strictEqual(actions.canAttach, false);
            assert.strictEqual(actions.canReplace, false);
            assert.strictEqual(actions.canDiscard, false);
            assert.strictEqual(actions.statusText, 'Đang được sử dụng bởi tập phim khác');
            assert.ok(actions.reason.includes('ScreenEntry khác'));
        });

        test('TARGET_MISSING allows discard but forbids attach', () => {
            const session = {
                sessionId: SESSION_ID,
                videoMediaAssetId: ASSET_ID,
                classification: RecoveryClassification.TARGET_MISSING,
                canRetry: false,
                canDiscard: true
            };

            const actions = determineAllowedActions(session, null);
            assert.strictEqual(actions.canAttach, false);
            assert.strictEqual(actions.canReplace, false);
            assert.strictEqual(actions.canDiscard, true);
            assert.strictEqual(actions.statusText, 'Tập phim mục tiêu không còn tồn tại');
        });

        test('ALREADY_DISCARDED is terminal, forbids all operations', () => {
            const session = {
                sessionId: SESSION_ID,
                videoMediaAssetId: ASSET_ID,
                classification: RecoveryClassification.ALREADY_DISCARDED,
                canRetry: false,
                canDiscard: false
            };

            const actions = determineAllowedActions(session, null);
            assert.strictEqual(actions.canAttach, false);
            assert.strictEqual(actions.canReplace, false);
            assert.strictEqual(actions.canDiscard, false);
            assert.strictEqual(actions.statusText, 'Phiên tải lên đã được hủy');
        });

        test('INCONSISTENT shows warning, forbids destructive actions and auto-attach', () => {
            const session = {
                sessionId: SESSION_ID,
                videoMediaAssetId: ASSET_ID,
                classification: RecoveryClassification.INCONSISTENT,
                canRetry: false,
                canDiscard: false
            };

            const actions = determineAllowedActions(session, null);
            assert.strictEqual(actions.canAttach, false);
            assert.strictEqual(actions.canReplace, false);
            assert.strictEqual(actions.canDiscard, false);
            assert.strictEqual(actions.statusText, 'Trạng thái không nhất quán / cần kiểm tra thủ công');
            assert.strictEqual(actions.badgeClass, 'badge-danger');
        });

        test('Malformed session object fails closed safely', () => {
            const actions = determineAllowedActions(null, null);
            assert.strictEqual(actions.canAttach, false);
            assert.strictEqual(actions.canReplace, false);
            assert.strictEqual(actions.canDiscard, false);
            assert.strictEqual(actions.badgeClass, 'badge-danger');
        });
    });

    describe('2. RecoveryCoordinator Discovery & Generation Fencing', () => {

        test('fetchRecoverableSessions queries backend with entryId and returns session items', async () => {
            let capturedUrl = null;
            let capturedHeaders = null;

            const fakeFetch = async (url, options) => {
                capturedUrl = url;
                capturedHeaders = options.headers;
                return {
                    ok: true,
                    json: async () => ({
                        items: [{
                            sessionId: SESSION_ID,
                            videoMediaAssetId: ASSET_ID,
                            classification: 'UNATTACHED',
                            declaredSizeBytes: 1000
                        }],
                        page: 0,
                        size: 20,
                        totalElements: 1,
                        totalPages: 1,
                        first: true,
                        last: true
                    })
                };
            };

            const coordinator = new RecoveryCoordinator({
                titleId: TITLE_ID,
                entryId: ENTRY_ID,
                fetchFn: fakeFetch
            });

            const result = await coordinator.fetchRecoverableSessions();
            assert.strictEqual(result.isStale, false);
            assert.strictEqual(result.items.length, 1);
            assert.strictEqual(result.items[0].sessionId, SESSION_ID);
            assert.ok(capturedUrl.includes('/admin/screen/video-uploads/recoverable?entryId=' + ENTRY_ID));
            assert.strictEqual(capturedHeaders.Accept, 'application/json');
        });

        test('Empty recovery sessions returns empty items list and isStale=false', async () => {
            const fakeFetch = async () => ({
                ok: true,
                json: async () => ({
                    items: [],
                    page: 0,
                    size: 20,
                    totalElements: 0,
                    totalPages: 0,
                    first: true,
                    last: true
                })
            });

            const coordinator = new RecoveryCoordinator({
                titleId: TITLE_ID,
                entryId: ENTRY_ID,
                fetchFn: fakeFetch
            });

            const result = await coordinator.fetchRecoverableSessions();
            assert.strictEqual(result.isStale, false);
            assert.strictEqual(result.items.length, 0);
            assert.strictEqual(result.totalElements, 0);
        });

        test('Generation Fencing: Out-of-order query responses from earlier generation are discarded', async () => {
            let callCount = 0;
            let resolveFirstCall;
            const firstCallPromise = new Promise(resolve => { resolveFirstCall = resolve; });

            const fakeFetch = async (url) => {
                callCount++;
                if (callCount === 1) {
                    // Slow response for Generation 1
                    await firstCallPromise;
                    return {
                        ok: true,
                        json: async () => ({
                            items: [{ sessionId: 'stale-session' }]
                        })
                    };
                } else {
                    // Fast response for Generation 2
                    return {
                        ok: true,
                        json: async () => ({
                            items: [{ sessionId: 'fresh-session' }]
                        })
                    };
                }
            };

            const coordinator = new RecoveryCoordinator({
                titleId: TITLE_ID,
                entryId: ENTRY_ID,
                fetchFn: fakeFetch
            });

            // Start Gen 1 (pending)
            const gen1Promise = coordinator.fetchRecoverableSessions();

            // Start Gen 2 immediately (completes first)
            const gen2Result = await coordinator.fetchRecoverableSessions();
            assert.strictEqual(gen2Result.isStale, false);
            assert.strictEqual(gen2Result.items[0].sessionId, 'fresh-session');

            // Now resolve Gen 1
            resolveFirstCall();
            const gen1Result = await gen1Promise;

            // Gen 1 must be flagged as stale!
            assert.strictEqual(gen1Result.isStale, true);
            // Authoritative lastLoadedSessions remains Gen 2
            assert.strictEqual(coordinator.lastLoadedSessions[0].sessionId, 'fresh-session');
        });

        test('Backend 403 Forbidden raises Vietnamese permission error', async () => {
            const fakeFetch = async () => ({
                ok: false,
                status: 403,
                json: async () => ({ message: 'Access denied' })
            });

            const coordinator = new RecoveryCoordinator({
                titleId: TITLE_ID,
                entryId: ENTRY_ID,
                fetchFn: fakeFetch
            });

            await assert.rejects(
                async () => coordinator.fetchRecoverableSessions(),
                /không có quyền truy cập/
            );
        });
    });

    describe('3. Session Attachment & Replacement Safety', () => {

        test('attachSession sends POST with CSRF header, payload, and updates currentAttachedAssetId', async () => {
            let capturedUrl = null;
            let capturedHeaders = null;
            let capturedBody = null;

            const fakeFetch = async (url, options) => {
                capturedUrl = url;
                capturedHeaders = options.headers;
                capturedBody = JSON.parse(options.body);
                return {
                    ok: true,
                    json: async () => ({
                        videoMediaAssetId: ASSET_ID,
                        playbackUrl: '/admin/screen/titles/' + TITLE_ID + '/entries/' + ENTRY_ID + '/video/content',
                        status: 'ATTACHED',
                        message: 'Video đã được gắn thành công'
                    })
                };
            };

            const coordinator = new RecoveryCoordinator({
                titleId: TITLE_ID,
                entryId: ENTRY_ID,
                fetchFn: fakeFetch,
                getCsrfHeaders: (h) => { h['X-CSRF-TOKEN'] = 'mock-csrf-token'; return h; }
            });

            const session = {
                sessionId: SESSION_ID,
                videoMediaAssetId: ASSET_ID,
                intent: 'INITIAL'
            };

            const result = await coordinator.attachSession(session);
            assert.strictEqual(result.status, 'ATTACHED');
            assert.strictEqual(coordinator.currentAttachedAssetId, ASSET_ID);
            assert.ok(capturedUrl.includes('/video/attach'));
            assert.strictEqual(capturedHeaders['X-CSRF-TOKEN'], 'mock-csrf-token');
            assert.strictEqual(capturedBody.sessionId, SESSION_ID);
            assert.strictEqual(capturedBody.videoMediaAssetId, ASSET_ID);
            assert.strictEqual(capturedBody.isReplacement, 'false');
        });

        test('attachSession with replacement sends isReplacement=true and expectedCurrentVideoMediaAssetId', async () => {
            let capturedBody = null;

            const fakeFetch = async (url, options) => {
                capturedBody = JSON.parse(options.body);
                return {
                    ok: true,
                    json: async () => ({
                        videoMediaAssetId: ASSET_ID,
                        status: 'REPLACED'
                    })
                };
            };

            const coordinator = new RecoveryCoordinator({
                titleId: TITLE_ID,
                entryId: ENTRY_ID,
                currentAttachedAssetId: OLD_ASSET_ID,
                fetchFn: fakeFetch
            });

            const session = {
                sessionId: SESSION_ID,
                videoMediaAssetId: ASSET_ID,
                intent: 'REPLACEMENT',
                replacementExpectedAssetId: OLD_ASSET_ID
            };

            await coordinator.attachSession(session);
            assert.strictEqual(capturedBody.isReplacement, 'true');
            assert.strictEqual(capturedBody.expectedCurrentVideoMediaAssetId, OLD_ASSET_ID);
            assert.strictEqual(coordinator.currentAttachedAssetId, ASSET_ID);
        });

        test('attachSession 409 Conflict translates into actionable Vietnamese conflict error', async () => {
            const fakeFetch = async () => ({
                ok: false,
                status: 409,
                json: async () => ({
                    errorCode: 'SCREEN_ENTRY_VIDEO_CONFLICT',
                    message: 'Video hiện tại không khớp với phiên thay thế.'
                })
            });

            const coordinator = new RecoveryCoordinator({
                titleId: TITLE_ID,
                entryId: ENTRY_ID,
                currentAttachedAssetId: OLD_ASSET_ID,
                fetchFn: fakeFetch
            });

            const session = {
                sessionId: SESSION_ID,
                videoMediaAssetId: ASSET_ID,
                intent: 'REPLACEMENT',
                replacementExpectedAssetId: OLD_ASSET_ID
            };

            await assert.rejects(
                async () => coordinator.attachSession(session),
                /Xung đột thay thế video/
            );
            // Old attached video remains unaffected!
            assert.strictEqual(coordinator.currentAttachedAssetId, OLD_ASSET_ID);
        });

        test('Duplicate attach submission while mutating is strictly rejected', async () => {
            let slowResolve;
            const slowPromise = new Promise(resolve => { slowResolve = resolve; });

            const fakeFetch = async () => {
                await slowPromise;
                return {
                    ok: true,
                    json: async () => ({ status: 'ATTACHED' })
                };
            };

            const coordinator = new RecoveryCoordinator({
                titleId: TITLE_ID,
                entryId: ENTRY_ID,
                fetchFn: fakeFetch
            });

            const session = {
                sessionId: SESSION_ID,
                videoMediaAssetId: ASSET_ID,
                intent: 'INITIAL'
            };

            const call1 = coordinator.attachSession(session);

            // Immediate second call while in-flight
            await assert.rejects(
                async () => coordinator.attachSession(session),
                /Một thao tác khác đang được thực hiện/
            );

            slowResolve();
            await call1;
            assert.strictEqual(coordinator.isMutating, false);
        });
    });

    describe('4. Guarded Discard & Confirmation Safety', () => {

        test('discardSession prompts explicit confirmation; user cancel aborts without network call', async () => {
            let fetchCalled = false;
            let confirmPrompt = null;

            const fakeFetch = async () => {
                fetchCalled = true;
                return { ok: true, json: async () => ({}) };
            };

            const coordinator = new RecoveryCoordinator({
                titleId: TITLE_ID,
                entryId: ENTRY_ID,
                fetchFn: fakeFetch,
                confirmFn: (msg) => {
                    confirmPrompt = msg;
                    return false; // User clicks Cancel
                }
            });

            const session = {
                sessionId: SESSION_ID,
                videoMediaAssetId: ASSET_ID,
                originalFilename: 'test.mp4',
                declaredSizeBytes: 50000000
            };

            const result = await coordinator.discardSession(session);
            assert.strictEqual(result.cancelled, true);
            assert.strictEqual(fetchCalled, false);
            assert.ok(confirmPrompt.includes('test.mp4'));
            assert.ok(confirmPrompt.includes('Hoàn toàn KHÔNG xóa hoặc ảnh hưởng đến video đang phát sóng'));
        });

        test('discardSession confirmed sends POST to session-rooted endpoint with CSRF', async () => {
            let capturedUrl = null;
            let capturedBody = null;
            let capturedHeaders = null;

            const fakeFetch = async (url, options) => {
                capturedUrl = url;
                capturedHeaders = options.headers;
                capturedBody = JSON.parse(options.body);
                return {
                    ok: true,
                    json: async () => ({
                        sessionId: SESSION_ID,
                        videoMediaAssetId: ASSET_ID,
                        outcome: 'DISCARDED',
                        message: 'Phiên tải lên đã được hủy thành công.'
                    })
                };
            };

            const coordinator = new RecoveryCoordinator({
                titleId: TITLE_ID,
                entryId: ENTRY_ID,
                fetchFn: fakeFetch,
                confirmFn: () => true,
                getCsrfHeaders: (h) => { h['X-CSRF-TOKEN'] = 'csrf-discard'; return h; }
            });

            const session = {
                sessionId: SESSION_ID,
                videoMediaAssetId: ASSET_ID,
                originalFilename: 'test.mp4',
                declaredSizeBytes: 50000000
            };

            const result = await coordinator.discardSession(session);
            assert.strictEqual(result.outcome, 'DISCARDED');
            assert.ok(capturedUrl.includes('/admin/screen/video-uploads/' + SESSION_ID + '/discard'));
            assert.strictEqual(capturedHeaders['X-CSRF-TOKEN'], 'csrf-discard');
            assert.strictEqual(capturedBody.videoMediaAssetId, ASSET_ID);
        });

        test('discardSession 409 Reference Conflict throws informative error without corrupting state', async () => {
            const fakeFetch = async () => ({
                ok: false,
                status: 409,
                json: async () => ({
                    errorCode: 'SCREEN_VIDEO_ALREADY_REFERENCED',
                    message: 'Video đang được tham chiếu.'
                })
            });

            const coordinator = new RecoveryCoordinator({
                titleId: TITLE_ID,
                entryId: ENTRY_ID,
                fetchFn: fakeFetch,
                confirmFn: () => true
            });

            const session = {
                sessionId: SESSION_ID,
                videoMediaAssetId: ASSET_ID
            };

            await assert.rejects(
                async () => coordinator.discardSession(session),
                /Video này đang được tham chiếu bởi một ScreenEntry khác/
            );
        });
    });

    describe('5. Local Storage Reconciliation & Stale Hint Invalidation', () => {

        test('reconcileLocalStorage removes saved attempt when server reports session ATTACHED_TO_TARGET', () => {
            const storageMap = new Map();
            storageMap.set('screen_video_attempt_' + ENTRY_ID, JSON.stringify({
                sessionId: SESSION_ID,
                fileName: 'test.mp4'
            }));

            const fakeStorage = {
                getItem: (k) => storageMap.get(k) || null,
                removeItem: (k) => storageMap.delete(k)
            };

            const coordinator = new RecoveryCoordinator({
                titleId: TITLE_ID,
                entryId: ENTRY_ID,
                storage: fakeStorage
            });

            const serverSessions = [{
                sessionId: SESSION_ID,
                classification: RecoveryClassification.ATTACHED_TO_TARGET
            }];

            coordinator.reconcileLocalStorage(serverSessions);
            assert.strictEqual(storageMap.has('screen_video_attempt_' + ENTRY_ID), false);
        });

        test('reconcileLocalStorage removes saved attempt when server reports session ALREADY_DISCARDED', () => {
            const storageMap = new Map();
            storageMap.set('screen_video_attempt_' + ENTRY_ID, JSON.stringify({
                sessionId: SESSION_ID,
                fileName: 'test.mp4'
            }));

            const fakeStorage = {
                getItem: (k) => storageMap.get(k) || null,
                removeItem: (k) => storageMap.delete(k)
            };

            const coordinator = new RecoveryCoordinator({
                titleId: TITLE_ID,
                entryId: ENTRY_ID,
                storage: fakeStorage
            });

            const serverSessions = [{
                sessionId: SESSION_ID,
                classification: RecoveryClassification.ALREADY_DISCARDED
            }];

            coordinator.reconcileLocalStorage(serverSessions);
            assert.strictEqual(storageMap.has('screen_video_attempt_' + ENTRY_ID), false);
        });

        test('reconcileLocalStorage retains attempt when server reports UNATTACHED', () => {
            const storageMap = new Map();
            storageMap.set('screen_video_attempt_' + ENTRY_ID, JSON.stringify({
                sessionId: SESSION_ID,
                fileName: 'test.mp4'
            }));

            const fakeStorage = {
                getItem: (k) => storageMap.get(k) || null,
                removeItem: (k) => storageMap.delete(k)
            };

            const coordinator = new RecoveryCoordinator({
                titleId: TITLE_ID,
                entryId: ENTRY_ID,
                storage: fakeStorage
            });

            const serverSessions = [{
                sessionId: SESSION_ID,
                classification: RecoveryClassification.UNATTACHED
            }];

            coordinator.reconcileLocalStorage(serverSessions);
            assert.strictEqual(storageMap.has('screen_video_attempt_' + ENTRY_ID), true);
        });
    });

    describe('6. Utility Formatting & Safety', () => {

        test('formatBytes produces consistent binary units', () => {
            assert.strictEqual(formatBytes(0), '0 B');
            assert.strictEqual(formatBytes(512), '512 B');
            assert.strictEqual(formatBytes(1048576), '1.00 MiB');
            assert.strictEqual(formatBytes(1073741824), '1.00 GiB');
            assert.strictEqual(formatBytes(-1), '0 B');
            assert.strictEqual(formatBytes('invalid'), '0 B');
        });

        test('formatDate handles valid ISO dates and malformed strings safely', () => {
            const valid = formatDate('2026-10-21T06:30:00Z');
            assert.ok(valid.length > 5);
            assert.strictEqual(formatDate(null), 'Chưa rõ');
            assert.strictEqual(formatDate(''), 'Chưa rõ');
            assert.strictEqual(formatDate('not-a-date'), 'not-a-date');
        });
    });

    describe('7. MS-08E3-S3 Integration Corrective: Suite B Local Attempt Isolation (B1-B6)', () => {

        test('B1: Saved session A, discard A succeeds → A may be cleared', () => {
            const storageMap = new Map();
            storageMap.set('screen_video_attempt_' + ENTRY_ID, JSON.stringify({
                sessionId: 'session-A',
                fileName: 'video_a.mp4'
            }));
            const fakeStorage = {
                getItem: (k) => storageMap.get(k) || null,
                removeItem: (k) => storageMap.delete(k)
            };

            const coordinator = new RecoveryCoordinator({
                entryId: ENTRY_ID,
                storage: fakeStorage
            });

            const cleared = coordinator.clearLocalAttempt('session-A');
            assert.strictEqual(cleared, true);
            assert.strictEqual(storageMap.has('screen_video_attempt_' + ENTRY_ID), false);
        });

        test('B2: Saved session A, discard B succeeds → A remains', () => {
            const storageMap = new Map();
            storageMap.set('screen_video_attempt_' + ENTRY_ID, JSON.stringify({
                sessionId: 'session-A',
                fileName: 'video_a.mp4'
            }));
            const fakeStorage = {
                getItem: (k) => storageMap.get(k) || null,
                removeItem: (k) => storageMap.delete(k)
            };

            const coordinator = new RecoveryCoordinator({
                entryId: ENTRY_ID,
                storage: fakeStorage
            });

            const cleared = coordinator.clearLocalAttempt('session-B');
            assert.strictEqual(cleared, false);
            assert.strictEqual(storageMap.has('screen_video_attempt_' + ENTRY_ID), true);
            const saved = JSON.parse(storageMap.get('screen_video_attempt_' + ENTRY_ID));
            assert.strictEqual(saved.sessionId, 'session-A');
        });

        test('B3: Saved session A, attach B succeeds → A remains', async () => {
            const storageMap = new Map();
            storageMap.set('screen_video_attempt_' + ENTRY_ID, JSON.stringify({
                sessionId: 'session-A',
                fileName: 'video_a.mp4'
            }));
            const fakeStorage = {
                getItem: (k) => storageMap.get(k) || null,
                removeItem: (k) => storageMap.delete(k)
            };

            const fakeFetch = async () => ({
                ok: true,
                json: async () => ({
                    videoMediaAssetId: ASSET_ID,
                    status: 'ATTACHED'
                })
            });

            const coordinator = new RecoveryCoordinator({
                titleId: TITLE_ID,
                entryId: ENTRY_ID,
                storage: fakeStorage,
                fetchFn: fakeFetch
            });

            await coordinator.attachSession({
                sessionId: 'session-B',
                videoMediaAssetId: ASSET_ID,
                intent: 'INITIAL'
            });

            assert.strictEqual(storageMap.has('screen_video_attempt_' + ENTRY_ID), true);
            const saved = JSON.parse(storageMap.get('screen_video_attempt_' + ENTRY_ID));
            assert.strictEqual(saved.sessionId, 'session-A');
        });

        test('B4: Saved attempt has an idempotency key but no sessionId → unrelated recovery mutation preserves it', () => {
            const storageMap = new Map();
            storageMap.set('screen_video_attempt_' + ENTRY_ID, JSON.stringify({
                idempotencyKey: 'idemp-screen-entry-abc',
                fileName: 'hashing_in_progress.mp4'
            }));
            const fakeStorage = {
                getItem: (k) => storageMap.get(k) || null,
                removeItem: (k) => storageMap.delete(k)
            };

            const cleared = clearMatchingLocalAttempt(fakeStorage, ENTRY_ID, 'session-B');
            assert.strictEqual(cleared, false);
            assert.strictEqual(storageMap.has('screen_video_attempt_' + ENTRY_ID), true);
            const saved = JSON.parse(storageMap.get('screen_video_attempt_' + ENTRY_ID));
            assert.strictEqual(saved.idempotencyKey, 'idemp-screen-entry-abc');
            assert.strictEqual(saved.sessionId, undefined);
        });

        test('B5: Discard or attach fails → saved attempt remains unchanged', async () => {
            const storageMap = new Map();
            storageMap.set('screen_video_attempt_' + ENTRY_ID, JSON.stringify({
                sessionId: 'session-A',
                fileName: 'video_a.mp4'
            }));
            const fakeStorage = {
                getItem: (k) => storageMap.get(k) || null,
                removeItem: (k) => storageMap.delete(k)
            };

            const fakeFetch = async () => ({
                ok: false,
                status: 500,
                json: async () => ({ message: 'Server error' })
            });

            const coordinator = new RecoveryCoordinator({
                titleId: TITLE_ID,
                entryId: ENTRY_ID,
                storage: fakeStorage,
                fetchFn: fakeFetch,
                confirmFn: () => true
            });

            await assert.rejects(
                async () => coordinator.discardSession({ sessionId: 'session-A', videoMediaAssetId: ASSET_ID }),
                /Server error/
            );

            assert.strictEqual(storageMap.has('screen_video_attempt_' + ENTRY_ID), true);
            const saved = JSON.parse(storageMap.get('screen_video_attempt_' + ENTRY_ID));
            assert.strictEqual(saved.sessionId, 'session-A');
        });

        test('B6: Local storage is unavailable or malformed → recovery functions handle this safely', () => {
            // Null storage
            assert.strictEqual(clearMatchingLocalAttempt(null, ENTRY_ID, 'session-A'), false);

            // Malformed JSON
            const corruptStorage = {
                getItem: () => '{corrupted json content',
                removeItem: () => { throw new Error('Should not be called'); }
            };
            assert.strictEqual(clearMatchingLocalAttempt(corruptStorage, ENTRY_ID, 'session-A'), false);

            // Storage that throws on getItem
            const throwingStorage = {
                getItem: () => { throw new Error('QuotaExceeded or SecurityError'); },
                removeItem: () => {}
            };
            assert.strictEqual(clearMatchingLocalAttempt(throwingStorage, ENTRY_ID, 'session-A'), false);
        });
    });

    describe('8. MS-08E3-S3 Concurrent Uploader / Recovery Mutual Exclusion Policy Tests', () => {

        test('isUploaderActive accurately determines active vs settled uploader state', () => {
            // Null or malformed coordinator
            assert.strictEqual(isUploaderActive(null), false);
            assert.strictEqual(isUploaderActive({}), false);
            assert.strictEqual(isUploaderActive({ getCurrentAttempt: () => null }), false);

            // Settled attempt
            assert.strictEqual(isUploaderActive({
                getCurrentAttempt: () => ({ sessionId: 'sess-1', isSettled: true })
            }), false);

            // Active, unsettled attempt
            assert.strictEqual(isUploaderActive({
                getCurrentAttempt: () => ({ sessionId: 'sess-1', isSettled: false })
            }), true);

            // Active without isSettled field (defaults to unsettled)
            assert.strictEqual(isUploaderActive({
                getCurrentAttempt: () => ({ sessionId: 'sess-1' })
            }), true);
        });

        test('RecoveryCoordinator.attachSession fail-closed rejects when uploader attempt is active', async () => {
            let fetchDispatched = false;
            const fakeFetch = async () => {
                fetchDispatched = true;
                return { ok: true, json: async () => ({ status: 'ATTACHED' }) };
            };

            const mockUploader = {
                getCurrentAttempt: () => ({ sessionId: 'active-upload-a', isSettled: false })
            };

            const coordinator = new RecoveryCoordinator({
                titleId: TITLE_ID,
                entryId: ENTRY_ID,
                fetchFn: fakeFetch,
                uploaderCoordinator: mockUploader
            });

            await assert.rejects(
                async () => coordinator.attachSession({
                    sessionId: 'session-B',
                    videoMediaAssetId: ASSET_ID,
                    intent: 'INITIAL'
                }),
                /Không thể gắn hoặc thay thế video từ phiên khôi phục trong khi một tiến trình tải lên video khác đang được thực hiện/
            );

            // Crucial: no backend fetch occurred!
            assert.strictEqual(fetchDispatched, false);
        });

        test('RecoveryCoordinator.attachSession permits attach when uploader attempt is settled', async () => {
            let fetchDispatched = false;
            const fakeFetch = async () => {
                fetchDispatched = true;
                return { ok: true, json: async () => ({ videoMediaAssetId: ASSET_ID, status: 'ATTACHED' }) };
            };

            const mockUploader = {
                getCurrentAttempt: () => ({ sessionId: 'settled-upload-a', isSettled: true })
            };

            const coordinator = new RecoveryCoordinator({
                titleId: TITLE_ID,
                entryId: ENTRY_ID,
                fetchFn: fakeFetch,
                uploaderCoordinator: mockUploader
            });

            const result = await coordinator.attachSession({
                sessionId: 'session-B',
                videoMediaAssetId: ASSET_ID,
                intent: 'INITIAL'
            });

            assert.strictEqual(fetchDispatched, true);
            assert.strictEqual(result.status, 'ATTACHED');
        });
    });
});
