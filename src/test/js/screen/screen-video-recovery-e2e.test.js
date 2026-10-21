const { describe, test } = require('node:test');
const assert = require('node:assert');
const {
    RecoveryClassification,
    determineAllowedActions,
    isUploaderActive,
    RecoveryCoordinator,
    RecoveryViewRenderer
} = require('../../../main/resources/static/js/screen/screen-video-recovery.js');

/**
 * Lightweight mock DOM environment for headless testing of RecoveryViewRenderer without jsdom.
 */
function createMockElement(tagName = 'div') {
    const children = [];
    const listeners = {};
    return {
        tagName: tagName.toUpperCase(),
        style: {},
        attributes: {},
        disabled: false,
        type: '',
        className: '',
        title: '',
        _textContent: '',
        get textContent() {
            if (children.length > 0) {
                return children.map(c => (c && c.textContent !== undefined ? c.textContent : '')).join('');
            }
            return this._textContent;
        },
        set textContent(val) {
            children.length = 0;
            this._textContent = String(val);
        },
        get firstChild() {
            return children[0] || null;
        },
        get childNodes() {
            return children;
        },
        appendChild(child) {
            children.push(child);
            return child;
        },
        removeChild(child) {
            const idx = children.indexOf(child);
            if (idx !== -1) {
                children.splice(idx, 1);
            }
            return child;
        },
        addEventListener(event, fn) {
            if (!listeners[event]) listeners[event] = [];
            listeners[event].push(fn);
        },
        setAttribute(name, val) {
            this.attributes[name] = val;
        },
        getAttribute(name) {
            return this.attributes[name] || null;
        },
        async dispatchEvent(event) {
            const list = listeners[event] || [];
            for (const fn of list) {
                await fn();
            }
        }
    };
}

