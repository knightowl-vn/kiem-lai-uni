const { describe, test, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert');
const path = require('node:path');

const restoreModule = require('../../../main/resources/static/js/novel/reader-block-discussion-restore.js');
const EphemeralDraftStore = require('../../../main/resources/static/js/shared/ephemeral-draft-store.js');
const draftsAdapter = require('../../../main/resources/static/js/novel/reader-comment-drafts.js');

// Minimal Fake DOM setup for Node test environment
class FakeClassList {
    constructor(el) {
        this.el = el;
        this.classes = new Set();
    }
    add(...names) {
        names.forEach(n => this.classes.add(n));
        this.el.attributes['class'] = Array.from(this.classes).join(' ');
    }
    remove(...names) {
        names.forEach(n => this.classes.delete(n));
        this.el.attributes['class'] = Array.from(this.classes).join(' ');
    }
    contains(name) {
        return this.classes.has(name);
    }
}

class FakeElement {
    constructor(tagName, attributes = {}) {
        this.tagName = String(tagName).toUpperCase();
        this.attributes = {};
        this.childNodes = [];
        this.parentNode = null;
        this.parentElement = null;
        this.classList = new FakeClassList(this);
        this.listeners = {};
        this.style = {};
        this._textContent = '';
        this.scrolledIntoView = false;
        this.scrollOptions = null;

        for (const [k, v] of Object.entries(attributes)) {
            this.setAttribute(k, v);
        }
    }

    get className() {
        return this.getAttribute('class') || '';
    }

    set className(val) {
        this.setAttribute('class', val);
    }

    get textContent() {
        if (this.childNodes.length === 0) {
            return this._textContent;
        }
        return this.childNodes.map(c => c.textContent || '').join('');
    }

    set textContent(val) {
        this._textContent = String(val);
        this.childNodes = [];
    }

    scrollIntoView(options) {
        this.scrolledIntoView = true;
        this.scrollOptions = options;
    }

    appendChild(child) {
        child.parentNode = this;
        child.parentElement = this;
        this.childNodes.push(child);
        return child;
    }

    getAttribute(name) {
        return this.attributes[name] !== undefined ? this.attributes[name] : null;
    }

    setAttribute(name, value) {
        this.attributes[name] = String(value);
        if (name === 'class') {
            this.classList.classes.clear();
            String(value).trim().split(/\s+/).filter(Boolean).forEach(c => this.classList.classes.add(c));
        }
    }

    closest(selector) {
        let cur = this;
        while (cur) {
            if (matchesSingleSelector(cur, selector)) {
                return cur;
            }
            cur = cur.parentElement;
        }
        return null;
    }

    querySelector(selector) {
        return querySelectorAllDeep(this, selector)[0] || null;
    }

    querySelectorAll(selector) {
        return querySelectorAllDeep(this, selector);
    }
}

function matchesSingleSelector(el, sel) {
    if (!el || !el.tagName) return false;
    if (sel.startsWith('.')) {
        return el.classList.contains(sel.slice(1));
    }
    if (sel.startsWith('#')) {
        return el.getAttribute('id') === sel.slice(1);
    }
    if (sel.startsWith('[') && sel.endsWith(']')) {
        const raw = sel.slice(1, -1);
        const eqIdx = raw.indexOf('=');
        if (eqIdx === -1) {
            return el.getAttribute(raw) !== null;
        }
        const name = raw.slice(0, eqIdx);
        const val = raw.slice(eqIdx + 1).replace(/^["']|["']$/g, '');
        return el.getAttribute(name) === val;
    }
    return el.tagName.toLowerCase() === sel.toLowerCase();
}

function querySelectorAllDeep(root, selector) {
    const parts = selector.trim().split(/\s+/).filter(Boolean);
    if (parts.length === 0) return [];
    if (parts.length === 1) {
        const results = [];
        function traverse(node) {
            if (!node || !node.childNodes) return;
            for (const child of node.childNodes) {
                if (matchesSingleSelector(child, parts[0])) {
                    results.push(child);
                }
                traverse(child);
            }
        }
        traverse(root);
        return results;
    }

    let currentLevel = [root];
    for (const part of parts) {
        const nextLevel = [];
        for (const parent of currentLevel) {
            const found = querySelectorAllDeep(parent, part);
            for (const f of found) {
                if (!nextLevel.includes(f)) nextLevel.push(f);
            }
        }
        currentLevel = nextLevel;
    }
    return currentLevel;
}

class FakeDocument {
    constructor() {
        this.documentElement = new FakeElement('html');
        this.body = new FakeElement('body');
        this.documentElement.appendChild(this.body);
        this.listeners = {};
    }

    createElement(tagName) {
        const el = new FakeElement(tagName);
        el.ownerDocument = this;
        return el;
    }

    getElementById(id) {
        return querySelectorAllDeep(this.documentElement, '#' + id)[0] || null;
    }

    querySelector(selector) {
        return querySelectorAllDeep(this.documentElement, selector)[0] || null;
    }

    querySelectorAll(selector) {
        return querySelectorAllDeep(this.documentElement, selector);
    }

    addEventListener(event, fn) {
        if (!this.listeners[event]) {
            this.listeners[event] = [];
        }
        this.listeners[event].push(fn);
    }

    removeEventListener(event, fn) {
        if (!this.listeners[event]) return;
        const idx = this.listeners[event].indexOf(fn);
        if (idx !== -1) {
            this.listeners[event].splice(idx, 1);
        }
    }

    dispatchEvent(evt) {
        const handlers = this.listeners[evt.type] || [];
        for (const fn of [...handlers]) {
            fn.call(this, evt);
        }
        return true;
    }
}

function createFakeWindow(initialSearch = '', initialPath = '/novel/chapters/quyen-1-chuong-1', initialHash = '') {
    const historyEntries = [];
    return {
        location: {
            pathname: initialPath,
            search: initialSearch,
            hash: initialHash,
            origin: 'https://kiemlai.vn'
        },
        history: {
            state: null,
            replaceState: function (state, title, url) {
                this.state = state;
                historyEntries.push({ state, title, url });
                const parsedUrl = new URL(url, 'https://kiemlai.vn');
                this._lastUrl = url;
                // update current fake location
                this.win.location.pathname = parsedUrl.pathname;
                this.win.location.search = parsedUrl.search;
                this.win.location.hash = parsedUrl.hash;
            },
            getHistoryEntries: function () {
                return historyEntries;
            }
        }
    };
}

function setupTestDOM() {
    const doc = new FakeDocument();

    const chapterBody = doc.createElement('article');
    chapterBody.setAttribute('class', 'novel-reader-chapter-body');
    chapterBody.setAttribute('data-chapter-id', '11111111-1111-1111-1111-111111111111');
    chapterBody.setAttribute('data-content-version', '1');

    const blockA = doc.createElement('p');
    blockA.setAttribute('data-reader-block-key', 'blk-0123456789abcdef-1');
    blockA.setAttribute('data-comment-thread-count', '2');
    blockA.textContent = 'Nội dung đoạn văn A.';
    chapterBody.appendChild(blockA);

    const blockB = doc.createElement('p');
    blockB.setAttribute('data-reader-block-key', 'blk-fedcba9876543210-1');
    blockB.setAttribute('data-comment-thread-count', '0');
    blockB.textContent = 'Nội dung đoạn văn B.';
    chapterBody.appendChild(blockB);

    doc.body.appendChild(chapterBody);

    // Drawer content container
    const contentEl = doc.createElement('section');
    contentEl.setAttribute('id', 'novelBlockDiscussionContent');
    contentEl.setAttribute('class', 'novel-block-discussion-content');
    doc.body.appendChild(contentEl);

    return { doc, chapterBody, blockA, blockB, contentEl };
}

describe('MS-05E5G3A3 Novel Interaction Restore Tests', () => {

    beforeEach(() => {
        restoreModule.resetRestoreState();
    });

    afterEach(() => {
        restoreModule.resetRestoreState();
    });

    test('1. no restore params -> no action', () => {
        const { doc } = setupTestDOM();
        const win = createFakeWindow('');
        win.history.win = win;

        let requestedEvents = 0;
        doc.addEventListener('kiemlai:block-discussion-requested', () => {
            requestedEvents++;
        });

        restoreModule.init(doc, win);
        assert.strictEqual(requestedEvents, 0);
        assert.strictEqual(restoreModule.getActiveRestore(), null);
    });

    test('2. valid discussionBlock locates exact Reader block and dispatches exactly one discussion-requested event', () => {
        const { doc } = setupTestDOM();
        const win = createFakeWindow('?discussionBlock=blk-0123456789abcdef-1');
        win.history.win = win;

        const events = [];
        doc.addEventListener('kiemlai:block-discussion-requested', (e) => {
            events.push(e.detail);
        });

        restoreModule.init(doc, win);

        assert.strictEqual(events.length, 1);
        assert.strictEqual(events[0].blockKey, 'blk-0123456789abcdef-1');
        assert.strictEqual(events[0].chapterId, '11111111-1111-1111-1111-111111111111');
        assert.strictEqual(events[0].contentVersion, 1);
        assert.strictEqual(events[0].threadCount, 2);
        assert.strictEqual(events[0].canonicalText, 'Nội dung đoạn văn A.');
    });

    test('3. uses current DOM chapterId/contentVersion/canonical text rather than URL copies', () => {
        const { doc, chapterBody, blockA } = setupTestDOM();
        chapterBody.setAttribute('data-chapter-id', 'custom-ch-99');
        chapterBody.setAttribute('data-content-version', '5');
        blockA.textContent = 'Authoritative text directly from DOM';

        const win = createFakeWindow('?discussionBlock=blk-0123456789abcdef-1');
        win.history.win = win;

        let emitted = null;
        doc.addEventListener('kiemlai:block-discussion-requested', (e) => {
            emitted = e.detail;
        });

        restoreModule.init(doc, win);

        assert.ok(emitted);
        assert.strictEqual(emitted.chapterId, 'custom-ch-99');
        assert.strictEqual(emitted.contentVersion, 5);
        assert.strictEqual(emitted.canonicalText, 'Authoritative text directly from DOM');
    });

    test('4. waits for matching kiemlai:block-discussion-loaded before targeting comments', () => {
        const { doc, contentEl } = setupTestDOM();
        const win = createFakeWindow('?discussionBlock=blk-0123456789abcdef-1&threadId=11111111-1111-1111-1111-111111111111');
        win.history.win = win;

        const threadEl = doc.createElement('article');
        threadEl.className = 'novel-block-discussion-thread';
        threadEl.setAttribute('data-root-id', '11111111-1111-1111-1111-111111111111');
        contentEl.appendChild(threadEl);

        restoreModule.init(doc, win);

        // Before loaded event, element has not been scrolled or highlighted
        assert.strictEqual(threadEl.scrolledIntoView, false);
        assert.strictEqual(threadEl.classList.contains('is-restored-target'), false);

        // Now dispatch matching loaded event
        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-loaded',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                blockKey: 'blk-0123456789abcdef-1',
                contentVersion: 1,
                threadCount: 1
            }
        });

        assert.strictEqual(threadEl.scrolledIntoView, true);
        assert.strictEqual(threadEl.classList.contains('is-restored-target'), true);
    });

    test('5. ignores loaded event for wrong block or chapter', () => {
        const { doc, contentEl } = setupTestDOM();
        const win = createFakeWindow('?discussionBlock=blk-0123456789abcdef-1&threadId=11111111-1111-1111-1111-111111111111');
        win.history.win = win;

        const threadEl = doc.createElement('article');
        threadEl.className = 'novel-block-discussion-thread';
        threadEl.setAttribute('data-root-id', '11111111-1111-1111-1111-111111111111');
        contentEl.appendChild(threadEl);

        restoreModule.init(doc, win);

        // Mismatched blockKey
        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-loaded',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                blockKey: 'blk-OTHER-KEY',
                contentVersion: 1,
                threadCount: 1
            }
        });

        assert.strictEqual(threadEl.scrolledIntoView, false);
        assert.notStrictEqual(restoreModule.getActiveRestore(), null);

        // Mismatched chapterId
        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-loaded',
            detail: {
                chapterId: '99999999-9999-9999-9999-999999999999',
                blockKey: 'blk-0123456789abcdef-1',
                contentVersion: 1,
                threadCount: 1
            }
        });

        assert.strictEqual(threadEl.scrolledIntoView, false);
        assert.notStrictEqual(restoreModule.getActiveRestore(), null);
    });

    test('6. threadId restore scrolls exact root thread', () => {
        const { doc, contentEl } = setupTestDOM();
        const win = createFakeWindow('?discussionBlock=blk-0123456789abcdef-1&threadId=11111111-1111-1111-1111-111111111111');
        win.history.win = win;

        const thread1 = doc.createElement('article');
        thread1.className = 'novel-block-discussion-thread';
        thread1.setAttribute('data-root-id', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa');
        contentEl.appendChild(thread1);

        const thread2 = doc.createElement('article');
        thread2.className = 'novel-block-discussion-thread';
        thread2.setAttribute('data-root-id', '11111111-1111-1111-1111-111111111111');
        contentEl.appendChild(thread2);

        restoreModule.init(doc, win);

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-loaded',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                blockKey: 'blk-0123456789abcdef-1',
                contentVersion: 1,
                threadCount: 2
            }
        });

        assert.strictEqual(thread1.scrolledIntoView, false);
        assert.strictEqual(thread2.scrolledIntoView, true);
        assert.strictEqual(thread2.classList.contains('is-restored-target'), true);
    });

    test('7. replyTo restore scrolls exact reply', () => {
        const { doc, contentEl } = setupTestDOM();
        const targetReplyId = '22222222-2222-2222-2222-222222222222';
        const win = createFakeWindow(`?discussionBlock=blk-0123456789abcdef-1&threadId=11111111-1111-1111-1111-111111111111&replyTo=${targetReplyId}`);
        win.history.win = win;

        const thread = doc.createElement('article');
        thread.className = 'novel-block-discussion-thread';
        thread.setAttribute('data-root-id', '11111111-1111-1111-1111-111111111111');

        const reply1 = doc.createElement('article');
        reply1.className = 'novel-comment novel-comment--reply';
        reply1.setAttribute('data-reply-id', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb');
        thread.appendChild(reply1);

        const reply2 = doc.createElement('article');
        reply2.className = 'novel-comment novel-comment--reply';
        reply2.setAttribute('data-reply-id', targetReplyId);
        reply2.setAttribute('data-comment-id', targetReplyId);
        thread.appendChild(reply2);

        contentEl.appendChild(thread);

        restoreModule.init(doc, win);

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-loaded',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                blockKey: 'blk-0123456789abcdef-1',
                contentVersion: 1,
                threadCount: 1
            }
        });

        assert.strictEqual(reply1.scrolledIntoView, false);
        assert.strictEqual(reply2.scrolledIntoView, true);
        assert.strictEqual(reply2.classList.contains('is-restored-target'), true);
    });

    test('7b. replyTo-only targeting root comment scrolls .novel-comment--root, resolves threadId from owning thread, and emits reply event', () => {
        const { doc, contentEl } = setupTestDOM();
        const rootId = '11111111-1111-1111-1111-111111111111';
        // NO threadId in query params!
        const win = createFakeWindow(`?discussionBlock=blk-0123456789abcdef-1&replyTo=${rootId}&intent=reply`);
        win.history.win = win;

        const thread = doc.createElement('article');
        thread.className = 'novel-block-discussion-thread';
        thread.setAttribute('data-root-id', rootId);

        const rootEl = doc.createElement('div');
        rootEl.className = 'novel-comment novel-comment--root';
        rootEl.setAttribute('data-comment-id', rootId);
        thread.appendChild(rootEl);
        contentEl.appendChild(thread);

        const replyEvents = [];
        doc.addEventListener('kiemlai:comment-reply-resume-requested', (e) => {
            replyEvents.push(e.detail);
        });

        restoreModule.init(doc, win);

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-loaded',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                blockKey: 'blk-0123456789abcdef-1',
                contentVersion: 1,
                threadCount: 1
            }
        });

        // Exact .novel-comment--root is scrolled and highlighted
        assert.strictEqual(rootEl.scrolledIntoView, true);
        assert.strictEqual(rootEl.classList.contains('is-restored-target'), true);

        // Thread card is NOT target
        assert.strictEqual(thread.scrolledIntoView, false);
        assert.strictEqual(thread.classList.contains('is-restored-target'), false);

        // Exactly one reply-resume event with resolved threadId = owning root thread
        assert.strictEqual(replyEvents.length, 1);
        assert.strictEqual(replyEvents[0].threadId, rootId);
        assert.strictEqual(replyEvents[0].commentId, rootId);
    });

    test('7c. replyTo-only targeting child reply scrolls .novel-comment--reply, resolves threadId from owning thread, and emits reply event', () => {
        const { doc, contentEl } = setupTestDOM();
        const rootId = '11111111-1111-1111-1111-111111111111';
        const replyId = '22222222-2222-2222-2222-222222222222';
        // NO threadId in query params!
        const win = createFakeWindow(`?discussionBlock=blk-0123456789abcdef-1&replyTo=${replyId}&intent=reply`);
        win.history.win = win;

        const thread = doc.createElement('article');
        thread.className = 'novel-block-discussion-thread';
        thread.setAttribute('data-root-id', rootId);

        const rootEl = doc.createElement('div');
        rootEl.className = 'novel-comment novel-comment--root';
        rootEl.setAttribute('data-comment-id', rootId);

        const repliesContainer = doc.createElement('div');
        repliesContainer.className = 'novel-comment-replies';

        const replyEl = doc.createElement('article');
        replyEl.className = 'novel-comment novel-comment--reply';
        replyEl.setAttribute('data-reply-id', replyId);
        replyEl.setAttribute('data-comment-id', replyId);
        repliesContainer.appendChild(replyEl);

        thread.appendChild(rootEl);
        thread.appendChild(repliesContainer);
        contentEl.appendChild(thread);

        const replyEvents = [];
        doc.addEventListener('kiemlai:comment-reply-resume-requested', (e) => {
            replyEvents.push(e.detail);
        });

        restoreModule.init(doc, win);

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-loaded',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                blockKey: 'blk-0123456789abcdef-1',
                contentVersion: 1,
                threadCount: 1
            }
        });

        // Exact .novel-comment--reply is scrolled and highlighted
        assert.strictEqual(replyEl.scrolledIntoView, true);
        assert.strictEqual(replyEl.classList.contains('is-restored-target'), true);

        // Thread card and root are NOT targets
        assert.strictEqual(thread.scrolledIntoView, false);
        assert.strictEqual(rootEl.scrolledIntoView, false);

        // Exactly one reply-resume event with resolved threadId = owning root thread
        assert.strictEqual(replyEvents.length, 1);
        assert.strictEqual(replyEvents[0].threadId, rootId);
        assert.strictEqual(replyEvents[0].commentId, replyId);
    });

    test('8. replyTo targets .novel-comment--root element itself when matching root ID', () => {
        const { doc, contentEl } = setupTestDOM();
        const rootId = '11111111-1111-1111-1111-111111111111';
        const win = createFakeWindow(`?discussionBlock=blk-0123456789abcdef-1&threadId=${rootId}&replyTo=${rootId}`);
        win.history.win = win;

        const thread = doc.createElement('article');
        thread.className = 'novel-block-discussion-thread';
        thread.setAttribute('data-root-id', rootId);

        const rootEl = doc.createElement('div');
        rootEl.className = 'novel-comment novel-comment--root';
        rootEl.setAttribute('data-comment-id', rootId);
        thread.appendChild(rootEl);
        contentEl.appendChild(thread);

        restoreModule.init(doc, win);

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-loaded',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                blockKey: 'blk-0123456789abcdef-1',
                contentVersion: 1,
                threadCount: 1
            }
        });

        assert.strictEqual(rootEl.scrolledIntoView, true);
        assert.strictEqual(rootEl.classList.contains('is-restored-target'), true);
        assert.strictEqual(thread.scrolledIntoView, false);
    });

    test('8b. threadId=ROOT&intent=reply without replyTo scrolls thread card but emits NO reply event', () => {
        const { doc, contentEl } = setupTestDOM();
        const rootId = '11111111-1111-1111-1111-111111111111';
        const win = createFakeWindow(`?discussionBlock=blk-0123456789abcdef-1&threadId=${rootId}&intent=reply`);
        win.history.win = win;

        const thread = doc.createElement('article');
        thread.className = 'novel-block-discussion-thread';
        thread.setAttribute('data-root-id', rootId);

        const rootEl = doc.createElement('div');
        rootEl.className = 'novel-comment novel-comment--root';
        rootEl.setAttribute('data-comment-id', rootId);
        thread.appendChild(rootEl);
        contentEl.appendChild(thread);

        const replyEvents = [];
        doc.addEventListener('kiemlai:comment-reply-resume-requested', (e) => {
            replyEvents.push(e.detail);
        });

        restoreModule.init(doc, win);

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-loaded',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                blockKey: 'blk-0123456789abcdef-1',
                contentVersion: 1,
                threadCount: 1
            }
        });

        // Thread card itself is scrolled and highlighted
        assert.strictEqual(thread.scrolledIntoView, true);
        assert.strictEqual(thread.classList.contains('is-restored-target'), true);
        assert.strictEqual(rootEl.scrolledIntoView, false);

        // Crucial: NO reply handoff event because replyTo was not explicitly provided
        assert.strictEqual(replyEvents.length, 0);
    });

    test('8c. threadId=ROOT&replyTo=ROOT&intent=reply targets root element and emits reply handoff', () => {
        const { doc, contentEl } = setupTestDOM();
        const rootId = '11111111-1111-1111-1111-111111111111';
        const win = createFakeWindow(`?discussionBlock=blk-0123456789abcdef-1&threadId=${rootId}&replyTo=${rootId}&intent=reply`);
        win.history.win = win;

        const thread = doc.createElement('article');
        thread.className = 'novel-block-discussion-thread';
        thread.setAttribute('data-root-id', rootId);

        const rootEl = doc.createElement('div');
        rootEl.className = 'novel-comment novel-comment--root';
        rootEl.setAttribute('data-comment-id', rootId);
        thread.appendChild(rootEl);
        contentEl.appendChild(thread);

        const replyEvents = [];
        doc.addEventListener('kiemlai:comment-reply-resume-requested', (e) => {
            replyEvents.push(e.detail);
        });

        restoreModule.init(doc, win);

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-loaded',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                blockKey: 'blk-0123456789abcdef-1',
                contentVersion: 1,
                threadCount: 1
            }
        });

        assert.strictEqual(rootEl.scrolledIntoView, true);
        assert.strictEqual(rootEl.classList.contains('is-restored-target'), true);
        assert.strictEqual(thread.scrolledIntoView, false);

        assert.strictEqual(replyEvents.length, 1);
        assert.strictEqual(replyEvents[0].threadId, rootId);
        assert.strictEqual(replyEvents[0].commentId, rootId);
    });

    test('8d. live root with tombstoned child reply emits reply event for root without false tombstone detection', () => {
        const { doc, contentEl } = setupTestDOM();
        const rootId = '11111111-1111-1111-1111-111111111111';
        const tombstoneReplyId = '22222222-2222-2222-2222-222222222222';
        const win = createFakeWindow(`?discussionBlock=blk-0123456789abcdef-1&threadId=${rootId}&replyTo=${rootId}&intent=reply`);
        win.history.win = win;

        const thread = doc.createElement('article');
        thread.className = 'novel-block-discussion-thread';
        thread.setAttribute('data-root-id', rootId);

        const rootEl = doc.createElement('div');
        rootEl.className = 'novel-comment novel-comment--root';
        rootEl.setAttribute('data-comment-id', rootId);

        const repliesContainer = doc.createElement('div');
        repliesContainer.className = 'novel-comment-replies';

        const tombstoneReply = doc.createElement('article');
        tombstoneReply.className = 'novel-comment novel-comment--reply is-tombstone';
        tombstoneReply.setAttribute('data-reply-id', tombstoneReplyId);
        tombstoneReply.setAttribute('data-comment-id', tombstoneReplyId);

        const tombstoneBody = doc.createElement('div');
        tombstoneBody.className = 'novel-comment-body novel-comment-body--tombstone';
        tombstoneReply.appendChild(tombstoneBody);
        repliesContainer.appendChild(tombstoneReply);

        thread.appendChild(rootEl);
        thread.appendChild(repliesContainer);
        contentEl.appendChild(thread);

        const replyEvents = [];
        doc.addEventListener('kiemlai:comment-reply-resume-requested', (e) => {
            replyEvents.push(e.detail);
        });

        restoreModule.init(doc, win);

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-loaded',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                blockKey: 'blk-0123456789abcdef-1',
                contentVersion: 1,
                threadCount: 1
            }
        });

        assert.strictEqual(rootEl.scrolledIntoView, true);
        assert.strictEqual(rootEl.classList.contains('is-restored-target'), true);
        assert.strictEqual(replyEvents.length, 1);
        assert.strictEqual(replyEvents[0].commentId, rootId);
    });

    test('8e. regression: thread card data-root-id=ROOT but contained root comment data-comment-id!=ROOT degrades to thread card with NO reply event', () => {
        const { doc, contentEl } = setupTestDOM();
        const rootThreadId = '11111111-1111-1111-1111-111111111111';
        const mismatchedRootCommentId = '33333333-3333-3333-3333-333333333333';
        const win = createFakeWindow(`?discussionBlock=blk-0123456789abcdef-1&threadId=${rootThreadId}&replyTo=${rootThreadId}&intent=reply`);
        win.history.win = win;

        const thread = doc.createElement('article');
        thread.className = 'novel-block-discussion-thread';
        thread.setAttribute('data-root-id', rootThreadId);

        const mismatchedRootEl = doc.createElement('div');
        mismatchedRootEl.className = 'novel-comment novel-comment--root';
        mismatchedRootEl.setAttribute('data-comment-id', mismatchedRootCommentId);
        thread.appendChild(mismatchedRootEl);
        contentEl.appendChild(thread);

        const replyEvents = [];
        doc.addEventListener('kiemlai:comment-reply-resume-requested', (e) => {
            replyEvents.push(e.detail);
        });

        restoreModule.init(doc, win);

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-loaded',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                blockKey: 'blk-0123456789abcdef-1',
                contentVersion: 1,
                threadCount: 1
            }
        });

        // Degrades visually to the matching thread card for context
        assert.strictEqual(thread.scrolledIntoView, true);
        assert.strictEqual(thread.classList.contains('is-restored-target'), true);

        // Does NOT target the mismatched root comment
        assert.strictEqual(mismatchedRootEl.scrolledIntoView, false);
        assert.strictEqual(mismatchedRootEl.classList.contains('is-restored-target'), false);

        // MUST NOT emit kiemlai:comment-reply-resume-requested!
        assert.strictEqual(replyEvents.length, 0);
    });

    test('9. intent=reply + valid live target emits exactly one kiemlai:comment-reply-resume-requested', () => {
        const { doc, contentEl } = setupTestDOM();
        const targetReplyId = '22222222-2222-2222-2222-222222222222';
        const win = createFakeWindow(`?discussionBlock=blk-0123456789abcdef-1&threadId=11111111-1111-1111-1111-111111111111&replyTo=${targetReplyId}&intent=reply`);
        win.history.win = win;

        const thread = doc.createElement('article');
        thread.className = 'novel-block-discussion-thread';
        thread.setAttribute('data-root-id', '11111111-1111-1111-1111-111111111111');

        const reply = doc.createElement('article');
        reply.className = 'novel-comment novel-comment--reply';
        reply.setAttribute('data-reply-id', targetReplyId);
        reply.setAttribute('data-comment-id', targetReplyId);
        thread.appendChild(reply);
        contentEl.appendChild(thread);

        const replyEvents = [];
        doc.addEventListener('kiemlai:comment-reply-resume-requested', (e) => {
            replyEvents.push(e.detail);
        });

        restoreModule.init(doc, win);

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-loaded',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                blockKey: 'blk-0123456789abcdef-1',
                contentVersion: 1,
                threadCount: 1
            }
        });

        assert.strictEqual(replyEvents.length, 1);
        assert.strictEqual(replyEvents[0].chapterId, '11111111-1111-1111-1111-111111111111');
        assert.strictEqual(replyEvents[0].blockKey, 'blk-0123456789abcdef-1');
        assert.strictEqual(replyEvents[0].threadId, '11111111-1111-1111-1111-111111111111');
        assert.strictEqual(replyEvents[0].commentId, targetReplyId);
    });

    test('10. intent=reply + missing target emits NO reply event', () => {
        const { doc, contentEl } = setupTestDOM();
        const win = createFakeWindow('?discussionBlock=blk-0123456789abcdef-1&threadId=99999999-9999-9999-9999-999999999999&intent=reply');
        win.history.win = win;

        const replyEvents = [];
        doc.addEventListener('kiemlai:comment-reply-resume-requested', (e) => {
            replyEvents.push(e.detail);
        });

        restoreModule.init(doc, win);

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-loaded',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                blockKey: 'blk-0123456789abcdef-1',
                contentVersion: 1,
                threadCount: 0
            }
        });

        assert.strictEqual(replyEvents.length, 0);
    });

    test('11. tombstoned target emits NO reply event', () => {
        const { doc, contentEl } = setupTestDOM();
        const tombstoneId = '22222222-2222-2222-2222-222222222222';
        const win = createFakeWindow(`?discussionBlock=blk-0123456789abcdef-1&threadId=11111111-1111-1111-1111-111111111111&replyTo=${tombstoneId}&intent=reply`);
        win.history.win = win;

        const thread = doc.createElement('article');
        thread.className = 'novel-block-discussion-thread';
        thread.setAttribute('data-root-id', '11111111-1111-1111-1111-111111111111');

        const tombstoneReply = doc.createElement('article');
        tombstoneReply.className = 'novel-comment novel-comment--reply is-tombstone';
        tombstoneReply.setAttribute('data-reply-id', tombstoneId);
        tombstoneReply.setAttribute('data-comment-id', tombstoneId);
        thread.appendChild(tombstoneReply);
        contentEl.appendChild(thread);

        const replyEvents = [];
        doc.addEventListener('kiemlai:comment-reply-resume-requested', (e) => {
            replyEvents.push(e.detail);
        });

        restoreModule.init(doc, win);

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-loaded',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                blockKey: 'blk-0123456789abcdef-1',
                contentVersion: 1,
                threadCount: 1
            }
        });

        assert.strictEqual(tombstoneReply.scrolledIntoView, true);
        assert.strictEqual(replyEvents.length, 0); // No reply handoff on tombstone!
    });

    test('12. missing block in DOM does not open drawer and cleans transient params', () => {
        const { doc } = setupTestDOM();
        const win = createFakeWindow('?discussionBlock=blk-9999999999999999-1&threadId=11111111-1111-1111-1111-111111111111');
        win.history.win = win;

        let opened = false;
        doc.addEventListener('kiemlai:block-discussion-requested', () => {
            opened = true;
        });

        restoreModule.init(doc, win);

        assert.strictEqual(opened, false);
        assert.strictEqual(win.location.search, '');
    });

    test('13. malformed blockKey or UUID does not throw', () => {
        const { doc } = setupTestDOM();
        const win = createFakeWindow('?discussionBlock=invalid<script>&threadId=not-a-uuid&replyTo=123');
        win.history.win = win;

        assert.doesNotThrow(() => {
            restoreModule.init(doc, win);
        });
        assert.strictEqual(restoreModule.getActiveRestore(), null);
    });

    test('14. missing thread degrades to block-level drawer', () => {
        const { doc, contentEl } = setupTestDOM();
        const win = createFakeWindow('?discussionBlock=blk-0123456789abcdef-1&threadId=55555555-5555-5555-5555-555555555555');
        win.history.win = win;

        const otherThread = doc.createElement('article');
        otherThread.className = 'novel-block-discussion-thread';
        otherThread.setAttribute('data-root-id', '11111111-1111-1111-1111-111111111111');
        contentEl.appendChild(otherThread);

        restoreModule.init(doc, win);

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-loaded',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                blockKey: 'blk-0123456789abcdef-1',
                contentVersion: 1,
                threadCount: 1
            }
        });

        // Other thread was not targeted
        assert.strictEqual(otherThread.scrolledIntoView, false);
        assert.strictEqual(otherThread.classList.contains('is-restored-target'), false);
        assert.strictEqual(win.location.search, '');
    });

    test('15. missing reply with valid root degrades to root thread without reply intent', () => {
        const { doc, contentEl } = setupTestDOM();
        const win = createFakeWindow('?discussionBlock=blk-0123456789abcdef-1&threadId=11111111-1111-1111-1111-111111111111&replyTo=88888888-8888-8888-8888-888888888888&intent=reply');
        win.history.win = win;

        const thread = doc.createElement('article');
        thread.className = 'novel-block-discussion-thread';
        thread.setAttribute('data-root-id', '11111111-1111-1111-1111-111111111111');
        contentEl.appendChild(thread);

        const replyEvents = [];
        doc.addEventListener('kiemlai:comment-reply-resume-requested', (e) => {
            replyEvents.push(e.detail);
        });

        restoreModule.init(doc, win);

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-loaded',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                blockKey: 'blk-0123456789abcdef-1',
                contentVersion: 1,
                threadCount: 1
            }
        });

        // Root thread is scrolled
        assert.strictEqual(thread.scrolledIntoView, true);
        // Reply intent is NOT dispatched because exact reply was missing
        assert.strictEqual(replyEvents.length, 0);
    });

    test('16. transient params removed after terminal success via replaceState', () => {
        const { doc, contentEl } = setupTestDOM();
        const win = createFakeWindow('?discussionBlock=blk-0123456789abcdef-1&threadId=11111111-1111-1111-1111-111111111111');
        win.history.win = win;

        const thread = doc.createElement('article');
        thread.className = 'novel-block-discussion-thread';
        thread.setAttribute('data-root-id', '11111111-1111-1111-1111-111111111111');
        contentEl.appendChild(thread);

        restoreModule.init(doc, win);

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-loaded',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                blockKey: 'blk-0123456789abcdef-1',
                contentVersion: 1,
                threadCount: 1
            }
        });

        assert.strictEqual(win.location.search, '');
        assert.strictEqual(win.history._lastUrl, '/novel/chapters/quyen-1-chuong-1');
    });

    test('17. unrelated query params preserved during cleanup', () => {
        const { doc, contentEl } = setupTestDOM();
        const win = createFakeWindow('?font=serif&mode=dark&discussionBlock=blk-0123456789abcdef-1&threadId=11111111-1111-1111-1111-111111111111');
        win.history.win = win;

        restoreModule.init(doc, win);

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-loaded',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                blockKey: 'blk-0123456789abcdef-1',
                contentVersion: 1,
                threadCount: 1
            }
        });

        assert.strictEqual(win.location.search, '?font=serif&mode=dark');
    });

    test('18. hash preserved during cleanup', () => {
        const { doc, contentEl } = setupTestDOM();
        const win = createFakeWindow('?discussionBlock=blk-0123456789abcdef-1', '/novel/chapters/c1', '#chapter-note');
        win.history.win = win;

        restoreModule.init(doc, win);

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-loaded',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                blockKey: 'blk-0123456789abcdef-1',
                contentVersion: 1,
                threadCount: 1
            }
        });

        assert.strictEqual(win.location.hash, '#chapter-note');
        assert.strictEqual(win.history._lastUrl, '/novel/chapters/c1#chapter-note');
    });

    test('19. refresh/init after cleaned URL does nothing', () => {
        const { doc } = setupTestDOM();
        const win = createFakeWindow('');
        win.history.win = win;

        let opened = 0;
        doc.addEventListener('kiemlai:block-discussion-requested', () => {
            opened++;
        });

        restoreModule.init(doc, win);
        assert.strictEqual(opened, 0);
    });

    test('20. chapter-changed invalidates pending restore and cleans transient URL params', () => {
        const { doc, contentEl } = setupTestDOM();
        const win = createFakeWindow(
            '?discussionBlock=blk-0123456789abcdef-1&threadId=11111111-1111-1111-1111-111111111111&preserveMe=1',
            '/novel/chapters/quyen-1-chuong-1',
            '#pos1'
        );
        win.history.win = win;

        const thread = doc.createElement('article');
        thread.className = 'novel-block-discussion-thread';
        thread.setAttribute('data-root-id', '11111111-1111-1111-1111-111111111111');
        contentEl.appendChild(thread);

        restoreModule.init(doc, win);
        assert.notStrictEqual(restoreModule.getActiveRestore(), null);

        // Chapter changes while request is in flight
        doc.dispatchEvent({
            type: 'kiemlai:chapter-changed',
            detail: { chapterId: 'new-chapter-uuid' }
        });

        assert.strictEqual(restoreModule.getActiveRestore(), null);
        assert.strictEqual(win.location.search, '?preserveMe=1');
        assert.strictEqual(win.location.hash, '#pos1');

        // Stale loaded event arrives late from previous chapter
        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-loaded',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                blockKey: 'blk-0123456789abcdef-1',
                contentVersion: 1,
                threadCount: 1
            }
        });

        // Did not target previous thread
        assert.strictEqual(thread.scrolledIntoView, false);
    });

    test('20b. chapter-changed with NO restore params leaves URL completely untouched', () => {
        const { doc } = setupTestDOM();
        const win = createFakeWindow('?page=2&sort=asc', '/novel/chapters/quyen-1-chuong-1', '#section2');
        win.history.win = win;

        restoreModule.init(doc, win);
        assert.strictEqual(restoreModule.getActiveRestore(), null);

        let replaceStateCalled = false;
        const origReplace = win.history.replaceState;
        win.history.replaceState = function (...args) {
            replaceStateCalled = true;
            return origReplace.apply(this, args);
        };

        doc.dispatchEvent({
            type: 'kiemlai:chapter-changed',
            detail: { chapterId: 'new-chapter-uuid' }
        });

        assert.strictEqual(replaceStateCalled, false);
        assert.strictEqual(win.location.search, '?page=2&sort=asc');
        assert.strictEqual(win.location.hash, '#section2');
    });

    test('21. manual drawer close invalidates pending restore and cleans URL', () => {
        const { doc } = setupTestDOM();
        const win = createFakeWindow('?discussionBlock=blk-0123456789abcdef-1&threadId=11111111-1111-1111-1111-111111111111');
        win.history.win = win;

        restoreModule.init(doc, win);
        assert.notStrictEqual(restoreModule.getActiveRestore(), null);

        // User closes drawer before fetch finishes
        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-closed',
            detail: {}
        });

        assert.strictEqual(restoreModule.getActiveRestore(), null);
        assert.strictEqual(win.location.search, '');
    });

    test('22. late stale loaded event cannot scroll or highlight', () => {
        const { doc, contentEl } = setupTestDOM();
        const win = createFakeWindow('?discussionBlock=blk-0123456789abcdef-1&threadId=11111111-1111-1111-1111-111111111111');
        win.history.win = win;

        const thread = doc.createElement('article');
        thread.className = 'novel-block-discussion-thread';
        thread.setAttribute('data-root-id', '11111111-1111-1111-1111-111111111111');
        contentEl.appendChild(thread);

        restoreModule.init(doc, win);

        // Cancel restore manually
        restoreModule.resetRestoreState();

        // Late event arrives
        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-loaded',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                blockKey: 'blk-0123456789abcdef-1',
                contentVersion: 1,
                threadCount: 1
            }
        });

        assert.strictEqual(thread.scrolledIntoView, false);
        assert.strictEqual(thread.classList.contains('is-restored-target'), false);
    });

    test('23. temporary highlight removed after timeout', async () => {
        const { doc, contentEl } = setupTestDOM();
        const win = createFakeWindow('?discussionBlock=blk-0123456789abcdef-1&threadId=11111111-1111-1111-1111-111111111111');
        win.history.win = win;

        const thread = doc.createElement('article');
        thread.className = 'novel-block-discussion-thread';
        thread.setAttribute('data-root-id', '11111111-1111-1111-1111-111111111111');
        contentEl.appendChild(thread);

        restoreModule.init(doc, win);

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-loaded',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                blockKey: 'blk-0123456789abcdef-1',
                contentVersion: 1,
                threadCount: 1
            }
        });

        assert.strictEqual(thread.classList.contains('is-restored-target'), true);

        // Fast forward highlight timer
        await new Promise(r => setTimeout(r, 2600));

        assert.strictEqual(thread.classList.contains('is-restored-target'), false);
    });

    test('24. selector-injection-shaped input is rejected safely', () => {
        const { doc } = setupTestDOM();
        const injectionQuery = '?discussionBlock=blk-0123456789abcdef-1%22%5D%20div%7B&threadId=bad%22%3E%3Cscript%3E';
        const win = createFakeWindow(injectionQuery);
        win.history.win = win;

        assert.doesNotThrow(() => {
            restoreModule.init(doc, win);
        });
        assert.strictEqual(restoreModule.getActiveRestore(), null);
    });

    test('25. matching kiemlai:block-discussion-load-failed cleans URL and invalidates restore', () => {
        const { doc } = setupTestDOM();
        const win = createFakeWindow('?discussionBlock=blk-0123456789abcdef-1&threadId=11111111-1111-1111-1111-111111111111');
        win.history.win = win;

        restoreModule.init(doc, win);
        assert.notStrictEqual(restoreModule.getActiveRestore(), null);

        // Terminal load failure event
        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-load-failed',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                blockKey: 'blk-0123456789abcdef-1',
                reason: 'unavailable'
            }
        });

        // Restore invalidated and transient parameters cleaned
        assert.strictEqual(restoreModule.getActiveRestore(), null);
        assert.strictEqual(win.location.search, '');
    });

    test('26. mismatched kiemlai:block-discussion-load-failed is ignored', () => {
        const { doc } = setupTestDOM();
        const win = createFakeWindow('?discussionBlock=blk-0123456789abcdef-1&threadId=11111111-1111-1111-1111-111111111111');
        win.history.win = win;

        restoreModule.init(doc, win);
        assert.notStrictEqual(restoreModule.getActiveRestore(), null);

        // Mismatched blockKey
        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-load-failed',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                blockKey: 'blk-OTHER-KEY',
                reason: 'error'
            }
        });

        // Restore context remains active
        assert.notStrictEqual(restoreModule.getActiveRestore(), null);
        assert.notStrictEqual(win.location.search, '');
    });

    test('27. orphan transient parameters without discussionBlock are cleaned immediately without opening drawer', () => {
        const { doc } = setupTestDOM();
        const win = createFakeWindow('?threadId=11111111-1111-1111-1111-111111111111&intent=reply');
        win.history.win = win;

        let opened = false;
        doc.addEventListener('kiemlai:block-discussion-requested', () => {
            opened = true;
        });

        restoreModule.init(doc, win);

        assert.strictEqual(opened, false);
        assert.strictEqual(restoreModule.getActiveRestore(), null);
        assert.strictEqual(win.location.search, '');
    });

    test('28. malformed threadId, replyTo, intent, or blockKey are cleaned immediately without opening drawer', () => {
        const testCases = [
            '?discussionBlock=invalid_block_format',
            '?discussionBlock=blk-0123456789abcdef-1&threadId=not-a-uuid',
            '?discussionBlock=blk-0123456789abcdef-1&threadId=11111111-1111-1111-1111-111111111111&replyTo=bad-reply-uuid',
            '?discussionBlock=blk-0123456789abcdef-1&intent=unsupported_intent'
        ];

        for (const query of testCases) {
            const { doc } = setupTestDOM();
            const win = createFakeWindow(query);
            win.history.win = win;

            let opened = false;
            doc.addEventListener('kiemlai:block-discussion-requested', () => {
                opened = true;
            });

            restoreModule.init(doc, win);

            assert.strictEqual(opened, false, `Expected no drawer open for query: ${query}`);
            assert.strictEqual(restoreModule.getActiveRestore(), null);
            assert.strictEqual(win.location.search, '', `Expected cleaned URL for query: ${query}`);
            restoreModule.resetRestoreState();
        }
    });

    test('29. openDiscussionTarget dispatches drawer event, sets activeRestore, and leaves URL unchanged', () => {
        const { doc } = setupTestDOM();
        const win = createFakeWindow('?tab=info');
        win.history.win = win;

        let requestedEvents = 0;
        let eventDetail = null;
        doc.addEventListener('kiemlai:block-discussion-requested', (e) => {
            requestedEvents++;
            eventDetail = e.detail;
        });

        const success = restoreModule.openDiscussionTarget({
            blockKey: 'blk-0123456789abcdef-1',
            threadId: '11111111-1111-1111-1111-111111111111'
        }, doc, win);

        assert.strictEqual(success, true);
        assert.strictEqual(requestedEvents, 1);
        assert.ok(eventDetail);
        assert.strictEqual(eventDetail.chapterId, '11111111-1111-1111-1111-111111111111');
        assert.strictEqual(eventDetail.blockKey, 'blk-0123456789abcdef-1');
        assert.strictEqual(eventDetail.contentVersion, 1);
        assert.strictEqual(eventDetail.canonicalText, 'Nội dung đoạn văn A.');

        const active = restoreModule.getActiveRestore();
        assert.ok(active);
        assert.strictEqual(active.chapterId, '11111111-1111-1111-1111-111111111111');
        assert.strictEqual(active.blockKey, 'blk-0123456789abcdef-1');
        assert.strictEqual(active.threadId, '11111111-1111-1111-1111-111111111111');

        assert.strictEqual(win.location.search, '?tab=info', 'Programmatic bridge call must leave URL completely unchanged');
        assert.strictEqual(win.history.getHistoryEntries().length, 0, 'Must not touch history state');
    });

    test('30. openDiscussionTarget matching loaded event targets and scrolls exact thread card inside drawer', () => {
        const { doc, contentEl } = setupTestDOM();
        const win = createFakeWindow('');
        win.history.win = win;

        // Build two thread cards inside drawer
        const thread1 = doc.createElement('article');
        thread1.setAttribute('class', 'novel-block-discussion-thread');
        thread1.setAttribute('data-root-id', '11111111-1111-1111-1111-111111111111');
        const root1 = doc.createElement('div');
        root1.setAttribute('class', 'novel-comment novel-comment--root');
        root1.setAttribute('data-comment-id', '11111111-1111-1111-1111-111111111111');
        thread1.appendChild(root1);
        contentEl.appendChild(thread1);

        const thread2 = doc.createElement('article');
        thread2.setAttribute('class', 'novel-block-discussion-thread');
        thread2.setAttribute('data-root-id', '22222222-2222-2222-2222-222222222222');
        const root2 = doc.createElement('div');
        root2.setAttribute('class', 'novel-comment novel-comment--root');
        root2.setAttribute('data-comment-id', '22222222-2222-2222-2222-222222222222');
        thread2.appendChild(root2);
        contentEl.appendChild(thread2);

        const success = restoreModule.openDiscussionTarget({
            blockKey: 'blk-0123456789abcdef-1',
            threadId: '11111111-1111-1111-1111-111111111111'
        }, doc, win);
        assert.strictEqual(success, true);

        // Dispatch matching loaded event
        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-loaded',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                blockKey: 'blk-0123456789abcdef-1',
                threadCount: 2,
                commentCount: 2
            }
        });

        // Exact thread1 card must be targeted and scrolled
        assert.strictEqual(thread1.scrolledIntoView, true);
        assert.strictEqual(thread1.classList.contains('is-restored-target'), true);

        // Unrelated thread2 must NOT be targeted or scrolled
        assert.strictEqual(thread2.scrolledIntoView, false);
        assert.strictEqual(thread2.classList.contains('is-restored-target'), false);
    });

    test('31. openDiscussionTarget missing blockKey returns false with no event or activeRestore', () => {
        const { doc } = setupTestDOM();
        const win = createFakeWindow('');
        win.history.win = win;

        let opened = false;
        doc.addEventListener('kiemlai:block-discussion-requested', () => {
            opened = true;
        });

        const success = restoreModule.openDiscussionTarget({
            blockKey: 'blk-missingnonexistent-1',
            threadId: '11111111-1111-1111-1111-111111111111'
        }, doc, win);

        assert.strictEqual(success, false);
        assert.strictEqual(opened, false);
        assert.strictEqual(restoreModule.getActiveRestore(), null);
    });

    test('32. openDiscussionTarget completion with no transient params does not mutate URL', () => {
        const { doc, contentEl } = setupTestDOM();
        const win = createFakeWindow('?other=value', '/novel/chapters/quyen-1-chuong-1', '#section2');
        win.history.win = win;

        const thread = doc.createElement('article');
        thread.setAttribute('class', 'novel-block-discussion-thread');
        thread.setAttribute('data-root-id', '11111111-1111-1111-1111-111111111111');
        contentEl.appendChild(thread);

        const success = restoreModule.openDiscussionTarget({
            blockKey: 'blk-0123456789abcdef-1',
            threadId: '11111111-1111-1111-1111-111111111111'
        }, doc, win);
        assert.strictEqual(success, true);

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-loaded',
            detail: {
                chapterId: '11111111-1111-1111-1111-111111111111',
                blockKey: 'blk-0123456789abcdef-1',
                threadCount: 1,
                commentCount: 1
            }
        });

        assert.strictEqual(win.location.search, '?other=value');
        assert.strictEqual(win.location.hash, '#section2');
        assert.strictEqual(win.history.getHistoryEntries().length, 0, 'No history mutation should occur for programmatic calls');
    });
});

