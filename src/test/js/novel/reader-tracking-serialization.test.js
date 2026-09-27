const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

test('MS-05B4/B5 Reader Progress and History Promise Queue Serialization Tests', async (t) => {
    const progressJsPath = path.join(__dirname, '../../../main/resources/static/js/novel/reader-progress.js');
    const historyJsPath = path.join(__dirname, '../../../main/resources/static/js/novel/reader-history.js');

    const progressCode = fs.readFileSync(progressJsPath, 'utf8');
    const historyCode = fs.readFileSync(historyJsPath, 'utf8');

    function createFakeDocument(trackerId, initialChapterId) {
        const listeners = {};
        const tracker = {
            id: trackerId,
            dataset: {
                chapterId: initialChapterId,
                csrfToken: 'test-token',
                csrfHeader: 'X-CSRF-TOKEN'
            }
        };

        return {
            readyState: 'complete',
            tracker,
            listeners,
            addEventListener(event, handler) {
                if (!listeners[event]) listeners[event] = [];
                listeners[event].push(handler);
            },
            dispatchEvent(event) {
                const handlers = listeners[event] || [];
                for (const h of handlers) {
                    h();
                }
            },
            getElementById(id) {
                if (id === trackerId) {
                    return this.tracker;
                }
                return null;
            }
        };
    }

    await t.test('reader-progress: serializes requests and freezes DOM snapshot synchronously', async () => {
        const calls = [];
        let resolveFirstCall;
        const firstCallPromise = new Promise(resolve => { resolveFirstCall = resolve; });

        const doc = createFakeDocument('novelReadingProgressTracker', 'chapter-1111');

        const fakeFetch = async (url, opts) => {
            calls.push({ url, opts });
            if (calls.length === 1) {
                await firstCallPromise;
            }
            return { ok: true, status: 200 };
        };

        const sandbox = {
            document: doc,
            fetch: fakeFetch,
            console: { debug: () => {}, warn: () => {}, error: () => {} },
            setTimeout,
            clearTimeout,
            Promise,
            encodeURIComponent
        };

        // 1. Loading the script triggers initial record for chapter-1111 (since readyState === 'complete')
        vm.runInNewContext(progressCode, sandbox);

        // Allow microtask to start first fetch
        await Promise.resolve();
        assert.strictEqual(calls.length, 1);
        assert.strictEqual(calls[0].url, '/novel/chapters/chapter-1111/progress');

        // 2. While first fetch is blocked, DOM changes to chapter-2222 and chapter-changed event fires
        doc.tracker.dataset.chapterId = 'chapter-2222';
        doc.dispatchEvent('kiemlai:chapter-changed');

        // Allow microtask to attach second fetch to queue
        await Promise.resolve();

        // Still only 1 call should have started because queue awaits first call
        assert.strictEqual(calls.length, 1);

        // Resolve first call
        resolveFirstCall();

        // Allow microtask queue to process the serialized second promise
        await new Promise(r => setTimeout(r, 50));

        // Both calls should have executed in strict sequence with their frozen snapshots
        assert.strictEqual(calls.length, 2);
        assert.strictEqual(calls[0].url, '/novel/chapters/chapter-1111/progress');
        assert.strictEqual(calls[1].url, '/novel/chapters/chapter-2222/progress');
    });

    await t.test('reader-history: serializes requests and freezes DOM snapshot synchronously', async () => {
        const calls = [];
        let resolveFirstCall;
        const firstCallPromise = new Promise(resolve => { resolveFirstCall = resolve; });

        const doc = createFakeDocument('novelReadingHistoryTracker', 'chapter-aaaa');

        const fakeFetch = async (url, opts) => {
            calls.push({ url, opts });
            if (calls.length === 1) {
                await firstCallPromise;
            }
            return { ok: true, status: 200 };
        };

        const sandbox = {
            document: doc,
            fetch: fakeFetch,
            console: { debug: () => {}, warn: () => {}, error: () => {} },
            setTimeout,
            clearTimeout,
            Promise,
            encodeURIComponent
        };

        // 1. Loading script triggers initial record for chapter-aaaa
        vm.runInNewContext(historyCode, sandbox);

        // Allow microtask to start first fetch
        await Promise.resolve();
        assert.strictEqual(calls.length, 1);
        assert.strictEqual(calls[0].url, '/novel/chapters/chapter-aaaa/history');

        // 2. While first fetch is blocked, DOM changes to chapter-bbbb and event fires
        doc.tracker.dataset.chapterId = 'chapter-bbbb';
        doc.dispatchEvent('kiemlai:chapter-changed');

        // Allow microtask to attach second fetch to queue
        await Promise.resolve();

        // Still only 1 call should have started
        assert.strictEqual(calls.length, 1);

        resolveFirstCall();

        await new Promise(r => setTimeout(r, 50));

        assert.strictEqual(calls.length, 2);
        assert.strictEqual(calls[0].url, '/novel/chapters/chapter-aaaa/history');
        assert.strictEqual(calls[1].url, '/novel/chapters/chapter-bbbb/history');
    });

    await t.test('reader-progress & reader-history: anonymous user (no tracker element) causes no requests', async () => {
        let calls = 0;
        const emptyDoc = {
            readyState: 'complete',
            addEventListener: () => {},
            getElementById: () => null
        };
        const sandbox = {
            document: emptyDoc,
            fetch: async () => { calls++; return { ok: true }; },
            console: { debug: () => {} },
            Promise,
            encodeURIComponent
        };

        vm.runInNewContext(progressCode, sandbox);
        vm.runInNewContext(historyCode, sandbox);

        await new Promise(r => setTimeout(r, 20));
        assert.strictEqual(calls, 0, 'No fetch calls should be made when tracker element is missing');
    });
});
