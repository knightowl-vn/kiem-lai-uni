const { test, describe, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert');
const draftsAdapter = require('../../../main/resources/static/js/novel/reader-comment-drafts.js');

describe('Novel Comment Draft Adapter (UX-DRAFT-01D1)', () => {

    afterEach(() => {
        draftsAdapter.destroyPagehideBridge();
    });

    describe('Canonical Key Schemas', () => {
        test('1. Bottom chapter root key matches schema', () => {
            const key = draftsAdapter.getChapterRootDraftKey('ch-uuid-123');
            assert.strictEqual(key, 'kiemlai:draft:novel-comment:ch-uuid-123:root');
        });

        test('2. Bottom chapter reply key matches schema', () => {
            const key = draftsAdapter.getChapterReplyDraftKey('ch-uuid-123', 'comment-uuid-456');
            assert.strictEqual(key, 'kiemlai:draft:novel-comment:ch-uuid-123:reply:comment-uuid-456');
        });

        test('3. Bottom chapter edit key matches schema', () => {
            const key = draftsAdapter.getChapterEditDraftKey('ch-uuid-123', 'comment-uuid-789');
            assert.strictEqual(key, 'kiemlai:draft:novel-comment:ch-uuid-123:edit:comment-uuid-789');
        });

        test('4. Bottom chapter active inline marker key matches schema', () => {
            const key = draftsAdapter.getChapterActiveInlineMarkerKey('ch-uuid-123');
            assert.strictEqual(key, 'kiemlai:draft:novel-comment:ch-uuid-123:active-inline');
        });

        test('5. Block drawer root key matches schema', () => {
            const key = draftsAdapter.getBlockRootDraftKey('ch-uuid-123', 'blk-abc-1');
            assert.strictEqual(key, 'kiemlai:draft:novel-comment:ch-uuid-123:block:blk-abc-1:root');
        });

        test('6. Block drawer reply key matches schema', () => {
            const key = draftsAdapter.getBlockReplyDraftKey('ch-uuid-123', 'blk-abc-1', 'reply-uuid-111');
            assert.strictEqual(key, 'kiemlai:draft:novel-comment:ch-uuid-123:block:blk-abc-1:reply:reply-uuid-111');
        });

        test('7. Block drawer edit key matches schema', () => {
            const key = draftsAdapter.getBlockEditDraftKey('ch-uuid-123', 'blk-abc-1', 'edit-uuid-222');
            assert.strictEqual(key, 'kiemlai:draft:novel-comment:ch-uuid-123:block:blk-abc-1:edit:edit-uuid-222');
        });

        test('8. Block drawer active marker key is chapter-scoped and matches schema', () => {
            const key = draftsAdapter.getBlockActiveMarkerKey('ch-uuid-123');
            assert.strictEqual(key, 'kiemlai:draft:novel-comment:ch-uuid-123:active-block');
        });
    });

    describe('Strict Nonblank-String Validation and URL Encoding', () => {
        test('Dynamic components are properly URL-encoded', () => {
            const key1 = draftsAdapter.getChapterRootDraftKey('ch/1?a=b&c=d');
            assert.strictEqual(key1, 'kiemlai:draft:novel-comment:ch%2F1%3Fa%3Db%26c%3Dd:root');

            const key2 = draftsAdapter.getBlockRootDraftKey('ch 1', 'blk/part:1');
            assert.strictEqual(key2, 'kiemlai:draft:novel-comment:ch%201:block:blk%2Fpart%3A1:root');

            const key3 = draftsAdapter.getChapterReplyDraftKey('ch 1', 'target/id#1');
            assert.strictEqual(key3, 'kiemlai:draft:novel-comment:ch%201:reply:target%2Fid%231');
        });

        test('Whitespace trimming is applied to valid strings', () => {
            const key = draftsAdapter.getChapterRootDraftKey('   ch-trim-me   ');
            assert.strictEqual(key, 'kiemlai:draft:novel-comment:ch-trim-me:root');
        });

        test('Empty and whitespace-only strings return null', () => {
            assert.strictEqual(draftsAdapter.getChapterRootDraftKey(''), null);
            assert.strictEqual(draftsAdapter.getChapterRootDraftKey('   '), null);
            assert.strictEqual(draftsAdapter.getChapterReplyDraftKey('ch-1', ''), null);
            assert.strictEqual(draftsAdapter.getChapterReplyDraftKey('', 'reply-1'), null);
            assert.strictEqual(draftsAdapter.getChapterReplyDraftKey('   ', '   '), null);
            assert.strictEqual(draftsAdapter.getChapterEditDraftKey('ch-1', '  '), null);
            assert.strictEqual(draftsAdapter.getChapterActiveInlineMarkerKey(''), null);
            assert.strictEqual(draftsAdapter.getBlockRootDraftKey('ch-1', ''), null);
            assert.strictEqual(draftsAdapter.getBlockReplyDraftKey('ch-1', 'blk-1', ''), null);
            assert.strictEqual(draftsAdapter.getBlockEditDraftKey('ch-1', '', 'edit-1'), null);
            assert.strictEqual(draftsAdapter.getBlockActiveMarkerKey('   '), null);
        });

        test('Null and undefined return null', () => {
            assert.strictEqual(draftsAdapter.getChapterRootDraftKey(null), null);
            assert.strictEqual(draftsAdapter.getChapterRootDraftKey(undefined), null);
            assert.strictEqual(draftsAdapter.getChapterReplyDraftKey(null, 'reply-1'), null);
            assert.strictEqual(draftsAdapter.getChapterReplyDraftKey('ch-1', undefined), null);
            assert.strictEqual(draftsAdapter.getBlockActiveMarkerKey(null), null);
        });

        test('Non-string types are strictly rejected without String() coercion', () => {
            assert.strictEqual(draftsAdapter.getChapterRootDraftKey(12345), null);
            assert.strictEqual(draftsAdapter.getChapterRootDraftKey(true), null);
            assert.strictEqual(draftsAdapter.getChapterRootDraftKey({ id: 'ch-1' }), null);
            assert.strictEqual(draftsAdapter.getChapterRootDraftKey(['ch-1']), null);
            assert.strictEqual(draftsAdapter.getChapterReplyDraftKey('ch-1', 456), null);
            assert.strictEqual(draftsAdapter.getBlockRootDraftKey('ch-1', 789), null);
        });
    });

    describe('EphemeralDraftStore Resolution', () => {
        test('Returns explicitly injected draft store if provided', () => {
            const mockStore = { save: () => {}, load: () => {}, remove: () => {} };
            const resolved = draftsAdapter.resolveDraftStore(mockStore);
            assert.strictEqual(resolved, mockStore);
        });

        test('Resolves CommonJS EphemeralDraftStore when no injected store is passed in Node', () => {
            const resolved = draftsAdapter.resolveDraftStore();
            assert.ok(resolved);
            assert.strictEqual(typeof resolved.createStore, 'function');
        });
    });

    describe('Pagehide Flush Bridge', () => {
        function createMockWindowAndDocument() {
            const listeners = {
                window: {},
                document: {}
            };

            const mockWindow = {
                addEventListener(event, fn) {
                    listeners.window[event] = listeners.window[event] || [];
                    listeners.window[event].push(fn);
                },
                removeEventListener(event, fn) {
                    if (listeners.window[event]) {
                        listeners.window[event] = listeners.window[event].filter(cb => cb !== fn);
                    }
                },
                dispatchEvent(event) {
                    const type = event.type || event;
                    if (listeners.window[type]) {
                        listeners.window[type].forEach(fn => fn(event));
                    }
                }
            };

            const mockDocument = {
                defaultView: mockWindow,
                addEventListener(event, fn) {
                    listeners.document[event] = listeners.document[event] || [];
                    listeners.document[event].push(fn);
                },
                removeEventListener(event, fn) {
                    if (listeners.document[event]) {
                        listeners.document[event] = listeners.document[event].filter(cb => cb !== fn);
                    }
                },
                dispatchEvent(event) {
                    const type = event.type || event;
                    if (listeners.document[type]) {
                        listeners.document[type].forEach(fn => fn(event));
                    }
                }
            };

            return { mockWindow, mockDocument, listeners };
        }

        test('Pagehide on window dispatches EVENT_FLUSH_DRAFTS on document', () => {
            const { mockWindow, mockDocument } = createMockWindowAndDocument();

            draftsAdapter.initPagehideBridge(mockDocument, mockWindow);
            assert.strictEqual(draftsAdapter.isBridgeActive(), true);

            let flushEventReceived = 0;
            mockDocument.addEventListener(draftsAdapter.EVENT_FLUSH_DRAFTS, () => {
                flushEventReceived++;
            });

            mockWindow.dispatchEvent({ type: 'pagehide' });
            assert.strictEqual(flushEventReceived, 1, 'Expected exactly one flush event to be dispatched');
        });

        test('Repeated init on the same window/document does not duplicate listeners', () => {
            const { mockWindow, mockDocument, listeners } = createMockWindowAndDocument();

            draftsAdapter.initPagehideBridge(mockDocument, mockWindow);
            draftsAdapter.initPagehideBridge(mockDocument, mockWindow);
            draftsAdapter.initPagehideBridge(mockDocument, mockWindow);

            assert.strictEqual(listeners.window['pagehide'].length, 1, 'Listener should not be duplicated');

            let flushCount = 0;
            mockDocument.addEventListener(draftsAdapter.EVENT_FLUSH_DRAFTS, () => {
                flushCount++;
            });

            mockWindow.dispatchEvent({ type: 'pagehide' });
            assert.strictEqual(flushCount, 1);
        });

        test('Re-init on a new window/document detaches the previous window listener', () => {
            const env1 = createMockWindowAndDocument();
            const env2 = createMockWindowAndDocument();

            draftsAdapter.initPagehideBridge(env1.mockDocument, env1.mockWindow);
            assert.strictEqual(env1.listeners.window['pagehide'].length, 1);

            draftsAdapter.initPagehideBridge(env2.mockDocument, env2.mockWindow);
            assert.strictEqual(env1.listeners.window['pagehide'].length, 0, 'Old window listener must be removed');
            assert.strictEqual(env2.listeners.window['pagehide'].length, 1, 'New window listener must be attached');

            let env1FlushCount = 0;
            let env2FlushCount = 0;
            env1.mockDocument.addEventListener(draftsAdapter.EVENT_FLUSH_DRAFTS, () => { env1FlushCount++; });
            env2.mockDocument.addEventListener(draftsAdapter.EVENT_FLUSH_DRAFTS, () => { env2FlushCount++; });

            env1.mockWindow.dispatchEvent({ type: 'pagehide' });
            assert.strictEqual(env1FlushCount, 0, 'Old window should not dispatch flush');

            env2.mockWindow.dispatchEvent({ type: 'pagehide' });
            assert.strictEqual(env2FlushCount, 1, 'New window should dispatch flush');
        });

        test('Destroy detaches the window listener and resets active state', () => {
            const { mockWindow, mockDocument, listeners } = createMockWindowAndDocument();

            draftsAdapter.initPagehideBridge(mockDocument, mockWindow);
            assert.strictEqual(draftsAdapter.isBridgeActive(), true);

            draftsAdapter.destroyPagehideBridge();
            assert.strictEqual(draftsAdapter.isBridgeActive(), false);
            assert.strictEqual(listeners.window['pagehide'].length, 0);

            let flushCount = 0;
            mockDocument.addEventListener(draftsAdapter.EVENT_FLUSH_DRAFTS, () => { flushCount++; });

            mockWindow.dispatchEvent({ type: 'pagehide' });
            assert.strictEqual(flushCount, 0);
        });
    });
});