describe('UX-DRAFT-01D3A — Block Drawer Root Active-Block Restore Tests', () => {
    class MockStorage {
        constructor() {
            this.store = new Map();
        }
        getItem(k) {
            return this.store.has(k) ? this.store.get(k) : null;
        }
        setItem(k, v) {
            this.store.set(k, String(v));
        }
        removeItem(k) {
            this.store.delete(k);
        }
        clear() {
            this.store.clear();
        }
    }

    let mockStorage;
    let draftStore;

    beforeEach(() => {
        mockStorage = new MockStorage();
        draftStore = EphemeralDraftStore.createStore({
            storage: mockStorage,
            defaultTtlMs: 5 * 60 * 1000
        });
        restoreModule.resetRestoreState();
    });

    afterEach(() => {
        restoreModule.resetRestoreState();
    });

    test('1. Valid URL/post-auth restore works unchanged', () => {
        const { doc } = setupTestDOM();
        const win = createFakeWindow('?discussionBlock=blk-0123456789abcdef-1&threadId=11111111-1111-1111-1111-111111111111');
        win.history.win = win;

        let requestedCount = 0;
        doc.addEventListener('kiemlai:block-discussion-requested', () => {
            requestedCount++;
        });

        restoreModule.init(doc, win, { draftStore, draftAdapter: draftsAdapter });

        assert.strictEqual(requestedCount, 1);
        const active = restoreModule.getActiveRestore();
        assert.strictEqual(active.source, 'url');
        assert.strictEqual(active.blockKey, 'blk-0123456789abcdef-1');
        assert.strictEqual(active.threadId, '11111111-1111-1111-1111-111111111111');
    });

    test('2. URL restore takes precedence over active Root marker (exactly 1 open request, marker restore does not run)', () => {
        const { doc } = setupTestDOM();
        const win = createFakeWindow('?discussionBlock=blk-0123456789abcdef-1');
        win.history.win = win;

        // Set Root marker for Block B
        const markerKey = draftsAdapter.getBlockActiveMarkerKey('11111111-1111-1111-1111-111111111111');
        draftStore.save(markerKey, JSON.stringify({ type: 'root', blockKey: 'blk-fedcba9876543210-1' }));

        let requestedEvents = [];
        doc.addEventListener('kiemlai:block-discussion-requested', (e) => {
            requestedEvents.push(e.detail);
        });

        restoreModule.init(doc, win, { draftStore, draftAdapter: draftsAdapter });

        assert.strictEqual(requestedEvents.length, 1, 'Exactly one open request');
        assert.strictEqual(requestedEvents[0].blockKey, 'blk-0123456789abcdef-1', 'URL block takes precedence');
        assert.strictEqual(restoreModule.getActiveRestore().source, 'url');
    });

    test('3. Malformed transient URL cleans parameters and does not attempt draft restore in same turn', () => {
        const { doc } = setupTestDOM();
        const win = createFakeWindow('?discussionBlock=invalid-block-format');
        win.history.win = win;

        // Valid Root marker exists for Block A
        const markerKey = draftsAdapter.getBlockActiveMarkerKey('11111111-1111-1111-1111-111111111111');
        draftStore.save(markerKey, JSON.stringify({ type: 'root', blockKey: 'blk-0123456789abcdef-1' }));

        let requestedCount = 0;
        doc.addEventListener('kiemlai:block-discussion-requested', () => {
            requestedCount++;
        });

        restoreModule.init(doc, win, { draftStore, draftAdapter: draftsAdapter });

        assert.strictEqual(requestedCount, 0, 'No drawer open request in same turn');
        assert.strictEqual(win.location.search, '', 'Transient parameters cleaned');
        assert.notStrictEqual(draftStore.load(markerKey), null, 'Marker not consumed or cleared');
    });

    test('4. No URL restore + valid current-chapter Root marker uses openDiscussionTarget / emits kiemlai:block-discussion-requested', () => {
        const { doc } = setupTestDOM();
        const win = createFakeWindow('');
        win.history.win = win;

        const markerKey = draftsAdapter.getBlockActiveMarkerKey('11111111-1111-1111-1111-111111111111');
        draftStore.save(markerKey, JSON.stringify({ type: 'root', blockKey: 'blk-0123456789abcdef-1' }));

        let requestedDetail = null;
        doc.addEventListener('kiemlai:block-discussion-requested', (e) => {
            requestedDetail = e.detail;
        });

        restoreModule.init(doc, win, { draftStore, draftAdapter: draftsAdapter });

        assert.notStrictEqual(requestedDetail, null, 'Discussion requested event emitted');
        assert.strictEqual(requestedDetail.blockKey, 'blk-0123456789abcdef-1');
        assert.strictEqual(requestedDetail.chapterId, '11111111-1111-1111-1111-111111111111');
        const active = restoreModule.getActiveRestore();
        assert.strictEqual(active.source, 'draft-root');
    });

    test('5. Marker-driven restore derives current chapterId and contentVersion from live Reader DOM', () => {
        const { doc, chapterBody } = setupTestDOM();
        chapterBody.setAttribute('data-chapter-id', 'ch-live-dom-999');
        chapterBody.setAttribute('data-content-version', '5');

        const win = createFakeWindow('');
        win.history.win = win;

        const markerKey = draftsAdapter.getBlockActiveMarkerKey('ch-live-dom-999');
        draftStore.save(markerKey, JSON.stringify({ type: 'root', blockKey: 'blk-0123456789abcdef-1' }));

        let requestedDetail = null;
        doc.addEventListener('kiemlai:block-discussion-requested', (e) => {
            requestedDetail = e.detail;
        });

        restoreModule.init(doc, win, { draftStore, draftAdapter: draftsAdapter });

        assert.notStrictEqual(requestedDetail, null);
        assert.strictEqual(requestedDetail.chapterId, 'ch-live-dom-999');
        assert.strictEqual(requestedDetail.contentVersion, 5);
        assert.strictEqual(requestedDetail.blockKey, 'blk-0123456789abcdef-1');
    });

    test('6. Zero draft text in URL, discussion-requested detail, or marker payload', () => {
        const { doc } = setupTestDOM();
        const win = createFakeWindow('');
        win.history.win = win;

        const draftKey = draftsAdapter.getBlockRootDraftKey('11111111-1111-1111-1111-111111111111', 'blk-0123456789abcdef-1');
        const markerKey = draftsAdapter.getBlockActiveMarkerKey('11111111-1111-1111-1111-111111111111');
        const secretDraft = 'SECRET_SENSITIVE_DRAFT_TEXT_NO_LEAK';
        draftStore.save(draftKey, secretDraft);
        draftStore.save(markerKey, JSON.stringify({ type: 'root', blockKey: 'blk-0123456789abcdef-1' }));

        let requestedDetail = null;
        doc.addEventListener('kiemlai:block-discussion-requested', (e) => {
            requestedDetail = e.detail;
        });

        restoreModule.init(doc, win, { draftStore, draftAdapter: draftsAdapter });

        assert.strictEqual(win.location.search.includes(secretDraft), false, 'Draft text must not appear in URL');
        assert.strictEqual(JSON.stringify(requestedDetail).includes(secretDraft), false, 'Draft text must not appear in event detail');
        assert.strictEqual(draftStore.load(markerKey).includes(secretDraft), false, 'Draft text must not appear in marker');
    });

    test('7. Malformed JSON Root marker is safely removed, zero drawer requests', () => {
        const { doc } = setupTestDOM();
        const win = createFakeWindow('');
        win.history.win = win;

        const markerKey = draftsAdapter.getBlockActiveMarkerKey('11111111-1111-1111-1111-111111111111');
        draftStore.save(markerKey, '{corrupted-json-not-valid');

        let requestedCount = 0;
        doc.addEventListener('kiemlai:block-discussion-requested', () => {
            requestedCount++;
        });

        restoreModule.init(doc, win, { draftStore, draftAdapter: draftsAdapter });

        assert.strictEqual(requestedCount, 0, 'Zero requests dispatched');
        assert.strictEqual(draftStore.load(markerKey), null, 'Corrupted marker safely removed');
        assert.strictEqual(restoreModule.getActiveRestore(), null);
    });

    test('8. Root marker with blank/missing blockKey is safely removed', () => {
        const { doc } = setupTestDOM();
        const win = createFakeWindow('');
        win.history.win = win;

        const markerKey = draftsAdapter.getBlockActiveMarkerKey('11111111-1111-1111-1111-111111111111');
        draftStore.save(markerKey, JSON.stringify({ type: 'root', blockKey: '   ' }));

        let requestedCount = 0;
        doc.addEventListener('kiemlai:block-discussion-requested', () => {
            requestedCount++;
        });

        restoreModule.init(doc, win, { draftStore, draftAdapter: draftsAdapter });

        assert.strictEqual(requestedCount, 0, 'Zero requests dispatched');
        assert.strictEqual(draftStore.load(markerKey), null, 'Blank blockKey marker safely removed');
        assert.strictEqual(restoreModule.getActiveRestore(), null);
    });

    test('9. Reply marker ignored untouched', () => {
        const { doc } = setupTestDOM();
        const win = createFakeWindow('');
        win.history.win = win;

        const markerKey = draftsAdapter.getBlockActiveMarkerKey('11111111-1111-1111-1111-111111111111');
        const replyPayload = JSON.stringify({ type: 'reply', blockKey: 'blk-0123456789abcdef-1', commentId: 'c-1' });
        draftStore.save(markerKey, replyPayload);

        let requestedCount = 0;
        doc.addEventListener('kiemlai:block-discussion-requested', () => {
            requestedCount++;
        });

        restoreModule.init(doc, win, { draftStore, draftAdapter: draftsAdapter });

        assert.strictEqual(requestedCount, 0, 'Zero requests dispatched');
        assert.strictEqual(draftStore.load(markerKey), replyPayload, 'Reply marker left untouched');
        assert.strictEqual(restoreModule.getActiveRestore(), null);
    });

    test('10. Edit marker ignored untouched', () => {
        const { doc } = setupTestDOM();
        const win = createFakeWindow('');
        win.history.win = win;

        const markerKey = draftsAdapter.getBlockActiveMarkerKey('11111111-1111-1111-1111-111111111111');
        const editPayload = JSON.stringify({ type: 'edit', blockKey: 'blk-0123456789abcdef-1', commentId: 'c-2' });
        draftStore.save(markerKey, editPayload);

        let requestedCount = 0;
        doc.addEventListener('kiemlai:block-discussion-requested', () => {
            requestedCount++;
        });

        restoreModule.init(doc, win, { draftStore, draftAdapter: draftsAdapter });

        assert.strictEqual(requestedCount, 0, 'Zero requests dispatched');
        assert.strictEqual(draftStore.load(markerKey), editPayload, 'Edit marker left untouched');
        assert.strictEqual(restoreModule.getActiveRestore(), null);
    });

    test('11. Stale/missing block in DOM: no drawer request, Root marker removed, Root draft preserved', () => {
        const { doc } = setupTestDOM();
        const win = createFakeWindow('');
        win.history.win = win;

        const missingBlockKey = 'blk-abcdef0123456789-99';
        const draftKey = draftsAdapter.getBlockRootDraftKey('11111111-1111-1111-1111-111111111111', missingBlockKey);
        const markerKey = draftsAdapter.getBlockActiveMarkerKey('11111111-1111-1111-1111-111111111111');

        draftStore.save(draftKey, 'Draft for stale block to preserve');
        draftStore.save(markerKey, JSON.stringify({ type: 'root', blockKey: missingBlockKey }));

        let requestedCount = 0;
        doc.addEventListener('kiemlai:block-discussion-requested', () => {
            requestedCount++;
        });

        restoreModule.init(doc, win, { draftStore, draftAdapter: draftsAdapter });

        assert.strictEqual(requestedCount, 0, 'No drawer requested for missing block');
        assert.strictEqual(draftStore.load(markerKey), null, 'Root marker removed');
        assert.strictEqual(draftStore.load(draftKey), 'Draft for stale block to preserve', 'Draft preserved');
    });

    test('12. Wrong chapter isolation: only current chapter active-block key consulted', () => {
        const { doc } = setupTestDOM();
        const win = createFakeWindow('');
        win.history.win = win;

        const otherChapterId = '22222222-2222-2222-2222-222222222222';
        const markerKeyOther = draftsAdapter.getBlockActiveMarkerKey(otherChapterId);
        const otherPayload = JSON.stringify({ type: 'root', blockKey: 'blk-0123456789abcdef-1' });
        draftStore.save(markerKeyOther, otherPayload);

        let requestedCount = 0;
        doc.addEventListener('kiemlai:block-discussion-requested', () => {
            requestedCount++;
        });

        restoreModule.init(doc, win, { draftStore, draftAdapter: draftsAdapter });

        assert.strictEqual(requestedCount, 0, 'No drawer requested for other chapter marker');
        assert.strictEqual(draftStore.load(markerKeyOther), otherPayload, 'Other chapter marker untouched');
    });

    test('13. Marker-driven load failure (kiemlai:block-discussion-load-failed): active restore cancelled, marker removed, draft preserved, no auto-retry', () => {
        const { doc } = setupTestDOM();
        const win = createFakeWindow('');
        win.history.win = win;

        const chapterId = '11111111-1111-1111-1111-111111111111';
        const blockKey = 'blk-0123456789abcdef-1';
        const draftKey = draftsAdapter.getBlockRootDraftKey(chapterId, blockKey);
        const markerKey = draftsAdapter.getBlockActiveMarkerKey(chapterId);

        draftStore.save(draftKey, 'Draft content on load failure');
        draftStore.save(markerKey, JSON.stringify({ type: 'root', blockKey: blockKey }));

        restoreModule.init(doc, win, { draftStore, draftAdapter: draftsAdapter });
        assert.notStrictEqual(restoreModule.getActiveRestore(), null, 'Active restore initiated');

        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-load-failed',
            detail: { chapterId: chapterId, blockKey: blockKey }
        });

        assert.strictEqual(restoreModule.getActiveRestore(), null, 'Active restore cancelled');
        assert.strictEqual(draftStore.load(markerKey), null, 'Marker removed');
        assert.strictEqual(draftStore.load(draftKey), 'Draft content on load failure', 'Draft preserved');
    });

    test('14. Manual drawer close (kiemlai:block-discussion-closed) during marker-driven restore: active restore cancelled, marker removed, draft preserved', () => {
        const { doc } = setupTestDOM();
        const win = createFakeWindow('');
        win.history.win = win;

        const chapterId = '11111111-1111-1111-1111-111111111111';
        const blockKey = 'blk-0123456789abcdef-1';
        const draftKey = draftsAdapter.getBlockRootDraftKey(chapterId, blockKey);
        const markerKey = draftsAdapter.getBlockActiveMarkerKey(chapterId);

        draftStore.save(draftKey, 'Draft content on drawer close');
        draftStore.save(markerKey, JSON.stringify({ type: 'root', blockKey: blockKey }));

        restoreModule.init(doc, win, { draftStore, draftAdapter: draftsAdapter });
        assert.notStrictEqual(restoreModule.getActiveRestore(), null);

        doc.dispatchEvent({ type: 'kiemlai:block-discussion-closed' });

        assert.strictEqual(restoreModule.getActiveRestore(), null, 'Active restore cancelled');
        assert.strictEqual(draftStore.load(markerKey), null, 'Marker removed');
        assert.strictEqual(draftStore.load(draftKey), 'Draft content on drawer close', 'Draft preserved');
    });

    test('15. Repeated init on same document does not duplicate restore or dispatch duplicate open events', () => {
        const { doc } = setupTestDOM();
        const win = createFakeWindow('');
        win.history.win = win;

        const chapterId = '11111111-1111-1111-1111-111111111111';
        const blockKey = 'blk-0123456789abcdef-1';
        const markerKey = draftsAdapter.getBlockActiveMarkerKey(chapterId);
        draftStore.save(markerKey, JSON.stringify({ type: 'root', blockKey: blockKey }));

        let requestedCount = 0;
        let requestedEvents = [];
        doc.addEventListener('kiemlai:block-discussion-requested', (e) => {
            requestedCount++;
            requestedEvents.push(e.detail);
        });

        // 1. Initial restore: valid Root marker => exactly one discussion-requested
        restoreModule.init(doc, win, { draftStore, draftAdapter: draftsAdapter });
        assert.strictEqual(requestedCount, 1, 'Exactly one discussion-requested emitted on first init');

        // 2. In-flight repeat init does not duplicate
        restoreModule.init(doc, win, { draftStore, draftAdapter: draftsAdapter });
        assert.strictEqual(requestedCount, 1, 'In-flight repeat init does not duplicate request');

        // 3. Dispatch matching loaded event so marker-driven restore COMPLETES
        doc.dispatchEvent({
            type: 'kiemlai:block-discussion-loaded',
            detail: {
                chapterId: chapterId,
                contentVersion: 1,
                blockKey: blockKey,
                threadCount: 1,
                commentCount: 1
            }
        });

        // 4. activeRestore is null, but marker still represents active Root interaction
        assert.strictEqual(restoreModule.getActiveRestore(), null, 'activeRestore is null after load completion');
        assert.notStrictEqual(draftStore.load(markerKey), null, 'Root marker still present in store');

        // 5. Call init(doc, win) again -> assert NO second discussion-requested is emitted
        restoreModule.init(doc, win, { draftStore, draftAdapter: draftsAdapter });
        assert.strictEqual(requestedCount, 1, 'Completed draft-root restore suppresses duplicate init request');

        // 6. Drawer close clears completed context
        doc.dispatchEvent({ type: 'kiemlai:block-discussion-closed' });

        // 7. A different Root block marker restores normally
        const blockKey2 = 'blk-fedcba9876543210-1';
        draftStore.save(markerKey, JSON.stringify({ type: 'root', blockKey: blockKey2 }));
        restoreModule.init(doc, win, { draftStore, draftAdapter: draftsAdapter });
        assert.strictEqual(requestedCount, 2, 'Different Root block marker restores normally');
        assert.strictEqual(requestedEvents[1].blockKey, blockKey2);
    });
});