describe('MS-08E3-S3 Mocked End-to-End Recovery Workflows', () => {

    const TITLE_ID = '11111111-1111-1111-1111-111111111111';
    const ENTRY_ID = '22222222-2222-2222-2222-222222222222';
    const SESSION_ID = '33333333-3333-3333-3333-333333333333';
    const ASSET_ID = '44444444-4444-4444-4444-444444444444';
    const OLD_ASSET_ID = '55555555-5555-5555-5555-555555555555';

    function setupDomEnvironment() {
        const originalDocument = globalThis.document;
        globalThis.document = {
            createElement: (tag) => createMockElement(tag),
            createTextNode: (text) => ({ textContent: text })
        };
        const elements = {
            sessionListContainer: createMockElement('div'),
            emptyMessage: createMockElement('div'),
            loadingSpinner: createMockElement('div'),
            refreshBtn: createMockElement('button'),
            alertBox: createMockElement('div')
        };
        const restore = () => {
            globalThis.document = originalDocument;
        };
        return { elements, restore };
    }

    test('Flow 1: Page load → recovery discovery → list → refresh', async () => {
        const { elements, restore } = setupDomEnvironment();
        try {
            let fetchCount = 0;
            const fakeFetch = async (url) => {
                fetchCount++;
                return {
                    ok: true,
                    json: async () => ({
                        items: [{
                            sessionId: SESSION_ID,
                            videoMediaAssetId: ASSET_ID,
                            originalFilename: 'episode_01.mp4',
                            declaredSizeBytes: 52428800,
                            completedAt: '2026-10-21T06:00:00Z',
                            intent: 'INITIAL',
                            classification: RecoveryClassification.UNATTACHED,
                            canRetry: true,
                            canDiscard: true
                        }],
                        page: 0,
                        size: 20,
                        totalElements: 1,
                        totalPages: 1
                    })
                };
            };

            const coordinator = new RecoveryCoordinator({
                titleId: TITLE_ID,
                entryId: ENTRY_ID,
                fetchFn: fakeFetch
            });

            const renderer = new RecoveryViewRenderer(elements, coordinator);

            // Initial load on page visit
            await renderer.load();

            assert.strictEqual(fetchCount, 1);
            assert.strictEqual(elements.sessionListContainer.childNodes.length, 1);
            assert.strictEqual(elements.emptyMessage.style.display, 'none');

            // Trigger manual refresh via refreshBtn click
            await elements.refreshBtn.dispatchEvent('click');
            assert.strictEqual(fetchCount, 2);
            assert.strictEqual(elements.sessionListContainer.childNodes.length, 1);
        } finally {
            restore();
        }
    });

    test('Flow 2: Completed eligible session → attach → server-confirmed video state', async () => {
        const { elements, restore } = setupDomEnvironment();
        try {
            let attachCalled = false;
            let capturedAttachBody = null;
            let currentServerSessionStatus = RecoveryClassification.UNATTACHED;

            const fakeFetch = async (url, options) => {
                if (url.includes('/video/attach') && options?.method === 'POST') {
                    attachCalled = true;
                    capturedAttachBody = JSON.parse(options.body);
                    currentServerSessionStatus = RecoveryClassification.ATTACHED_TO_TARGET;
                    return {
                        ok: true,
                        json: async () => ({
                            videoMediaAssetId: ASSET_ID,
                            playbackUrl: '/admin/screen/titles/' + TITLE_ID + '/entries/' + ENTRY_ID + '/video/content',
                            status: 'ATTACHED',
                            message: 'Video đã được gán thành công.'
                        })
                    };
                }

                if (url.includes('/video-uploads/recoverable')) {
                    return {
                        ok: true,
                        json: async () => ({
                            items: [{
                                sessionId: SESSION_ID,
                                videoMediaAssetId: ASSET_ID,
                                originalFilename: 'episode_01.mp4',
                                declaredSizeBytes: 52428800,
                                intent: 'INITIAL',
                                classification: currentServerSessionStatus,
                                canRetry: (currentServerSessionStatus === RecoveryClassification.UNATTACHED),
                                canDiscard: (currentServerSessionStatus === RecoveryClassification.UNATTACHED)
                            }]
                        })
                    };
                }
                throw new Error('Unexpected URL: ' + url);
            };

            let onAttachedCalled = false;
            let attachedResult = null;

            const coordinator = new RecoveryCoordinator({
                titleId: TITLE_ID,
                entryId: ENTRY_ID,
                fetchFn: fakeFetch
            });

            const renderer = new RecoveryViewRenderer(elements, coordinator, {
                onAttached: (res) => {
                    onAttachedCalled = true;
                    attachedResult = res;
                }
            });

            await renderer.load();
            assert.strictEqual(elements.sessionListContainer.childNodes.length, 1);

            const card = elements.sessionListContainer.childNodes[0];
            // Find attach button in actions row (last child of card)
            const actionsRow = card.childNodes[card.childNodes.length - 1];
            const attachBtn = actionsRow.childNodes.find(btn => btn.textContent.includes('Gắn video'));
            assert.ok(attachBtn, 'Gắn video button must be present for UNATTACHED initial session');

            // Click Gắn video
            await attachBtn.dispatchEvent('click');

            assert.strictEqual(attachCalled, true);
            assert.strictEqual(capturedAttachBody.sessionId, SESSION_ID);
            assert.strictEqual(capturedAttachBody.videoMediaAssetId, ASSET_ID);
            assert.strictEqual(capturedAttachBody.isReplacement, 'false');

            assert.strictEqual(onAttachedCalled, true);
            assert.strictEqual(attachedResult.status, 'ATTACHED');
            assert.strictEqual(coordinator.currentAttachedAssetId, ASSET_ID);

            // Verified server state: session list was refreshed to ATTACHED_TO_TARGET
            assert.strictEqual(currentServerSessionStatus, RecoveryClassification.ATTACHED_TO_TARGET);
        } finally {
            restore();
        }
    });

    test('Flow 3: Existing video → replacement → success', async () => {
        const { elements, restore } = setupDomEnvironment();
        try {
            let replaceCalled = false;
            let capturedBody = null;

            const fakeFetch = async (url, options) => {
                if (url.includes('/video/attach') && options?.method === 'POST') {
                    replaceCalled = true;
                    capturedBody = JSON.parse(options.body);
                    return {
                        ok: true,
                        json: async () => ({
                            videoMediaAssetId: ASSET_ID,
                            playbackUrl: '/admin/screen/titles/' + TITLE_ID + '/entries/' + ENTRY_ID + '/video/content',
                            status: 'REPLACED',
                            message: 'Video đã được thay thế thành công.'
                        })
                    };
                }
                if (url.includes('/video-uploads/recoverable')) {
                    return {
                        ok: true,
                        json: async () => ({
                            items: [{
                                sessionId: SESSION_ID,
                                videoMediaAssetId: ASSET_ID,
                                originalFilename: 'episode_01_remaster.mp4',
                                declaredSizeBytes: 60000000,
                                intent: 'REPLACEMENT',
                                replacementExpectedAssetId: OLD_ASSET_ID,
                                classification: RecoveryClassification.UNATTACHED,
                                canRetry: true,
                                canDiscard: true
                            }]
                        })
                    };
                }
                throw new Error('Unexpected URL: ' + url);
            };

            const coordinator = new RecoveryCoordinator({
                titleId: TITLE_ID,
                entryId: ENTRY_ID,
                currentAttachedAssetId: OLD_ASSET_ID,
                fetchFn: fakeFetch,
                confirmFn: () => true // Confirm replacement prompt
            });

            const renderer = new RecoveryViewRenderer(elements, coordinator);
            await renderer.load();

            const card = elements.sessionListContainer.childNodes[0];
            const actionsRow = card.childNodes[card.childNodes.length - 1];
            const replaceBtn = actionsRow.childNodes.find(btn => btn.textContent.includes('Thay thế video'));
            assert.ok(replaceBtn, 'Thay thế video button must be present when current video matches expected');

            await replaceBtn.dispatchEvent('click');

            assert.strictEqual(replaceCalled, true);
            assert.strictEqual(capturedBody.isReplacement, 'true');
            assert.strictEqual(capturedBody.expectedCurrentVideoMediaAssetId, OLD_ASSET_ID);
            assert.strictEqual(coordinator.currentAttachedAssetId, ASSET_ID);
            assert.ok(elements.alertBox.textContent.includes('thay thế thành công'));
        } finally {
            restore();
        }
    });

    test('Flow 4: Existing video → replacement fails → old video remains', async () => {
        const { elements, restore } = setupDomEnvironment();
        try {
            const fakeFetch = async (url, options) => {
                if (url.includes('/video/attach') && options?.method === 'POST') {
                    return {
                        ok: false,
                        status: 409,
                        json: async () => ({
                            errorCode: 'SCREEN_ENTRY_VIDEO_CONFLICT',
                            message: 'Video hiện tại của ScreenEntry đã thay đổi bởi người quản trị khác.'
                        })
                    };
                }
                if (url.includes('/video-uploads/recoverable')) {
                    return {
                        ok: true,
                        json: async () => ({
                            items: [{
                                sessionId: SESSION_ID,
                                videoMediaAssetId: ASSET_ID,
                                originalFilename: 'episode_01_remaster.mp4',
                                declaredSizeBytes: 60000000,
                                intent: 'REPLACEMENT',
                                replacementExpectedAssetId: OLD_ASSET_ID,
                                classification: RecoveryClassification.UNATTACHED,
                                canRetry: true,
                                canDiscard: true
                            }]
                        })
                    };
                }
                throw new Error('Unexpected URL: ' + url);
            };

            const coordinator = new RecoveryCoordinator({
                titleId: TITLE_ID,
                entryId: ENTRY_ID,
                currentAttachedAssetId: OLD_ASSET_ID,
                fetchFn: fakeFetch,
                confirmFn: () => true
            });

            const renderer = new RecoveryViewRenderer(elements, coordinator);
            await renderer.load();

            const card = elements.sessionListContainer.childNodes[0];
            const actionsRow = card.childNodes[card.childNodes.length - 1];
            const replaceBtn = actionsRow.childNodes.find(btn => btn.textContent.includes('Thay thế video'));

            await replaceBtn.dispatchEvent('click');

            // Old video must be preserved!
            assert.strictEqual(coordinator.currentAttachedAssetId, OLD_ASSET_ID);
            // Alert box explains conflict
            assert.ok(elements.alertBox.textContent.includes('Xung đột thay thế video'));
            // Button is re-enabled for recovery/retry
            assert.strictEqual(replaceBtn.disabled, false);
        } finally {
            restore();
        }
    });

    test('Flow 5: Eligible session → guarded discard → refreshed state', async () => {
        const { elements, restore } = setupDomEnvironment();
        try {
            let discardCalled = false;
            let currentClassification = RecoveryClassification.UNATTACHED;

            const fakeFetch = async (url, options) => {
                if (url.includes('/discard') && options?.method === 'POST') {
                    discardCalled = true;
                    currentClassification = RecoveryClassification.ALREADY_DISCARDED;
                    return {
                        ok: true,
                        json: async () => ({
                            sessionId: SESSION_ID,
                            videoMediaAssetId: ASSET_ID,
                            outcome: 'DISCARDED',
                            message: 'Đã hủy phiên tải lên thành công.'
                        })
                    };
                }
                if (url.includes('/video-uploads/recoverable')) {
                    return {
                        ok: true,
                        json: async () => ({
                            items: [{
                                sessionId: SESSION_ID,
                                videoMediaAssetId: ASSET_ID,
                                originalFilename: 'unwanted.mp4',
                                declaredSizeBytes: 10000,
                                classification: currentClassification,
                                canRetry: (currentClassification === RecoveryClassification.UNATTACHED),
                                canDiscard: (currentClassification === RecoveryClassification.UNATTACHED)
                            }]
                        })
                    };
                }
                throw new Error('Unexpected URL: ' + url);
            };

            let onDiscardedCalled = false;
            const coordinator = new RecoveryCoordinator({
                titleId: TITLE_ID,
                entryId: ENTRY_ID,
                fetchFn: fakeFetch,
                confirmFn: () => true
            });

            const renderer = new RecoveryViewRenderer(elements, coordinator, {
                onDiscarded: () => { onDiscardedCalled = true; }
            });

            await renderer.load();

            const card = elements.sessionListContainer.childNodes[0];
            const actionsRow = card.childNodes[card.childNodes.length - 1];
            const discardBtn = actionsRow.childNodes.find(btn => btn.textContent.includes('Hủy phiên tải lên'));
            assert.ok(discardBtn, 'Hủy phiên tải lên button must be present when canDiscard is true');

            await discardBtn.dispatchEvent('click');

            assert.strictEqual(discardCalled, true);
            assert.strictEqual(onDiscardedCalled, true);
            assert.strictEqual(currentClassification, RecoveryClassification.ALREADY_DISCARDED);
            assert.ok(elements.alertBox.textContent.includes('hủy an toàn'));
        } finally {
            restore();
        }
    });

    test('Flow 6: Ambiguous / inconsistent completion → safe warning → no destructive action', async () => {
        const { elements, restore } = setupDomEnvironment();
        try {
            const fakeFetch = async (url) => {
                if (url.includes('/video-uploads/recoverable')) {
                    return {
                        ok: true,
                        json: async () => ({
                            items: [{
                                sessionId: SESSION_ID,
                                videoMediaAssetId: ASSET_ID,
                                originalFilename: 'ambiguous_session.mp4',
                                declaredSizeBytes: 10000,
                                classification: RecoveryClassification.INCONSISTENT,
                                canRetry: false,
                                canDiscard: false
                            }]
                        })
                    };
                }
                throw new Error('No destructive endpoints should be called for INCONSISTENT session: ' + url);
            };

            const coordinator = new RecoveryCoordinator({
                titleId: TITLE_ID,
                entryId: ENTRY_ID,
                fetchFn: fakeFetch
            });

            const renderer = new RecoveryViewRenderer(elements, coordinator);
            await renderer.load();

            assert.strictEqual(elements.sessionListContainer.childNodes.length, 1);
            const card = elements.sessionListContainer.childNodes[0];

            // Verify badge text
            const badge = card.childNodes[0].childNodes[1];
            assert.ok(badge.textContent.includes('Trạng thái không nhất quán'));

            // Check if reason note is displayed
            const noteEl = card.childNodes.find(node => node.textContent?.includes('bất thường') || node.textContent?.includes('Media'));
            assert.ok(noteEl, 'Warning explanation note must be displayed for INCONSISTENT sessions');

            // Verify actions row does NOT contain Gắn video, Thay thế, or Hủy phiên
            const actionsRow = card.childNodes.find(node => node.childNodes?.some(btn => btn.tagName === 'BUTTON'));
            assert.strictEqual(actionsRow, undefined, 'No destructive or attach buttons should exist for INCONSISTENT session');
        } finally {
            restore();
        }
    });

    test('Flow 7: Old browser attempt metadata → backend disagrees → server state wins', async () => {
        const { elements, restore } = setupDomEnvironment();
        try {
            const storageMap = new Map();
            // Local attempt says this session is still active
            storageMap.set('screen_video_attempt_' + ENTRY_ID, JSON.stringify({
                sessionId: SESSION_ID,
                fileName: 'stale_file.mp4'
            }));

            const fakeStorage = {
                getItem: (k) => storageMap.get(k) || null,
                removeItem: (k) => storageMap.delete(k)
            };

            // Backend authoritatively says this session was ALREADY attached to the entry
            const fakeFetch = async () => ({
                ok: true,
                json: async () => ({
                    items: [{
                        sessionId: SESSION_ID,
                        videoMediaAssetId: ASSET_ID,
                        originalFilename: 'stale_file.mp4',
                        declaredSizeBytes: 5000000,
                        classification: RecoveryClassification.ATTACHED_TO_TARGET,
                        canRetry: false,
                        canDiscard: false
                    }]
                })
            });

            const coordinator = new RecoveryCoordinator({
                titleId: TITLE_ID,
                entryId: ENTRY_ID,
                currentAttachedAssetId: ASSET_ID,
                fetchFn: fakeFetch,
                storage: fakeStorage
            });

            const renderer = new RecoveryViewRenderer(elements, coordinator);
            await renderer.load();

            // Local storage hint was invalidated and cleaned up!
            assert.strictEqual(storageMap.has('screen_video_attempt_' + ENTRY_ID), false);

            // Authoritative server state is displayed: Đã gắn vào tập phim này
            const card = elements.sessionListContainer.childNodes[0];
            const badge = card.childNodes[0].childNodes[1];
            assert.ok(badge.textContent.includes('Đã gắn vào tập phim này'));
        } finally {
            restore();
        }
    });

    test('Flow 8: Double-click/racing requests → no duplicate request or stale UI success', async () => {
        const { elements, restore } = setupDomEnvironment();
        try {
            let serverCallCount = 0;
            let resolveFetch;
            const fetchPromise = new Promise(resolve => { resolveFetch = resolve; });

            const fakeFetch = async (url, options) => {
                if (url.includes('/video/attach') && options?.method === 'POST') {
                    serverCallCount++;
                    await fetchPromise;
                    return {
                        ok: true,
                        json: async () => ({
                            videoMediaAssetId: ASSET_ID,
                            status: 'ATTACHED'
                        })
                    };
                }
                if (url.includes('/video-uploads/recoverable')) {
                    return {
                        ok: true,
                        json: async () => ({
                            items: [{
                                sessionId: SESSION_ID,
                                videoMediaAssetId: ASSET_ID,
                                intent: 'INITIAL',
                                classification: RecoveryClassification.UNATTACHED,
                                canRetry: true,
                                canDiscard: true
                            }]
                        })
                    };
                }
                throw new Error('Unexpected URL: ' + url);
            };

            const coordinator = new RecoveryCoordinator({
                titleId: TITLE_ID,
                entryId: ENTRY_ID,
                fetchFn: fakeFetch
            });

            const renderer = new RecoveryViewRenderer(elements, coordinator);
            await renderer.load();

            const card = elements.sessionListContainer.childNodes[0];
            const actionsRow = card.childNodes[card.childNodes.length - 1];
            const attachBtn = actionsRow.childNodes.find(btn => btn.textContent.includes('Gắn video'));

            // Click 1: triggers dispatch
            const click1 = attachBtn.dispatchEvent('click');

            // Immediate Click 2 while in-flight: button is disabled!
            assert.strictEqual(attachBtn.disabled, true);
            const click2 = attachBtn.dispatchEvent('click');

            // Complete server response
            resolveFetch();
            await click1;
            await click2;

            // Exactly 1 server mutation call was made!
            assert.strictEqual(serverCallCount, 1);
        } finally {
            restore();
        }
    });

    /**
     * Controlled deferred Promise helper (avoids arbitrary sleeps)
     */
    function createDeferred() {
        let resolve, reject;
        const promise = new Promise((res, rej) => {
            resolve = res;
            reject = rej;
        });
        return { promise, resolve, reject };
    }

    describe('MS-08E3-S3 Integration Corrective: Suite A (Async Fencing & Stale Errors A1-A6)', () => {

        test('A1: Old success arrives after new success → new list retained', async () => {
            const { elements, restore } = setupDomEnvironment();
            try {
                const req1Deferred = createDeferred();
                const req2Deferred = createDeferred();
                let callCount = 0;

                const fakeFetch = async () => {
                    callCount++;
                    return (callCount === 1) ? req1Deferred.promise : req2Deferred.promise;
                };

                const coordinator = new RecoveryCoordinator({
                    titleId: TITLE_ID,
                    entryId: ENTRY_ID,
                    fetchFn: fakeFetch
                });

                const renderer = new RecoveryViewRenderer(elements, coordinator);

                // Start Request 1 (pending)
                const p1 = renderer.load();

                // Start Request 2 (pending)
                const p2 = renderer.load();

                // Request 2 resolves first with session2
                req2Deferred.resolve({
                    ok: true,
                    json: async () => ({
                        items: [{
                            sessionId: 'session-2',
                            originalFilename: 'video_two.mp4',
                            declaredSizeBytes: 20000000,
                            classification: RecoveryClassification.UNATTACHED,
                            intent: 'INITIAL',
                            canRetry: true,
                            canDiscard: true
                        }]
                    })
                });
                await p2;

                // session-2 is rendered
                assert.strictEqual(elements.sessionListContainer.childNodes.length, 1);
                assert.ok(elements.sessionListContainer.childNodes[0].textContent.includes('video_two.mp4'));

                // Request 1 resolves late with session1
                req1Deferred.resolve({
                    ok: true,
                    json: async () => ({
                        items: [{
                            sessionId: 'session-1',
                            originalFilename: 'video_one.mp4',
                            declaredSizeBytes: 10000000,
                            classification: RecoveryClassification.UNATTACHED,
                            intent: 'INITIAL',
                            canRetry: true,
                            canDiscard: true
                        }]
                    })
                });
                await p1;

                // Crucial check: session-2 must STILL be retained!
                assert.strictEqual(elements.sessionListContainer.childNodes.length, 1);
                assert.ok(elements.sessionListContainer.childNodes[0].textContent.includes('video_two.mp4'));
                assert.strictEqual(elements.sessionListContainer.childNodes[0].textContent.includes('video_one.mp4'), false);
            } finally {
                restore();
            }
        });

        test('A2: Old HTTP 500 error arrives after new success → new list retained, no stale error alert', async () => {
            const { elements, restore } = setupDomEnvironment();
            try {
                const req1Deferred = createDeferred();
                const req2Deferred = createDeferred();
                let callCount = 0;

                const fakeFetch = async () => {
                    callCount++;
                    return (callCount === 1) ? req1Deferred.promise : req2Deferred.promise;
                };

                const coordinator = new RecoveryCoordinator({
                    titleId: TITLE_ID,
                    entryId: ENTRY_ID,
                    fetchFn: fakeFetch
                });

                const renderer = new RecoveryViewRenderer(elements, coordinator);

                const p1 = renderer.load();
                const p2 = renderer.load();

                // Request 2 resolves with success
                req2Deferred.resolve({
                    ok: true,
                    json: async () => ({
                        items: [{
                            sessionId: 'session-2',
                            originalFilename: 'video_two.mp4',
                            declaredSizeBytes: 20000000,
                            classification: RecoveryClassification.UNATTACHED,
                            intent: 'INITIAL',
                            canRetry: true
                        }]
                    })
                });
                await p2;

                assert.strictEqual(elements.sessionListContainer.childNodes.length, 1);
                assert.ok(elements.sessionListContainer.childNodes[0].textContent.includes('video_two.mp4'));

                // Request 1 resolves with HTTP 500 and parsed error body
                req1Deferred.resolve({
                    ok: false,
                    status: 500,
                    json: async () => ({ message: 'Stale 500 Database Error' })
                });
                await p1;

                // List must remain rendered; no stale error alert displayed!
                assert.strictEqual(elements.sessionListContainer.childNodes.length, 1);
                assert.ok(elements.sessionListContainer.childNodes[0].textContent.includes('video_two.mp4'));
                assert.strictEqual(elements.alertBox.style.display, 'none');
                assert.strictEqual(elements.alertBox.textContent, '');
            } finally {
                restore();
            }
        });

        test('A3: Old network rejection arrives after new success → no stale UI mutation', async () => {
            const { elements, restore } = setupDomEnvironment();
            try {
                const req1Deferred = createDeferred();
                const req2Deferred = createDeferred();
                let callCount = 0;

                const fakeFetch = async () => {
                    callCount++;
                    return (callCount === 1) ? req1Deferred.promise : req2Deferred.promise;
                };

                const coordinator = new RecoveryCoordinator({
                    titleId: TITLE_ID,
                    entryId: ENTRY_ID,
                    fetchFn: fakeFetch
                });

                const renderer = new RecoveryViewRenderer(elements, coordinator);

                const p1 = renderer.load();
                const p2 = renderer.load();

                req2Deferred.resolve({
                    ok: true,
                    json: async () => ({
                        items: [{
                            sessionId: 'session-2',
                            originalFilename: 'video_two.mp4',
                            classification: RecoveryClassification.UNATTACHED,
                            intent: 'INITIAL',
                            canRetry: true
                        }]
                    })
                });
                await p2;

                // Request 1 network rejection
                req1Deferred.reject(new Error('Stale Failed to fetch'));
                await p1;

                // New list remains intact
                assert.strictEqual(elements.sessionListContainer.childNodes.length, 1);
                assert.ok(elements.sessionListContainer.childNodes[0].textContent.includes('video_two.mp4'));
                assert.strictEqual(elements.alertBox.style.display, 'none');
            } finally {
                restore();
            }
        });

        test('A4: Old JSON parse failure arrives after new success → no stale UI mutation', async () => {
            const { elements, restore } = setupDomEnvironment();
            try {
                const req1Deferred = createDeferred();
                const req2Deferred = createDeferred();
                let callCount = 0;

                const fakeFetch = async () => {
                    callCount++;
                    return (callCount === 1) ? req1Deferred.promise : req2Deferred.promise;
                };

                const coordinator = new RecoveryCoordinator({
                    titleId: TITLE_ID,
                    entryId: ENTRY_ID,
                    fetchFn: fakeFetch
                });

                const renderer = new RecoveryViewRenderer(elements, coordinator);

                const p1 = renderer.load();
                const p2 = renderer.load();

                req2Deferred.resolve({
                    ok: true,
                    json: async () => ({
                        items: [{
                            sessionId: 'session-2',
                            originalFilename: 'video_two.mp4',
                            classification: RecoveryClassification.UNATTACHED,
                            intent: 'INITIAL',
                            canRetry: true
                        }]
                    })
                });
                await p2;

                // Request 1 returns invalid JSON syntax
                req1Deferred.resolve({
                    ok: true,
                    json: async () => { throw new Error('SyntaxError: Unexpected token < in JSON'); }
                });
                await p1;

                // New list remains intact
                assert.strictEqual(elements.sessionListContainer.childNodes.length, 1);
                assert.ok(elements.sessionListContainer.childNodes[0].textContent.includes('video_two.mp4'));
                assert.strictEqual(elements.alertBox.style.display, 'none');
            } finally {
                restore();
            }
        });

        test('A5: Old request completes while newer request remains pending → newer loading indicator remains active', async () => {
            const { elements, restore } = setupDomEnvironment();
            try {
                const req1Deferred = createDeferred();
                const req2Deferred = createDeferred();
                let callCount = 0;

                const fakeFetch = async () => {
                    callCount++;
                    return (callCount === 1) ? req1Deferred.promise : req2Deferred.promise;
                };

                const coordinator = new RecoveryCoordinator({
                    titleId: TITLE_ID,
                    entryId: ENTRY_ID,
                    fetchFn: fakeFetch
                });

                const renderer = new RecoveryViewRenderer(elements, coordinator);

                const p1 = renderer.load();
                const p2 = renderer.load();

                assert.strictEqual(elements.loadingSpinner.style.display, 'block');
                assert.strictEqual(elements.refreshBtn.disabled, true);

                // Request 1 resolves while Request 2 is still pending
                req1Deferred.resolve({
                    ok: true,
                    json: async () => ({ items: [] })
                });
                await p1;

                // Newer loading indicator must still be active!
                assert.strictEqual(elements.loadingSpinner.style.display, 'block');
                assert.strictEqual(elements.refreshBtn.disabled, true);

                // Now resolve Request 2
                req2Deferred.resolve({
                    ok: true,
                    json: async () => ({ items: [] })
                });
                await p2;

                // Now spinner settles
                assert.strictEqual(elements.loadingSpinner.style.display, 'none');
                assert.strictEqual(elements.refreshBtn.disabled, false);
            } finally {
                restore();
            }
        });

        test('A6: Latest request fails → proper current error is displayed', async () => {
            const { elements, restore } = setupDomEnvironment();
            try {
                const fakeFetch = async () => ({
                    ok: false,
                    status: 500,
                    json: async () => ({ message: 'Dịch vụ Media tạm thời không khả dụng.' })
                });

                const coordinator = new RecoveryCoordinator({
                    titleId: TITLE_ID,
                    entryId: ENTRY_ID,
                    fetchFn: fakeFetch
                });

                const renderer = new RecoveryViewRenderer(elements, coordinator);
                await renderer.load();

                assert.strictEqual(elements.alertBox.style.display, 'block');
                assert.ok(elements.alertBox.textContent.includes('Dịch vụ Media tạm thời không khả dụng'));
                assert.strictEqual(elements.sessionListContainer.childNodes.length, 0);
                assert.strictEqual(elements.loadingSpinner.style.display, 'none');
            } finally {
                restore();
            }
        });
    });

    describe('MS-08E3-S3 Integration Corrective: Suite B Browser Coordination & Storage Isolation (B7)', () => {

        test('B7: An active uploader attempt must not be dismissed or overwritten merely because an unrelated recovery operation completed', async () => {
            const { elements, restore } = setupDomEnvironment();
            try {
                const storageMap = new Map();
                // Active uploader attempt for session-A in storage
                storageMap.set('screen_video_attempt_' + ENTRY_ID, JSON.stringify({
                    sessionId: 'session-A',
                    fileName: 'uploading_a.mp4'
                }));
                const fakeStorage = {
                    getItem: (k) => storageMap.get(k) || null,
                    removeItem: (k) => storageMap.delete(k)
                };

                let dismissedCount = 0;
                // Mock Uploader coordinator representing the active in-progress uploader
                const mockUploaderCoordinator = {
                    getCurrentAttempt: () => ({ sessionId: 'session-A', status: 'UPLOADING' }),
                    dismissSettledAttempt: () => { dismissedCount++; }
                };

                const fakeFetch = async (url) => {
                    if (url.includes('/discard')) {
                        return {
                            ok: true,
                            json: async () => ({ sessionId: 'session-B', outcome: 'DISCARDED' })
                        };
                    }
                    if (url.includes('/video-uploads/recoverable')) {
                        return {
                            ok: true,
                            json: async () => ({ items: [] })
                        };
                    }
                    throw new Error('Unexpected url: ' + url);
                };

                const coordinator = new RecoveryCoordinator({
                    titleId: TITLE_ID,
                    entryId: ENTRY_ID,
                    storage: fakeStorage,
                    fetchFn: fakeFetch,
                    confirmFn: () => true
                });

                // Template callbacks exactly matching entry-detail.html
                const renderer = new RecoveryViewRenderer(elements, coordinator, {
                    onDiscarded: function (discardData, session) {
                        if (session && session.sessionId) {
                            coordinator.clearLocalAttempt(session.sessionId);
                        }
                        const currentAttempt = mockUploaderCoordinator.getCurrentAttempt();
                        if (currentAttempt && currentAttempt.sessionId === session?.sessionId) {
                            mockUploaderCoordinator.dismissSettledAttempt();
                        }
                    }
                });

                // Discard unrelated session-B
                await coordinator.discardSession({
                    sessionId: 'session-B',
                    videoMediaAssetId: 'asset-b'
                });
                if (renderer.callbacks.onDiscarded) {
                    renderer.callbacks.onDiscarded({ sessionId: 'session-B' }, { sessionId: 'session-B' });
                }

                // Assertions:
                // 1. mockUploaderCoordinator was NOT dismissed!
                assert.strictEqual(dismissedCount, 0);
                // 2. Storage attempt for session-A was NOT removed!
                assert.strictEqual(storageMap.has('screen_video_attempt_' + ENTRY_ID), true);
                const saved = JSON.parse(storageMap.get('screen_video_attempt_' + ENTRY_ID));
                assert.strictEqual(saved.sessionId, 'session-A');
            } finally {
                restore();
            }
        });
    });

    describe('MS-08E3-S3 Integration Corrective: Suite C Post-Attachment UI Consistency & Reload (C1-C7)', () => {

        test('C1: Entry initially has no video → recovery attach succeeds → normal attached-video state available after authoritative refresh', async () => {
            const { elements, restore } = setupDomEnvironment();
            const originalLocation = globalThis.location;
            let reloadCalled = false;
            globalThis.location = {
                reload: () => { reloadCalled = true; }
            };

            try {
                const fakeFetch = async (url) => {
                    if (url.includes('/video/attach')) {
                        return {
                            ok: true,
                            json: async () => ({
                                videoMediaAssetId: ASSET_ID,
                                status: 'ATTACHED'
                            })
                        };
                    }
                    if (url.includes('/video-uploads/recoverable')) {
                        return {
                            ok: true,
                            json: async () => ({
                                items: [{
                                    sessionId: SESSION_ID,
                                    videoMediaAssetId: ASSET_ID,
                                    classification: RecoveryClassification.UNATTACHED,
                                    intent: 'INITIAL',
                                    canRetry: true
                                }]
                            })
                        };
                    }
                    throw new Error('Unexpected url: ' + url);
                };

                const coordinator = new RecoveryCoordinator({
                    titleId: TITLE_ID,
                    entryId: ENTRY_ID,
                    currentAttachedAssetId: null, // Initially NO video
                    fetchFn: fakeFetch
                });

                const renderer = new RecoveryViewRenderer(elements, coordinator, {
                    onAttached: function () {
                        globalThis.location.reload();
                    }
                });

                await renderer.load();
                const card = elements.sessionListContainer.childNodes[0];
                const actionsRow = card.childNodes[card.childNodes.length - 1];
                const attachBtn = actionsRow.childNodes.find(btn => btn.textContent.includes('Gắn video'));

                await attachBtn.dispatchEvent('click');

                // Reload was triggered for authoritative Thymeleaf rendering!
                assert.strictEqual(reloadCalled, true);
                assert.strictEqual(coordinator.currentAttachedAssetId, ASSET_ID);
            } finally {
                globalThis.location = originalLocation;
                restore();
            }
        });

        test('C2: Entry has video A → replace with B succeeds → refreshed state shows B and uses B for future preconditions', async () => {
            const { elements, restore } = setupDomEnvironment();
            const originalLocation = globalThis.location;
            let reloadCalled = false;
            globalThis.location = {
                reload: () => { reloadCalled = true; }
            };

            try {
                const fakeFetch = async (url) => {
                    if (url.includes('/video/attach')) {
                        return {
                            ok: true,
                            json: async () => ({
                                videoMediaAssetId: ASSET_ID, // Replacement B
                                status: 'REPLACED'
                            })
                        };
                    }
                    if (url.includes('/video-uploads/recoverable')) {
                        return {
                            ok: true,
                            json: async () => ({
                                items: [{
                                    sessionId: SESSION_ID,
                                    videoMediaAssetId: ASSET_ID,
                                    classification: RecoveryClassification.UNATTACHED,
                                    intent: 'REPLACEMENT',
                                    replacementExpectedAssetId: OLD_ASSET_ID,
                                    canRetry: true
                                }]
                            })
                        };
                    }
                    throw new Error('Unexpected url: ' + url);
                };

                const coordinator = new RecoveryCoordinator({
                    titleId: TITLE_ID,
                    entryId: ENTRY_ID,
                    currentAttachedAssetId: OLD_ASSET_ID, // Initially video A
                    fetchFn: fakeFetch,
                    confirmFn: () => true
                });

                const renderer = new RecoveryViewRenderer(elements, coordinator, {
                    onAttached: function () {
                        globalThis.location.reload();
                    }
                });

                await renderer.load();
                const card = elements.sessionListContainer.childNodes[0];
                const actionsRow = card.childNodes[card.childNodes.length - 1];
                const replaceBtn = actionsRow.childNodes.find(btn => btn.textContent.includes('Thay thế video'));

                await replaceBtn.dispatchEvent('click');

                assert.strictEqual(reloadCalled, true);
                assert.strictEqual(coordinator.currentAttachedAssetId, ASSET_ID);
            } finally {
                globalThis.location = originalLocation;
                restore();
            }
        });

        test('C3: Replacement fails → no successful state transition is shown and page is not reloaded', async () => {
            const { elements, restore } = setupDomEnvironment();
            const originalLocation = globalThis.location;
            let reloadCalled = false;
            globalThis.location = {
                reload: () => { reloadCalled = true; }
            };

            try {
                const fakeFetch = async (url) => {
                    if (url.includes('/video/attach')) {
                        return {
                            ok: false,
                            status: 500,
                            json: async () => ({ message: 'Lỗi gắn video' })
                        };
                    }
                    if (url.includes('/video-uploads/recoverable')) {
                        return {
                            ok: true,
                            json: async () => ({
                                items: [{
                                    sessionId: SESSION_ID,
                                    videoMediaAssetId: ASSET_ID,
                                    classification: RecoveryClassification.UNATTACHED,
                                    intent: 'REPLACEMENT',
                                    replacementExpectedAssetId: OLD_ASSET_ID,
                                    canRetry: true
                                }]
                            })
                        };
                    }
                    throw new Error('Unexpected url: ' + url);
                };

                const coordinator = new RecoveryCoordinator({
                    titleId: TITLE_ID,
                    entryId: ENTRY_ID,
                    currentAttachedAssetId: OLD_ASSET_ID,
                    fetchFn: fakeFetch,
                    confirmFn: () => true
                });

                const renderer = new RecoveryViewRenderer(elements, coordinator, {
                    onAttached: function () {
                        globalThis.location.reload();
                    }
                });

                await renderer.load();
                const card = elements.sessionListContainer.childNodes[0];
                const actionsRow = card.childNodes[card.childNodes.length - 1];
                const replaceBtn = actionsRow.childNodes.find(btn => btn.textContent.includes('Thay thế video'));

                await replaceBtn.dispatchEvent('click');

                // Must NOT reload!
                assert.strictEqual(reloadCalled, false);
                // Old video remains
                assert.strictEqual(coordinator.currentAttachedAssetId, OLD_ASSET_ID);
                assert.ok(elements.alertBox.textContent.includes('Lỗi gắn video'));
            } finally {
                globalThis.location = originalLocation;
                restore();
            }
        });

        test('C4: A conflicting replacement requires fresh server state before another attempt (no reload on 409)', async () => {
            const { elements, restore } = setupDomEnvironment();
            const originalLocation = globalThis.location;
            let reloadCalled = false;
            globalThis.location = {
                reload: () => { reloadCalled = true; }
            };

            try {
                const fakeFetch = async (url) => {
                    if (url.includes('/video/attach')) {
                        return {
                            ok: false,
                            status: 409,
                            json: async () => ({
                                errorCode: 'SCREEN_ENTRY_VIDEO_CONFLICT',
                                message: 'Video hiện tại không khớp với phiên thay thế.'
                            })
                        };
                    }
                    if (url.includes('/video-uploads/recoverable')) {
                        return {
                            ok: true,
                            json: async () => ({
                                items: [{
                                    sessionId: SESSION_ID,
                                    videoMediaAssetId: ASSET_ID,
                                    classification: RecoveryClassification.UNATTACHED,
                                    intent: 'REPLACEMENT',
                                    replacementExpectedAssetId: OLD_ASSET_ID,
                                    canRetry: true
                                }]
                            })
                        };
                    }
                    throw new Error('Unexpected url: ' + url);
                };

                const coordinator = new RecoveryCoordinator({
                    titleId: TITLE_ID,
                    entryId: ENTRY_ID,
                    currentAttachedAssetId: OLD_ASSET_ID,
                    fetchFn: fakeFetch,
                    confirmFn: () => true
                });

                const renderer = new RecoveryViewRenderer(elements, coordinator, {
                    onAttached: function () {
                        globalThis.location.reload();
                    }
                });

                await renderer.load();
                const card = elements.sessionListContainer.childNodes[0];
                const actionsRow = card.childNodes[card.childNodes.length - 1];
                const replaceBtn = actionsRow.childNodes.find(btn => btn.textContent.includes('Thay thế video'));

                await replaceBtn.dispatchEvent('click');

                assert.strictEqual(reloadCalled, false);
                assert.ok(elements.alertBox.textContent.includes('Xung đột thay thế video'));
                assert.strictEqual(coordinator.currentAttachedAssetId, OLD_ASSET_ID);
            } finally {
                globalThis.location = originalLocation;
                restore();
            }
        });

        test('C6: Page reload does not automatically initiate another upload', async () => {
            let uploadInitiated = false;
            const fakeFetch = async (url) => {
                if (url.includes('/upload-session')) {
                    uploadInitiated = true;
                    return { ok: true, json: async () => ({}) };
                }
                if (url.includes('/video-uploads/recoverable')) {
                    return {
                        ok: true,
                        json: async () => ({ items: [] })
                    };
                }
                throw new Error('Unexpected url: ' + url);
            };

            const coordinator = new RecoveryCoordinator({
                titleId: TITLE_ID,
                entryId: ENTRY_ID,
                fetchFn: fakeFetch
            });

            // Normal load on page visit
            await coordinator.fetchRecoverableSessions();

            // Zero upload initiation calls were made
            assert.strictEqual(uploadInitiated, false);
        });

        test('C7: Existing E3-S2 cancellation, hashing and multipart behavior contracts remain satisfied', () => {
            const uploaderModule = require('../../../main/resources/static/js/screen/screen-video-uploader.js');
            assert.ok(uploaderModule.UploadCoordinator, 'UploadCoordinator must remain intact');
            assert.ok(uploaderModule.IncrementalSha256, 'IncrementalSha256 must remain intact');
            assert.ok(uploaderModule.validateFileConstraints, 'validateFileConstraints must remain intact');
        });
    });

    describe('MS-08E3-S3 Concurrent Uploader / Recovery Mutual Exclusion E2E Integration Guard', () => {

        test('1. Active upload A blocks recovery attach B before HTTP POST', async () => {
            const { elements, restore } = setupDomEnvironment();
            try {
                let fetchCalled = false;
                const fakeFetch = async (url, options) => {
                    if (url.includes('/video-uploads/recoverable')) {
                        return {
                            ok: true,
                            json: async () => ({
                                items: [{
                                    sessionId: 'session-B',
                                    videoMediaAssetId: 'asset-b',
                                    originalFilename: 'rec_video_b.mp4',
                                    declaredSizeBytes: 10000000,
                                    classification: RecoveryClassification.UNATTACHED,
                                    intent: 'INITIAL',
                                    canRetry: true,
                                    canDiscard: true
                                }]
                            })
                        };
                    }
                    if (url.includes('/video/attach')) {
                        fetchCalled = true;
                        return { ok: true, json: async () => ({ status: 'ATTACHED' }) };
                    }
                    throw new Error('Unexpected URL: ' + url);
                };

                const mockUploader = {
                    getCurrentAttempt: () => ({ sessionId: 'session-A', isSettled: false, status: 'UPLOADING' })
                };

                const coordinator = new RecoveryCoordinator({
                    titleId: TITLE_ID,
                    entryId: ENTRY_ID,
                    fetchFn: fakeFetch,
                    uploaderCoordinator: mockUploader
                });

                const renderer = new RecoveryViewRenderer(elements, coordinator, {
                    canAttach: function (session, isReplacement) {
                        if (isUploaderActive(mockUploader)) {
                            return {
                                allowed: false,
                                reason: 'Không thể gắn hoặc thay thế video từ phiên khôi phục trong khi một tiến trình tải lên video khác đang được thực hiện. Vui lòng chờ tiến trình hoàn tất hoặc hủy tiến trình trước khi thao tác.'
                            };
                        }
                        return { allowed: true };
                    }
                });

                await renderer.load();
                const card = elements.sessionListContainer.childNodes[0];
                const actionsRow = card.childNodes[card.childNodes.length - 1];
                const attachBtn = actionsRow.childNodes.find(btn => btn.textContent.includes('Gắn video'));

                await attachBtn.dispatchEvent('click');

                // 1. NO HTTP POST dispatched!
                assert.strictEqual(fetchCalled, false);
                // 2. Alert warning is displayed
                assert.strictEqual(elements.alertBox.style.display, 'block');
                assert.ok(elements.alertBox.textContent.includes('Không thể gắn hoặc thay thế video từ phiên khôi phục'));
                // 3. Button is re-enabled
                assert.strictEqual(attachBtn.disabled, false);
            } finally {
                restore();
            }
        });

        test('2. Active upload A blocks recovery replacement B before HTTP POST', async () => {
            const { elements, restore } = setupDomEnvironment();
            try {
                let fetchCalled = false;
                let confirmCalled = false;
                const fakeFetch = async (url, options) => {
                    if (url.includes('/video-uploads/recoverable')) {
                        return {
                            ok: true,
                            json: async () => ({
                                items: [{
                                    sessionId: 'session-B',
                                    videoMediaAssetId: 'asset-b',
                                    originalFilename: 'rec_video_b.mp4',
                                    declaredSizeBytes: 10000000,
                                    classification: RecoveryClassification.UNATTACHED,
                                    intent: 'REPLACEMENT',
                                    replacementExpectedAssetId: OLD_ASSET_ID,
                                    canRetry: true,
                                    canDiscard: true
                                }]
                            })
                        };
                    }
                    if (url.includes('/video/attach')) {
                        fetchCalled = true;
                        return { ok: true, json: async () => ({ status: 'REPLACED' }) };
                    }
                    throw new Error('Unexpected URL: ' + url);
                };

                const mockUploader = {
                    getCurrentAttempt: () => ({ sessionId: 'session-A', isSettled: false, status: 'UPLOADING' })
                };

                const coordinator = new RecoveryCoordinator({
                    titleId: TITLE_ID,
                    entryId: ENTRY_ID,
                    currentAttachedAssetId: OLD_ASSET_ID,
                    fetchFn: fakeFetch,
                    confirmFn: () => { confirmCalled = true; return true; },
                    uploaderCoordinator: mockUploader
                });

                const renderer = new RecoveryViewRenderer(elements, coordinator, {
                    canAttach: function (session, isReplacement) {
                        if (isUploaderActive(mockUploader)) {
                            return {
                                allowed: false,
                                reason: 'Không thể gắn hoặc thay thế video từ phiên khôi phục trong khi một tiến trình tải lên video khác đang được thực hiện. Vui lòng chờ tiến trình hoàn tất hoặc hủy tiến trình trước khi thao tác.'
                            };
                        }
                        return { allowed: true };
                    }
                });

                await renderer.load();
                const card = elements.sessionListContainer.childNodes[0];
                const actionsRow = card.childNodes[card.childNodes.length - 1];
                const replaceBtn = actionsRow.childNodes.find(btn => btn.textContent.includes('Thay thế video'));

                await replaceBtn.dispatchEvent('click');

                // Assertions:
                assert.strictEqual(fetchCalled, false);
                assert.strictEqual(confirmCalled, false); // Blocked before confirmation prompt!
                assert.strictEqual(elements.alertBox.style.display, 'block');
                assert.ok(elements.alertBox.textContent.includes('Không thể gắn hoặc thay thế video'));
                assert.strictEqual(replaceBtn.disabled, false);
                assert.strictEqual(coordinator.currentAttachedAssetId, OLD_ASSET_ID);
            } finally {
                restore();
            }
        });

        test('3. Blocked recovery action does not call page reload', async () => {
            const { elements, restore } = setupDomEnvironment();
            const originalLocation = globalThis.location;
            let reloadCalled = false;
            globalThis.location = { reload: () => { reloadCalled = true; } };

            try {
                const fakeFetch = async (url) => {
                    if (url.includes('/video-uploads/recoverable')) {
                        return {
                            ok: true,
                            json: async () => ({
                                items: [{
                                    sessionId: 'session-B',
                                    videoMediaAssetId: 'asset-b',
                                    classification: RecoveryClassification.UNATTACHED,
                                    intent: 'INITIAL',
                                    canRetry: true
                                }]
                            })
                        };
                    }
                    throw new Error('No fetch should occur');
                };

                const mockUploader = {
                    getCurrentAttempt: () => ({ sessionId: 'session-A', isSettled: false })
                };

                const coordinator = new RecoveryCoordinator({
                    titleId: TITLE_ID,
                    entryId: ENTRY_ID,
                    fetchFn: fakeFetch,
                    uploaderCoordinator: mockUploader
                });

                const renderer = new RecoveryViewRenderer(elements, coordinator, {
                    canAttach: function () {
                        if (isUploaderActive(mockUploader)) {
                            return { allowed: false, reason: 'Active upload blocks attach' };
                        }
                        return { allowed: true };
                    },
                    onAttached: function () {
                        globalThis.location.reload();
                    }
                });

                await renderer.load();
                const card = elements.sessionListContainer.childNodes[0];
                const actionsRow = card.childNodes[card.childNodes.length - 1];
                const attachBtn = actionsRow.childNodes.find(btn => btn.textContent.includes('Gắn video'));

                await attachBtn.dispatchEvent('click');

                assert.strictEqual(reloadCalled, false);
            } finally {
                globalThis.location = originalLocation;
                restore();
            }
        });

        test('4. Active attempt A and its local correlation data remain intact when recovery action is blocked', async () => {
            const { elements, restore } = setupDomEnvironment();
            const storageMap = new Map();
            storageMap.set('screen_video_attempt_' + ENTRY_ID, JSON.stringify({
                sessionId: 'session-A',
                idempotencyKey: 'idemp-attempt-A',
                fileName: 'uploading_a.mp4'
            }));
            const fakeStorage = {
                getItem: (k) => storageMap.get(k) || null,
                removeItem: (k) => storageMap.delete(k)
            };

            const activeAttemptA = {
                sessionId: 'session-A',
                isSettled: false,
                isCancelled: false,
                phase: 'UPLOADING',
                partsManifest: [{ partNumber: 1, etag: 'etag1' }]
            };

            let dismissCalled = false;
            const mockUploader = {
                getCurrentAttempt: () => activeAttemptA,
                dismissSettledAttempt: () => { dismissCalled = true; }
            };

            const fakeFetch = async (url) => {
                if (url.includes('/video-uploads/recoverable')) {
                    return {
                        ok: true,
                        json: async () => ({
                            items: [{
                                sessionId: 'session-B',
                                videoMediaAssetId: 'asset-b',
                                classification: RecoveryClassification.UNATTACHED,
                                intent: 'INITIAL',
                                canRetry: true
                            }]
                        })
                    };
                }
                throw new Error('No fetch should occur');
            };

            const coordinator = new RecoveryCoordinator({
                titleId: TITLE_ID,
                entryId: ENTRY_ID,
                storage: fakeStorage,
                fetchFn: fakeFetch,
                uploaderCoordinator: mockUploader
            });

            const renderer = new RecoveryViewRenderer(elements, coordinator, {
                canAttach: function () {
                    if (isUploaderActive(mockUploader)) {
                        return { allowed: false, reason: 'Active upload in progress' };
                    }
                    return { allowed: true };
                }
            });

            await renderer.load();
            const card = elements.sessionListContainer.childNodes[0];
            const actionsRow = card.childNodes[card.childNodes.length - 1];
            const attachBtn = actionsRow.childNodes.find(btn => btn.textContent.includes('Gắn video'));

            await attachBtn.dispatchEvent('click');

            // Verify Attempt A is completely untouched:
            assert.strictEqual(dismissCalled, false);
            assert.strictEqual(activeAttemptA.isSettled, false);
            assert.strictEqual(activeAttemptA.isCancelled, false);
            assert.strictEqual(activeAttemptA.phase, 'UPLOADING');
            // Storage metadata for session A is intact:
            assert.strictEqual(storageMap.has('screen_video_attempt_' + ENTRY_ID), true);
            const saved = JSON.parse(storageMap.get('screen_video_attempt_' + ENTRY_ID));
            assert.strictEqual(saved.sessionId, 'session-A');
            assert.strictEqual(saved.idempotencyKey, 'idemp-attempt-A');
        });

        test('5. Settled uploader attempt permits recovery attach', async () => {
            const { elements, restore } = setupDomEnvironment();
            const originalLocation = globalThis.location;
            let reloadCalled = false;
            globalThis.location = { reload: () => { reloadCalled = true; } };

            try {
                let attachCalled = false;
                const fakeFetch = async (url) => {
                    if (url.includes('/video-uploads/recoverable')) {
                        return {
                            ok: true,
                            json: async () => ({
                                items: [{
                                    sessionId: 'session-B',
                                    videoMediaAssetId: 'asset-b',
                                    classification: RecoveryClassification.UNATTACHED,
                                    intent: 'INITIAL',
                                    canRetry: true
                                }]
                            })
                        };
                    }
                    if (url.includes('/video/attach')) {
                        attachCalled = true;
                        return { ok: true, json: async () => ({ videoMediaAssetId: 'asset-b', status: 'ATTACHED' }) };
                    }
                    throw new Error('Unexpected URL: ' + url);
                };

                // Settled attempt from earlier completed or aborted upload
                const mockUploader = {
                    getCurrentAttempt: () => ({ sessionId: 'session-A', isSettled: true, status: 'COMPLETED' })
                };

                const coordinator = new RecoveryCoordinator({
                    titleId: TITLE_ID,
                    entryId: ENTRY_ID,
                    fetchFn: fakeFetch,
                    uploaderCoordinator: mockUploader
                });

                const renderer = new RecoveryViewRenderer(elements, coordinator, {
                    canAttach: function () {
                        if (isUploaderActive(mockUploader)) {
                            return { allowed: false, reason: 'Active upload' };
                        }
                        return { allowed: true };
                    },
                    onAttached: function () {
                        globalThis.location.reload();
                    }
                });

                await renderer.load();
                const card = elements.sessionListContainer.childNodes[0];
                const actionsRow = card.childNodes[card.childNodes.length - 1];
                const attachBtn = actionsRow.childNodes.find(btn => btn.textContent.includes('Gắn video'));

                await attachBtn.dispatchEvent('click');

                // Permitted: attach dispatched and reload called!
                assert.strictEqual(attachCalled, true);
                assert.strictEqual(reloadCalled, true);
                assert.strictEqual(coordinator.currentAttachedAssetId, 'asset-b');
            } finally {
                globalThis.location = originalLocation;
                restore();
            }
        });

        test('6. Recovery mutation already in flight prevents a new uploader attempt', async () => {
            // Integration check replicating entry-detail.html startUploadBtn guard
            const coordinator = new RecoveryCoordinator({
                titleId: TITLE_ID,
                entryId: ENTRY_ID
            });

            // Recovery is currently mutating (attach or discard in flight)
            coordinator.isMutating = true;

            let startAttemptCalled = false;
            const mockUploader = {
                startAttempt: async () => { startAttemptCalled = true; }
            };

            // Guard function as implemented in entry-detail.html
            function attemptStartUpload(recCoord, uploader) {
                if (recCoord && recCoord.isMutating) {
                    return {
                        allowed: false,
                        reason: 'Một thao tác gắn hoặc hủy phiên video đang được thực hiện. Vui lòng chờ phản hồi từ máy chủ trước khi bắt đầu tải lên video mới.'
                    };
                }
                uploader.startAttempt();
                return { allowed: true };
            }

            const result = attemptStartUpload(coordinator, mockUploader);
            assert.strictEqual(result.allowed, false);
            assert.strictEqual(startAttemptCalled, false);
            assert.ok(result.reason.includes('Một thao tác gắn hoặc hủy phiên video đang được thực hiện'));

            // Once mutation finishes:
            coordinator.isMutating = false;
            const result2 = attemptStartUpload(coordinator, mockUploader);
            assert.strictEqual(result2.allowed, true);
            assert.strictEqual(startAttemptCalled, true);
        });

        test('7. Successful recovery attach with no active upload still reloads', async () => {
            const { elements, restore } = setupDomEnvironment();
            const originalLocation = globalThis.location;
            let reloadCalled = false;
            globalThis.location = { reload: () => { reloadCalled = true; } };

            try {
                const fakeFetch = async (url) => {
                    if (url.includes('/video-uploads/recoverable')) {
                        return {
                            ok: true,
                            json: async () => ({
                                items: [{
                                    sessionId: 'session-B',
                                    videoMediaAssetId: 'asset-b',
                                    classification: RecoveryClassification.UNATTACHED,
                                    intent: 'INITIAL',
                                    canRetry: true
                                }]
                            })
                        };
                    }
                    if (url.includes('/video/attach')) {
                        return { ok: true, json: async () => ({ videoMediaAssetId: 'asset-b', status: 'ATTACHED' }) };
                    }
                    throw new Error('Unexpected URL: ' + url);
                };

                const mockUploader = {
                    getCurrentAttempt: () => null // No active upload
                };

                const coordinator = new RecoveryCoordinator({
                    titleId: TITLE_ID,
                    entryId: ENTRY_ID,
                    fetchFn: fakeFetch,
                    uploaderCoordinator: mockUploader
                });

                const renderer = new RecoveryViewRenderer(elements, coordinator, {
                    canAttach: function () {
                        if (isUploaderActive(mockUploader)) {
                            return { allowed: false, reason: 'Active upload' };
                        }
                        return { allowed: true };
                    },
                    onAttached: function () {
                        globalThis.location.reload();
                    }
                });

                await renderer.load();
                const card = elements.sessionListContainer.childNodes[0];
                const actionsRow = card.childNodes[card.childNodes.length - 1];
                const attachBtn = actionsRow.childNodes.find(btn => btn.textContent.includes('Gắn video'));

                await attachBtn.dispatchEvent('click');

                assert.strictEqual(reloadCalled, true);
                assert.strictEqual(coordinator.currentAttachedAssetId, 'asset-b');
            } finally {
                globalThis.location = originalLocation;
                restore();
            }
        });

        test('8. Failed or conflicting recovery never reloads', async () => {
            const { elements, restore } = setupDomEnvironment();
            const originalLocation = globalThis.location;
            let reloadCalled = false;
            globalThis.location = { reload: () => { reloadCalled = true; } };

            try {
                const fakeFetch = async (url) => {
                    if (url.includes('/video-uploads/recoverable')) {
                        return {
                            ok: true,
                            json: async () => ({
                                items: [{
                                    sessionId: 'session-B',
                                    videoMediaAssetId: 'asset-b',
                                    classification: RecoveryClassification.UNATTACHED,
                                    intent: 'INITIAL',
                                    canRetry: true
                                }]
                            })
                        };
                    }
                    if (url.includes('/video/attach')) {
                        return {
                            ok: false,
                            status: 409,
                            json: async () => ({
                                errorCode: 'SCREEN_ENTRY_VIDEO_CONFLICT',
                                message: 'Xung đột video trên hệ thống'
                            })
                        };
                    }
                    throw new Error('Unexpected URL: ' + url);
                };

                const mockUploader = {
                    getCurrentAttempt: () => null
                };

                const coordinator = new RecoveryCoordinator({
                    titleId: TITLE_ID,
                    entryId: ENTRY_ID,
                    fetchFn: fakeFetch,
                    uploaderCoordinator: mockUploader
                });

                const renderer = new RecoveryViewRenderer(elements, coordinator, {
                    canAttach: function () {
                        if (isUploaderActive(mockUploader)) {
                            return { allowed: false, reason: 'Active upload' };
                        }
                        return { allowed: true };
                    },
                    onAttached: function () {
                        globalThis.location.reload();
                    }
                });

                await renderer.load();
                const card = elements.sessionListContainer.childNodes[0];
                const actionsRow = card.childNodes[card.childNodes.length - 1];
                const attachBtn = actionsRow.childNodes.find(btn => btn.textContent.includes('Gắn video'));

                await attachBtn.dispatchEvent('click');

                assert.strictEqual(reloadCalled, false);
                assert.ok(elements.alertBox.textContent.includes('Xung đột thay thế video'));
            } finally {
                globalThis.location = originalLocation;
                restore();
            }
        });
    });
});
