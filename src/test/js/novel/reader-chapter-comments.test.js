const { test, describe, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert');
const path = require('path');

const commentsModule = require(path.join(__dirname, '../../../main/resources/static/js/novel/reader-chapter-comments.js'));

// ============================================================================
// Lightweight DOM Test Fixtures
// ============================================================================

class FakeClassList {
    constructor(element) {
        this.element = element;
        this.classes = new Set();
    }

    add(...names) {
        for (const name of names) {
            this.classes.add(name);
        }
        this._sync();
    }

    remove(...names) {
        for (const name of names) {
            this.classes.delete(name);
        }
        this._sync();
    }

    contains(name) {
        return this.classes.has(name);
    }

    _sync() {
        if (this.classes.size > 0) {
            this.element.attributes['class'] = Array.from(this.classes).join(' ');
        } else {
            delete this.element.attributes['class'];
        }
    }
}

class FakeElement {
    constructor(tagName, attributes = {}) {
        this.tagName = tagName.toUpperCase();
        this.attributes = {};
        this.childNodes = [];
        this.parentNode = null;
        this.parentElement = null;
        this.classList = new FakeClassList(this);
        this.listeners = {};
        this.style = {};
        this.hidden = false;
        this._textContent = '';
        this.isFocused = false;
        this.ownerDocument = null;

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

    appendChild(child) {
        child.parentNode = this;
        child.parentElement = this;
        child.ownerDocument = this.ownerDocument;
        this.childNodes.push(child);
        return child;
    }

    removeChild(child) {
        const idx = this.childNodes.indexOf(child);
        if (idx !== -1) {
            this.childNodes.splice(idx, 1);
            child.parentNode = null;
            child.parentElement = null;
        }
        return child;
    }

    replaceChildren(...newChildren) {
        for (const c of this.childNodes) {
            c.parentNode = null;
            c.parentElement = null;
        }
        this.childNodes = [];
        this._textContent = '';
        for (const child of newChildren) {
            this.appendChild(child);
        }
    }

    replaceChild(newChild, oldChild) {
        const idx = this.childNodes.indexOf(oldChild);
        if (idx !== -1) {
            this.childNodes[idx] = newChild;
            newChild.parentNode = this;
            newChild.parentElement = this;
            newChild.ownerDocument = this.ownerDocument;
            oldChild.parentNode = null;
            oldChild.parentElement = null;
            return oldChild;
        }
        return null;
    }

    get firstChild() {
        return this.childNodes[0] || null;
    }

    get isConnected() {
        let cur = this;
        while (cur) {
            if (cur.ownerDocument && (cur === cur.ownerDocument.documentElement || cur.parentNode === cur.ownerDocument || cur.parentElement === cur.ownerDocument.documentElement)) {
                return true;
            }
            cur = cur.parentElement || cur.parentNode;
        }
        return false;
    }

    contains(node) {
        let cur = node;
        while (cur) {
            if (cur === this) return true;
            cur = cur.parentElement || cur.parentNode;
        }
        return false;
    }

    hasAttribute(name) {
        return this.attributes[name] !== undefined;
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

    removeAttribute(name) {
        delete this.attributes[name];
        if (name === 'class') {
            this.classList.classes.clear();
        }
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
        try {
            evt.target = evt.target || this;
        } catch (_) {}
        try {
            evt.currentTarget = this;
        } catch (_) {}
        const handlers = this.listeners[evt.type] || [];
        for (const fn of [...handlers]) {
            fn.call(this, evt);
        }
        return !evt.defaultPrevented;
    }

    scrollIntoView(options) {
        this.scrollIntoViewCalled = true;
        this.lastScrollOptions = options;
    }

    focus() {
        this.isFocused = true;
        if (this.ownerDocument) {
            this.ownerDocument.activeElement = this;
        }
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
    let tag = null;
    let classPart = sel;
    if (!sel.startsWith('.')) {
        const dotIdx = sel.indexOf('.');
        if (dotIdx !== -1) {
            tag = sel.slice(0, dotIdx);
            classPart = sel.slice(dotIdx);
        } else {
            return el.tagName.toLowerCase() === sel.toLowerCase();
        }
    }
    if (tag && el.tagName.toLowerCase() !== tag.toLowerCase()) {
        return false;
    }
    const classes = classPart.split('.').filter(Boolean);
    return classes.every(c => el.classList.contains(c));
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

    let currentSet = [root];
    for (const part of parts) {
        const nextSet = [];
        for (const parent of currentSet) {
            nextSet.push(...querySelectorAllDeep(parent, part));
        }
        currentSet = nextSet;
    }
    return currentSet;
}

class FakeDocument {
    constructor() {
        this.body = new FakeElement('body');
        this.body.ownerDocument = this;
        this.documentElement = new FakeElement('html');
        this.documentElement.ownerDocument = this;
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
        try {
            evt.target = evt.target || this;
        } catch (_) {}
        const handlers = this.listeners[evt.type] || [];
        for (const fn of [...handlers]) {
            fn.call(this, evt);
        }
        return !evt.defaultPrevented;
    }
}

/**
 * Creates standard DOM fixture for chapter comments tests.
 */
function createStandardFixture(chapterId = 'c1234567-89ab-cdef-0123-456789abcdef') {
    const doc = new FakeDocument();

    const chapterBody = doc.createElement('div');
    chapterBody.className = 'novel-reader-chapter-body';
    chapterBody.setAttribute('data-chapter-id', chapterId);
    chapterBody.setAttribute('data-content-version', '1');

    const block1 = doc.createElement('p');
    block1.className = 'novel-reader-block';
    block1.setAttribute('data-reader-block-key', 'blk-test-1');
    block1.textContent = 'Đoạn văn thứ nhất...';
    chapterBody.appendChild(block1);

    const block2 = doc.createElement('p');
    block2.className = 'novel-reader-block';
    block2.setAttribute('data-reader-block-key', 'blk-test-2');
    block2.textContent = 'Đoạn văn thứ hai...';
    chapterBody.appendChild(block2);

    doc.body.appendChild(chapterBody);

    const section = doc.createElement('section');
    section.className = 'novel-chapter-comments';
    section.setAttribute('id', commentsModule.SECTION_ID);
    if (chapterId) {
        section.setAttribute('data-chapter-id', chapterId);
    }

    const header = doc.createElement('header');
    header.className = 'novel-chapter-comments-header';

    const title = doc.createElement('h2');
    title.setAttribute('id', commentsModule.TITLE_ID);
    title.className = 'novel-chapter-comments-title';
    title.textContent = 'Bình luận';

    const count = doc.createElement('span');
    count.setAttribute('id', commentsModule.COUNT_ID);
    count.className = 'novel-chapter-comments-count';

    header.appendChild(title);
    header.appendChild(count);

    const status = doc.createElement('div');
    status.setAttribute('id', commentsModule.STATUS_ID);
    status.className = 'novel-chapter-comments-status';

    const list = doc.createElement('div');
    list.setAttribute('id', commentsModule.LIST_ID);
    list.className = 'novel-chapter-comments-list';
    list.setAttribute('role', 'feed');
    list.setAttribute('aria-busy', 'false');

    section.appendChild(header);
    section.appendChild(status);
    section.appendChild(list);

    doc.body.appendChild(section);

    return { doc, section, header, title, count, status, list, chapterBody, block1, block2 };
}

// Sample feed data fixtures
function makeFeedResponse(items = [], hasNext = false) {
    return {
        items: items,
        page: 0,
        size: 20,
        hasNext: hasNext
    };
}

// ============================================================================
// Test Suite: reader-chapter-comments (MS-05E5H2C)
// ============================================================================

describe('Reader Chapter Comments Read UI (MS-05E5H2C)', () => {

    afterEach(() => {
        commentsModule.destroy();
    });

    test('1. No-op on missing DOM elements (section or list not found) without throwing or fetching', () => {
        const doc = new FakeDocument();
        let fetchCalled = false;
        const fakeFetch = () => {
            fetchCalled = true;
            return Promise.resolve({ ok: true, json: () => Promise.resolve(makeFeedResponse()) });
        };

        // Missing elements entirely
        commentsModule.init(doc, { fetch: fakeFetch, chapterId: 'c1' });
        assert.strictEqual(fetchCalled, false, 'Fetch should not be called when DOM is missing');
        assert.strictEqual(commentsModule.getState().status, 'idle');
    });

    test('2. Initial page-0 fetch when chapter ID is present on section element', async () => {
        const { doc } = createStandardFixture('c100');
        let requestedUrl = null;

        const fakeFetch = (url) => {
            requestedUrl = url;
            return Promise.resolve({
                ok: true,
                json: () => Promise.resolve(makeFeedResponse([]))
            });
        };

        commentsModule.init(doc, { fetch: fakeFetch });
        // Allow microtask ticks for promise chain
        await new Promise(r => setTimeout(r, 10));

        assert.strictEqual(
            requestedUrl,
            '/api/novel/chapters/c100/comments/feed?page=0&size=20',
            'Must issue fetch with page=0&size=20'
        );
    });

    test('3. Initial page-0 fetch resolving chapter ID from reader chapter body if absent on section', async () => {
        const { doc, section } = createStandardFixture('c200');
        section.removeAttribute('data-chapter-id'); // Remove from section to force body fallback
        let requestedUrl = null;

        const fakeFetch = (url) => {
            requestedUrl = url;
            return Promise.resolve({
                ok: true,
                json: () => Promise.resolve(makeFeedResponse([]))
            });
        };

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        assert.strictEqual(
            requestedUrl,
            '/api/novel/chapters/c200/comments/feed?page=0&size=20',
            'Must resolve chapterId from .novel-reader-chapter-body'
        );
    });

    test('4. Loading state displayed while fetch is in-flight (aria-busy="true", spinner, loading text)', () => {
        const { doc, list, status, count } = createStandardFixture('c300');
        let resolvePromise;
        const fetchPromise = new Promise((resolve) => {
            resolvePromise = resolve;
        });

        const fakeFetch = () => fetchPromise;

        commentsModule.init(doc, { fetch: fakeFetch });

        // Verify synchronous loading state immediately after init call
        assert.strictEqual(list.getAttribute('aria-busy'), 'true', 'List should have aria-busy="true" during loading');
        assert.strictEqual(count.textContent, '', 'Count should be empty during loading');

        const loadingDiv = status.querySelector('.novel-chapter-comments-loading');
        assert.ok(loadingDiv, 'Loading container must be present in status element');
        assert.strictEqual(loadingDiv.getAttribute('role'), 'status');
        assert.strictEqual(loadingDiv.getAttribute('aria-live'), 'polite');

        const spinner = loadingDiv.querySelector('.novel-chapter-comments-spinner');
        assert.ok(spinner, 'Spinner span must be present');
        assert.strictEqual(spinner.getAttribute('aria-hidden'), 'true');

        const loadingText = loadingDiv.querySelector('.novel-chapter-comments-loading-text');
        assert.ok(loadingText, 'Loading text must be present');
        assert.strictEqual(loadingText.textContent, 'Đang tải bình luận...');

        // Clean up pending promise
        resolvePromise({ ok: true, json: () => Promise.resolve(makeFeedResponse([])) });
    });

    test('5. Root thread order preserves API-delivered order (newest roots first)', async () => {
        const { doc, list } = createStandardFixture('c400');
        const items = [
            {
                rootCommentId: 'root-1',
                author: { userId: 'u1', displayName: 'Người Dùng 1', avatarUrl: null },
                body: 'Bình luận mới nhất',
                createdAt: '2026-09-18T10:30:00Z',
                updatedAt: '2026-09-18T10:30:00Z',
                edited: false,
                replyCount: 0,
                replies: []
            },
            {
                rootCommentId: 'root-2',
                author: { userId: 'u2', displayName: 'Người Dùng 2', avatarUrl: null },
                body: 'Bình luận cũ hơn',
                createdAt: '2026-09-18T09:00:00Z',
                updatedAt: '2026-09-18T09:00:00Z',
                edited: false,
                replyCount: 0,
                replies: []
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        const threadCards = list.querySelectorAll('.novel-block-discussion-thread');
        assert.strictEqual(threadCards.length, 2, 'Should render 2 thread cards');
        assert.strictEqual(threadCards[0].getAttribute('data-root-id'), 'root-1');
        assert.strictEqual(threadCards[1].getAttribute('data-root-id'), 'root-2');
    });

    test('6. Replies rendered underneath each root thread in delivered order (chronological ASC)', async () => {
        const { doc, list } = createStandardFixture('c500');
        const items = [
            {
                rootCommentId: 'root-10',
                author: { userId: 'u10', displayName: 'Root Author', avatarUrl: null },
                body: 'Nội dung root',
                createdAt: '2026-09-18T08:00:00Z',
                updatedAt: '2026-09-18T08:00:00Z',
                edited: false,
                replyCount: 2,
                replies: [
                    {
                        id: 'rep-1',
                        authorUserId: 'u11',
                        parentCommentId: 'root-10',
                        body: 'Phản hồi đầu tiên',
                        tombstone: false,
                        createdAt: '2026-09-18T08:10:00Z',
                        updatedAt: '2026-09-18T08:10:00Z',
                        author: { userId: 'u11', displayName: 'Reply Author 1', avatarUrl: null }
                    },
                    {
                        id: 'rep-2',
                        authorUserId: 'u12',
                        parentCommentId: 'root-10',
                        body: 'Phản hồi thứ hai',
                        tombstone: false,
                        createdAt: '2026-09-18T08:20:00Z',
                        updatedAt: '2026-09-18T08:20:00Z',
                        author: { userId: 'u12', displayName: 'Reply Author 2', avatarUrl: null }
                    }
                ]
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        const repliesContainer = list.querySelector('.novel-comment-replies');
        assert.ok(repliesContainer, 'Replies container should exist');
        assert.strictEqual(repliesContainer.getAttribute('role'), 'group');

        const replyEls = repliesContainer.querySelectorAll('.novel-comment--reply');
        assert.strictEqual(replyEls.length, 2, 'Should render 2 replies');
        assert.strictEqual(replyEls[0].getAttribute('data-reply-id'), 'rep-1');
        assert.strictEqual(replyEls[1].getAttribute('data-reply-id'), 'rep-2');
    });

    test('7. Flat reply rendering: all replies are direct children of .novel-comment-replies', async () => {
        const { doc, list } = createStandardFixture('c600');
        const items = [
            {
                rootCommentId: 'root-20',
                author: { userId: 'u20', displayName: 'Root', avatarUrl: null },
                body: 'Root body',
                createdAt: '2026-09-18T08:00:00Z',
                updatedAt: '2026-09-18T08:00:00Z',
                edited: false,
                replyCount: 2,
                replies: [
                    {
                        id: 'rep-21',
                        parentCommentId: 'root-20',
                        body: 'Reply to root',
                        tombstone: false,
                        createdAt: '2026-09-18T08:05:00Z',
                        updatedAt: '2026-09-18T08:05:00Z',
                        author: { userId: 'u21', displayName: 'User 21', avatarUrl: null }
                    },
                    {
                        id: 'rep-22',
                        parentCommentId: 'rep-21', // nested reply to rep-21
                        body: 'Nested reply to rep-21',
                        tombstone: false,
                        createdAt: '2026-09-18T08:10:00Z',
                        updatedAt: '2026-09-18T08:10:00Z',
                        author: { userId: 'u22', displayName: 'User 22', avatarUrl: null }
                    }
                ]
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        const repliesContainer = list.querySelector('.novel-comment-replies');
        assert.strictEqual(repliesContainer.childNodes.length, 2, 'Both replies must be direct children of container (flat)');

        // Second reply should have immediate parent mention @User 21
        const nestedReply = repliesContainer.childNodes[1];
        const mention = nestedReply.querySelector('.novel-comment-reply-mention');
        assert.ok(mention, 'Nested reply must have immediate-parent mention');
        assert.strictEqual(mention.textContent, '@User 21');
    });

    test('8. Author presentation: display name, fallback avatar initial, and sanitized avatar img URL', async () => {
        const { doc, list } = createStandardFixture('c700');
        const items = [
            {
                rootCommentId: 'root-30',
                author: { userId: 'u30', displayName: 'Alice', avatarUrl: 'https://cdn.example.com/alice.jpg' },
                body: 'Alice comment',
                createdAt: '2026-09-18T08:00:00Z',
                updatedAt: '2026-09-18T08:00:00Z',
                edited: false,
                replyCount: 1,
                replies: [
                    {
                        id: 'rep-31',
                        parentCommentId: 'root-30',
                        body: 'Bob reply',
                        tombstone: false,
                        createdAt: '2026-09-18T08:05:00Z',
                        updatedAt: '2026-09-18T08:05:00Z',
                        author: { userId: 'u31', displayName: 'Bob', avatarUrl: null }
                    }
                ]
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        // Alice (Root): Image avatar
        const rootEl = list.querySelector('.novel-comment--root');
        const img = rootEl.querySelector('img.novel-comment-avatar');
        assert.ok(img, 'Valid avatarUrl should render <img> tag');
        assert.strictEqual(img.getAttribute('src'), 'https://cdn.example.com/alice.jpg');
        assert.strictEqual(img.getAttribute('alt'), 'Alice');
        assert.strictEqual(img.getAttribute('referrerpolicy'), 'no-referrer');

        const authorName = rootEl.querySelector('.novel-comment-author');
        assert.strictEqual(authorName.textContent, 'Alice');

        // Bob (Reply): Fallback avatar with initial 'B'
        const replyEl = list.querySelector('.novel-comment--reply');
        const fallback = replyEl.querySelector('.novel-comment-avatar--fallback');
        assert.ok(fallback, 'Missing avatarUrl should render fallback initial');
        assert.strictEqual(fallback.textContent, 'B');
    });

    test('9. Timestamp formatting with <time> datetime attribute', async () => {
        const { doc, list } = createStandardFixture('c800');
        const items = [
            {
                rootCommentId: 'root-40',
                author: { userId: 'u40', displayName: 'Time Tester', avatarUrl: null },
                body: 'Timestamp test',
                createdAt: '2026-09-18T10:15:00.000Z',
                updatedAt: '2026-09-18T10:15:00.000Z',
                edited: false,
                replyCount: 0,
                replies: []
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        const timeEl = list.querySelector('time.novel-comment-time');
        assert.ok(timeEl, '<time> element must be present');
        assert.strictEqual(timeEl.getAttribute('datetime'), '2026-09-18T10:15:00.000Z');
        assert.ok(timeEl.textContent.length > 0, 'Formatted timestamp must not be empty');
    });

    test('10. Edited label displayed when edited=true or updatedAt > createdAt', async () => {
        const { doc, list } = createStandardFixture('c900');
        const items = [
            {
                rootCommentId: 'root-50',
                author: { userId: 'u50', displayName: 'Edited User', avatarUrl: null },
                body: 'Edited root',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:30:00Z',
                edited: true,
                replyCount: 1,
                replies: [
                    {
                        id: 'rep-51',
                        parentCommentId: 'root-50',
                        body: 'Unedited reply',
                        tombstone: false,
                        createdAt: '2026-09-18T10:35:00Z',
                        updatedAt: '2026-09-18T10:35:00Z',
                        edited: false,
                        author: { userId: 'u51', displayName: 'User 51', avatarUrl: null }
                    }
                ]
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        const rootEl = list.querySelector('.novel-comment--root');
        const rootEdited = rootEl.querySelector('.novel-comment-edited');
        assert.ok(rootEdited, 'Root edited indicator must be present');
        assert.strictEqual(rootEdited.textContent, 'đã chỉnh sửa');

        const replyEl = list.querySelector('.novel-comment--reply');
        const replyEdited = replyEl.querySelector('.novel-comment-edited');
        assert.strictEqual(replyEdited, null, 'Unedited reply must not have edited indicator');
    });

    test('11. Tombstone reply: author/avatar/actions omitted, text rendered, contextual single child mention', async () => {
        const { doc, list } = createStandardFixture('c1000');
        const items = [
            {
                rootCommentId: 'root-60',
                author: { userId: 'u60', displayName: 'Root', avatarUrl: null },
                body: 'Root body',
                createdAt: '2026-09-18T08:00:00Z',
                updatedAt: '2026-09-18T08:00:00Z',
                edited: false,
                replyCount: 2,
                replies: [
                    {
                        id: 'rep-tombstone',
                        parentCommentId: 'root-60',
                        body: null,
                        tombstone: true,
                        status: 'DELETED',
                        createdAt: '2026-09-18T08:05:00Z',
                        updatedAt: '2026-09-18T08:15:00Z',
                        author: null
                    },
                    {
                        id: 'rep-child',
                        parentCommentId: 'rep-tombstone',
                        body: 'Child of deleted reply',
                        tombstone: false,
                        createdAt: '2026-09-18T08:10:00Z',
                        updatedAt: '2026-09-18T08:10:00Z',
                        author: { userId: 'u62', displayName: 'Con Gái', avatarUrl: null }
                    }
                ]
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        const tombstoneEl = list.querySelector('.novel-comment--reply.is-tombstone');
        assert.ok(tombstoneEl, 'Tombstone reply must have .is-tombstone');

        // Check header, avatar, author, time, edited are all omitted
        assert.strictEqual(tombstoneEl.querySelector('.novel-comment-header'), null);
        assert.strictEqual(tombstoneEl.querySelector('.novel-comment-avatar'), null);
        assert.strictEqual(tombstoneEl.querySelector('.novel-comment-author'), null);
        assert.strictEqual(tombstoneEl.querySelector('.novel-comment-time'), null);

        // Check contextual child mention: exactly 1 active child -> "Bình luận mà @Con Gái phản hồi đã bị xóa."
        const tombstoneBody = tombstoneEl.querySelector('.novel-comment-body--tombstone');
        assert.ok(tombstoneBody, 'Tombstone body must be present');
        assert.strictEqual(tombstoneBody.textContent, 'Bình luận mà @Con Gái phản hồi đã bị xóa.');
    });

    test('12. Exclusion of passageExcerpt, blockKey, anchorStatus, and mutation action buttons', async () => {
        const { doc, list } = createStandardFixture('c1100');
        const items = [
            {
                rootCommentId: 'root-70',
                author: { userId: 'u70', displayName: 'User 70', avatarUrl: null },
                body: 'Root comment body',
                createdAt: '2026-09-18T08:00:00Z',
                updatedAt: '2026-09-18T08:00:00Z',
                edited: false,
                canEdit: true, // Backend gives permission, but read UI must NOT render buttons
                canDelete: true,
                anchorStatus: 'ANCHORED',
                blockKey: 'p-42',
                passageExcerpt: 'Trích đoạn bí mật không được hiển thị',
                replyCount: 0,
                replies: []
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        const html = list.textContent;
        assert.strictEqual(html.includes('Trích đoạn bí mật'), false, 'passageExcerpt must NOT be rendered');
        assert.strictEqual(html.includes('p-42'), false, 'blockKey must NOT be rendered in text');

        // No actions container, no reply/edit/delete buttons
        assert.strictEqual(list.querySelector('.novel-comment-actions'), null);
        assert.strictEqual(list.querySelector('.novel-comment-reply-btn'), null);
        assert.strictEqual(list.querySelector('.novel-comment-edit-btn'), null);
        assert.strictEqual(list.querySelector('.novel-comment-delete-btn'), null);
    });

    test('13. Empty state rendered when items array is empty with "Chưa có bình luận nào." and count "0 bình luận"', async () => {
        const { doc, list, status, count } = createStandardFixture('c1200');

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse([]))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        assert.strictEqual(list.getAttribute('aria-busy'), 'false');
        assert.strictEqual(status.childNodes.length, 0, 'Status should be empty on successful fetch');

        const emptyEl = list.querySelector('.novel-chapter-comments-empty');
        assert.ok(emptyEl, 'Empty state container should exist');

        const textEl = emptyEl.querySelector('.novel-chapter-comments-empty-text');
        assert.strictEqual(textEl.textContent, 'Chưa có bình luận nào.');

        assert.strictEqual(count.textContent, '0 bình luận', 'Count header should show 0 bình luận');
    });

    test('14. Error state rendered on HTTP 500 / network error with retry button', async () => {
        const { doc, list, status, count } = createStandardFixture('c1300');

        const fakeFetch = () => Promise.reject(new Error('Network error'));

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        assert.strictEqual(list.getAttribute('aria-busy'), 'false');
        assert.strictEqual(list.childNodes.length, 0, 'List should be empty on error');
        assert.strictEqual(count.textContent, '', 'Count should be cleared on error');

        const errorDiv = status.querySelector('.novel-chapter-comments-error');
        assert.ok(errorDiv, 'Error container must exist in status');
        assert.strictEqual(errorDiv.getAttribute('role'), 'alert');

        const errorText = errorDiv.querySelector('.novel-chapter-comments-error-text');
        assert.strictEqual(errorText.textContent, 'Không thể tải bình luận. Vui lòng thử lại.');

        const retryBtn = errorDiv.querySelector('button.novel-chapter-comments-retry-btn');
        assert.ok(retryBtn, 'Retry button must exist');
        assert.strictEqual(retryBtn.textContent, 'Thử lại');
    });

    test('15. Retry button initiates new fetch and transitions to populated state upon success', async () => {
        const { doc, list, status } = createStandardFixture('c1400');
        let callCount = 0;

        const fakeFetch = () => {
            callCount++;
            if (callCount === 1) {
                return Promise.resolve({ ok: false, status: 500 });
            }
            return Promise.resolve({
                ok: true,
                json: () => Promise.resolve(makeFeedResponse([
                    {
                        rootCommentId: 'recovered-root',
                        author: { userId: 'u99', displayName: 'Recovered User', avatarUrl: null },
                        body: 'Thành công sau retry',
                        createdAt: '2026-09-18T10:00:00Z',
                        updatedAt: '2026-09-18T10:00:00Z',
                        edited: false,
                        replyCount: 0,
                        replies: []
                    }
                ]))
            });
        };

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        // First call failed
        assert.strictEqual(commentsModule.getState().status, 'error');
        const retryBtn = status.querySelector('.novel-chapter-comments-retry-btn');
        assert.ok(retryBtn);

        // Click retry
        retryBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        await new Promise(r => setTimeout(r, 10));

        // Second call succeeded
        assert.strictEqual(callCount, 2, 'Fetch should have been called twice');
        assert.strictEqual(commentsModule.getState().status, 'populated');
        const thread = list.querySelector('.novel-block-discussion-thread');
        assert.ok(thread, 'Thread card should now be rendered');
        assert.strictEqual(thread.getAttribute('data-root-id'), 'recovered-root');
    });

    test('16. Token race safety: stale responses from earlier fetches are ignored', async () => {
        const { doc, list } = createStandardFixture('c1500');
        let resolveSlow;

        const slowPromise = new Promise(resolve => {
            resolveSlow = resolve;
        });

        const fastData = makeFeedResponse([
            {
                rootCommentId: 'fast-root',
                author: { userId: 'u1', displayName: 'Fast', avatarUrl: null },
                body: 'Fast comment',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                edited: false,
                replyCount: 0,
                replies: []
            }
        ]);

        const slowData = makeFeedResponse([
            {
                rootCommentId: 'slow-root',
                author: { userId: 'u2', displayName: 'Slow', avatarUrl: null },
                body: 'Slow comment',
                createdAt: '2026-09-18T09:00:00Z',
                updatedAt: '2026-09-18T09:00:00Z',
                edited: false,
                replyCount: 0,
                replies: []
            }
        ]);

        let fetchCount = 0;
        const fakeFetch = () => {
            fetchCount++;
            if (fetchCount === 1) {
                return slowPromise;
            }
            return Promise.resolve({ ok: true, json: () => Promise.resolve(fastData) });
        };

        commentsModule.init(doc, { fetch: fakeFetch });

        // Trigger a second fetch before the first one resolves
        commentsModule.retry();
        await new Promise(r => setTimeout(r, 10));

        // Second fetch finished first
        assert.strictEqual(commentsModule.getState().status, 'populated');
        let threads = list.querySelectorAll('.novel-block-discussion-thread');
        assert.strictEqual(threads[0].getAttribute('data-root-id'), 'fast-root');

        // Now resolve the first (slow) fetch
        resolveSlow({ ok: true, json: () => Promise.resolve(slowData) });
        await new Promise(r => setTimeout(r, 10));

        // The slow response must be ignored!
        threads = list.querySelectorAll('.novel-block-discussion-thread');
        assert.strictEqual(threads.length, 1);
        assert.strictEqual(threads[0].getAttribute('data-root-id'), 'fast-root');
    });

    test('17. kiemlai:chapter-changed event updates chapter ID and triggers new fetch', async () => {
        const { doc, list } = createStandardFixture('c1600-original');
        const requestedUrls = [];

        const fakeFetch = (url) => {
            requestedUrls.push(url);
            return Promise.resolve({
                ok: true,
                json: () => Promise.resolve(makeFeedResponse([
                    {
                        rootCommentId: 'chapter-' + requestedUrls.length,
                        author: { userId: 'u1', displayName: 'Reader', avatarUrl: null },
                        body: 'Comment ' + requestedUrls.length,
                        createdAt: '2026-09-18T10:00:00Z',
                        updatedAt: '2026-09-18T10:00:00Z',
                        edited: false,
                        replyCount: 0,
                        replies: []
                    }
                ]))
            });
        };

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        assert.strictEqual(requestedUrls.length, 1);
        assert.ok(requestedUrls[0].includes('c1600-original'));

        // Dispatch chapter-changed event
        doc.dispatchEvent({
            type: 'kiemlai:chapter-changed',
            detail: { chapterId: 'c1600-next' }
        });
        await new Promise(r => setTimeout(r, 10));

        assert.strictEqual(requestedUrls.length, 2);
        assert.ok(requestedUrls[1].includes('c1600-next'));
        assert.strictEqual(commentsModule.getState().chapterId, 'c1600-next');
    });

    test('18. Header comment count accurately sums roots and replies', async () => {
        const { doc, count } = createStandardFixture('c1700');
        const items = [
            {
                rootCommentId: 'r1',
                author: { userId: 'u1', displayName: 'U1', avatarUrl: null },
                body: 'B1',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                edited: false,
                replyCount: 2,
                replies: [{}, {}]
            },
            {
                rootCommentId: 'r2',
                author: { userId: 'u2', displayName: 'U2', avatarUrl: null },
                body: 'B2',
                createdAt: '2026-09-18T09:00:00Z',
                updatedAt: '2026-09-18T09:00:00Z',
                edited: false,
                replyCount: 3,
                replies: [{}, {}, {}]
            }
        ];

        // Total = (1 + 2) + (1 + 3) = 7 bình luận
        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        assert.strictEqual(count.textContent, '7 bình luận');
    });

    test('19. Destroy cleanly clears state, detaches listeners, and invalidates in-flight fetches', async () => {
        const { doc, list, status, count } = createStandardFixture('c1800');
        let resolveInFlight;
        const inFlightPromise = new Promise(resolve => {
            resolveInFlight = resolve;
        });

        commentsModule.init(doc, { fetch: () => inFlightPromise });

        assert.strictEqual(commentsModule.getState().status, 'loading');

        commentsModule.destroy();

        assert.strictEqual(commentsModule.getState().status, 'idle');
        assert.strictEqual(commentsModule.getState().chapterId, null);
        assert.strictEqual(list.childNodes.length, 0);
        assert.strictEqual(status.childNodes.length, 0);
        assert.strictEqual(count.textContent, '');

        // Resolve after destroy
        resolveInFlight({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse([
                {
                    rootCommentId: 'late-root',
                    author: { userId: 'u1', displayName: 'U1', avatarUrl: null },
                    body: 'Late',
                    createdAt: '2026-09-18T10:00:00Z',
                    updatedAt: '2026-09-18T10:00:00Z',
                    edited: false,
                    replyCount: 0,
                    replies: []
                }
            ]))
        });
        await new Promise(r => setTimeout(r, 10));

        // State remains idle and DOM remains empty
        assert.strictEqual(commentsModule.getState().status, 'idle');
        assert.strictEqual(list.childNodes.length, 0);
    });

    test('20. CURRENT root renders ⋯ with "Xem đoạn gốc" action', async () => {
        const { doc, list } = createStandardFixture('c2000');
        const items = [
            {
                rootCommentId: 'r-current',
                author: { userId: 'u1', displayName: 'User 1', avatarUrl: null },
                body: 'Current root comment',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                edited: false,
                anchorStatus: 'CURRENT',
                blockKey: 'blk-test-1',
                replyCount: 0,
                replies: []
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        const trigger = list.querySelector('.novel-comment-menu-trigger');
        assert.ok(trigger, 'Menu trigger button must exist on CURRENT root');
        assert.strictEqual(trigger.getAttribute('aria-label'), 'Mở menu bình luận');
        assert.strictEqual(trigger.getAttribute('aria-haspopup'), 'menu');
        assert.strictEqual(trigger.getAttribute('aria-expanded'), 'false');

        const popover = list.querySelector('.novel-comment-menu-popover');
        assert.ok(popover, 'Menu popover must exist');
        assert.strictEqual(popover.hidden, true);

        // Click trigger to open menu
        trigger.dispatchEvent({ type: 'click', preventDefault: () => {}, stopPropagation: () => {} });
        assert.strictEqual(trigger.getAttribute('aria-expanded'), 'true');
        assert.strictEqual(popover.hidden, false);

        const menuItem = popover.querySelector('.novel-comment-menu-item');
        assert.ok(menuItem, 'Menu item must exist');
        assert.strictEqual(menuItem.textContent, 'Xem đoạn gốc');
        assert.strictEqual(menuItem.getAttribute('data-block-key'), 'blk-test-1');
    });

    test('21. RELOCATED root renders navigation action with relocated blockKey', async () => {
        const { doc, list } = createStandardFixture('c2100');
        const items = [
            {
                rootCommentId: 'r-relocated',
                author: { userId: 'u2', displayName: 'User 2', avatarUrl: null },
                body: 'Relocated root comment',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                edited: false,
                anchorStatus: 'RELOCATED',
                blockKey: 'blk-test-2',
                replyCount: 0,
                replies: []
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        const trigger = list.querySelector('.novel-comment-menu-trigger');
        assert.ok(trigger, 'Menu trigger must exist on RELOCATED root');

        const menuItem = list.querySelector('.novel-comment-menu-item');
        assert.ok(menuItem);
        assert.strictEqual(menuItem.getAttribute('data-block-key'), 'blk-test-2');
    });

    test('22. STALE root has no active origin action or menu trigger', async () => {
        const { doc, list } = createStandardFixture('c2200');
        const items = [
            {
                rootCommentId: 'r-stale',
                author: { userId: 'u3', displayName: 'User 3', avatarUrl: null },
                body: 'Stale root comment',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                edited: false,
                anchorStatus: 'STALE',
                blockKey: null,
                replyCount: 0,
                replies: []
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        assert.strictEqual(list.querySelector('.novel-comment-menu-trigger'), null, 'STALE root must not render ⋯ trigger');
        assert.strictEqual(list.querySelector('.novel-comment-actions-menu'), null, 'STALE root must not render actions menu');
    });

    test('23. UNANCHORED root has no active origin action or menu trigger', async () => {
        const { doc, list } = createStandardFixture('c2300');
        const items = [
            {
                rootCommentId: 'r-unanchored',
                author: { userId: 'u4', displayName: 'User 4', avatarUrl: null },
                body: 'Unanchored root comment',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                edited: false,
                anchorStatus: 'UNANCHORED',
                blockKey: null,
                replyCount: 0,
                replies: []
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        assert.strictEqual(list.querySelector('.novel-comment-menu-trigger'), null, 'UNANCHORED root must not render ⋯ trigger');
        assert.strictEqual(list.querySelector('.novel-comment-actions-menu'), null, 'UNANCHORED root must not render actions menu');
    });

    test('24. Active reply inherits root CURRENT blockKey and renders origin menu', async () => {
        const { doc, list } = createStandardFixture('c2400');
        const items = [
            {
                rootCommentId: 'r-parent',
                author: { userId: 'u1', displayName: 'Root', avatarUrl: null },
                body: 'Root text',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                edited: false,
                anchorStatus: 'CURRENT',
                blockKey: 'blk-test-1',
                replyCount: 1,
                replies: [
                    {
                        id: 'rep-active',
                        parentCommentId: 'r-parent',
                        body: 'Active reply text',
                        tombstone: false,
                        createdAt: '2026-09-18T10:05:00Z',
                        updatedAt: '2026-09-18T10:05:00Z',
                        author: { userId: 'u2', displayName: 'Replier', avatarUrl: null }
                    }
                ]
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        const replyEl = list.querySelector('.novel-comment--reply');
        assert.ok(replyEl);

        const replyTrigger = replyEl.querySelector('.novel-comment-menu-trigger');
        assert.ok(replyTrigger, 'Active reply must render menu trigger inherited from root');

        const replyMenuItem = replyEl.querySelector('.novel-comment-menu-item');
        assert.ok(replyMenuItem);
        assert.strictEqual(replyMenuItem.getAttribute('data-block-key'), 'blk-test-1', 'Reply must inherit root blockKey');
    });

    test('25. Nested reply still inherits root blockKey, not immediate-parent identity', async () => {
        const { doc, list } = createStandardFixture('c2500');
        const items = [
            {
                rootCommentId: 'r-root',
                author: { userId: 'u1', displayName: 'Root', avatarUrl: null },
                body: 'Root text',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                edited: false,
                anchorStatus: 'CURRENT',
                blockKey: 'blk-test-1',
                replyCount: 2,
                replies: [
                    {
                        id: 'rep-1',
                        parentCommentId: 'r-root',
                        body: 'First reply',
                        tombstone: false,
                        createdAt: '2026-09-18T10:05:00Z',
                        updatedAt: '2026-09-18T10:05:00Z',
                        author: { userId: 'u2', displayName: 'User 2', avatarUrl: null }
                    },
                    {
                        id: 'rep-2',
                        parentCommentId: 'rep-1', // Nested reply
                        body: 'Nested reply',
                        tombstone: false,
                        createdAt: '2026-09-18T10:10:00Z',
                        updatedAt: '2026-09-18T10:10:00Z',
                        author: { userId: 'u3', displayName: 'User 3', avatarUrl: null }
                    }
                ]
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        const replies = list.querySelectorAll('.novel-comment--reply');
        assert.strictEqual(replies.length, 2);

        const nestedReply = replies[1];
        const nestedMenuItem = nestedReply.querySelector('.novel-comment-menu-item');
        assert.ok(nestedMenuItem);
        assert.strictEqual(
            nestedMenuItem.getAttribute('data-block-key'),
            'blk-test-1',
            'Nested reply must inherit root blockKey instead of deriving from parentCommentId'
        );
    });

    test('26. Tombstone reply has no actions menu', async () => {
        const { doc, list } = createStandardFixture('c2600');
        const items = [
            {
                rootCommentId: 'r-root-tomb',
                author: { userId: 'u1', displayName: 'Root', avatarUrl: null },
                body: 'Root text',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                edited: false,
                anchorStatus: 'CURRENT',
                blockKey: 'blk-test-1',
                replyCount: 1,
                replies: [
                    {
                        id: 'rep-deleted',
                        parentCommentId: 'r-root-tomb',
                        body: null,
                        tombstone: true,
                        status: 'DELETED',
                        createdAt: '2026-09-18T10:05:00Z',
                        updatedAt: '2026-09-18T10:10:00Z',
                        author: null
                    }
                ]
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        const tombstoneEl = list.querySelector('.novel-comment--reply.is-tombstone');
        assert.ok(tombstoneEl);
        assert.strictEqual(tombstoneEl.querySelector('.novel-comment-actions-menu'), null, 'Tombstone must not have actions menu');
        assert.strictEqual(tombstoneEl.querySelector('.novel-comment-menu-trigger'), null, 'Tombstone must not have menu trigger');
    });

    test('27. Click "Xem đoạn gốc" on root comment invokes openDiscussionTarget with { chapterId, blockKey, threadId: rootCommentId } without scrolling reader document', async () => {
        const { doc, list, block1 } = createStandardFixture('c2700');
        let bridgePayload = null;

        const items = [
            {
                rootCommentId: 'r-nav',
                author: { userId: 'u1', displayName: 'Root', avatarUrl: null },
                body: 'Navigate root',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                edited: false,
                anchorStatus: 'CURRENT',
                blockKey: 'blk-test-1',
                replyCount: 0,
                replies: []
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, {
            fetch: fakeFetch,
            openDiscussionTarget: (target) => {
                bridgePayload = target;
            }
        });
        await new Promise(r => setTimeout(r, 10));

        const trigger = list.querySelector('.novel-comment-menu-trigger');
        trigger.dispatchEvent({ type: 'click', preventDefault: () => {}, stopPropagation: () => {} });

        const originBtn = list.querySelector('.novel-comment-menu-item');
        originBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });

        // Menu should be closed
        assert.strictEqual(trigger.getAttribute('aria-expanded'), 'false');

        // Main reader document / paragraph scrollIntoView must NOT be invoked
        assert.strictEqual(block1.scrollIntoViewCalled, undefined, 'Must NOT scroll reader document or paragraph');
        assert.strictEqual(block1.classList.contains('is-origin-target'), false, 'Must NOT add .is-origin-target to paragraph');

        // Bridge must be called with chapterId, blockKey, and threadId (rootCommentId)
        assert.ok(bridgePayload, 'Must invoke injected openDiscussionTarget bridge');
        assert.strictEqual(bridgePayload.chapterId, 'c2700');
        assert.strictEqual(bridgePayload.blockKey, 'blk-test-1');
        assert.strictEqual(bridgePayload.threadId, 'r-nav');
    });

    test('28. Active reply "Xem đoạn gốc" targets ROOT discussion ID, never reply ID', async () => {
        const { doc, list, block1 } = createStandardFixture('c2800');
        let bridgePayload = null;

        const items = [
            {
                rootCommentId: 'r-parent-root',
                author: { userId: 'u1', displayName: 'Root', avatarUrl: null },
                body: 'Parent text',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                edited: false,
                anchorStatus: 'CURRENT',
                blockKey: 'blk-test-1',
                replyCount: 1,
                replies: [
                    {
                        id: 'rep-child-1',
                        parentCommentId: 'r-parent-root',
                        body: 'Active reply body',
                        tombstone: false,
                        createdAt: '2026-09-18T10:05:00Z',
                        updatedAt: '2026-09-18T10:05:00Z',
                        author: { userId: 'u2', displayName: 'Replier', avatarUrl: null }
                    }
                ]
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, {
            fetch: fakeFetch,
            openDiscussionTarget: (target) => {
                bridgePayload = target;
            }
        });
        await new Promise(r => setTimeout(r, 10));

        const replyEl = list.querySelector('.novel-comment--reply');
        assert.ok(replyEl);

        const trigger = replyEl.querySelector('.novel-comment-menu-trigger');
        assert.ok(trigger);
        trigger.dispatchEvent({ type: 'click', preventDefault: () => {}, stopPropagation: () => {} });

        const originBtn = replyEl.querySelector('.novel-comment-menu-item');
        assert.ok(originBtn);
        assert.strictEqual(originBtn.getAttribute('data-root-id'), 'r-parent-root', 'Reply menu item must reference rootCommentId');

        originBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });

        // Menu closed
        assert.strictEqual(trigger.getAttribute('aria-expanded'), 'false');

        // Reader document scroll untouched
        assert.strictEqual(block1.scrollIntoViewCalled, undefined, 'Must NOT scroll reader document or paragraph');

        // Bridge payload targets root discussion ID, NEVER child reply ID
        assert.ok(bridgePayload, 'Must invoke openDiscussionTarget');
        assert.strictEqual(bridgePayload.blockKey, 'blk-test-1');
        assert.strictEqual(bridgePayload.threadId, 'r-parent-root', 'Must use rootCommentId, NEVER reply.id');
    });

    test('29. Nested reply still targets root discussion ID and does not alter URL or scroll reader document', async () => {
        const { doc, list, block1 } = createStandardFixture('c2900');
        let bridgePayload = null;

        const items = [
            {
                rootCommentId: 'r-deep-root',
                author: { userId: 'u1', displayName: 'Root', avatarUrl: null },
                body: 'Root text',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                edited: false,
                anchorStatus: 'CURRENT',
                blockKey: 'blk-test-1',
                replyCount: 2,
                replies: [
                    {
                        id: 'rep-first-level',
                        parentCommentId: 'r-deep-root',
                        body: 'Level 1',
                        tombstone: false,
                        createdAt: '2026-09-18T10:05:00Z',
                        updatedAt: '2026-09-18T10:05:00Z',
                        author: { userId: 'u2', displayName: 'U2', avatarUrl: null }
                    },
                    {
                        id: 'rep-nested-level',
                        parentCommentId: 'rep-first-level',
                        body: 'Level 2 nested',
                        tombstone: false,
                        createdAt: '2026-09-18T10:10:00Z',
                        updatedAt: '2026-09-18T10:10:00Z',
                        author: { userId: 'u3', displayName: 'U3', avatarUrl: null }
                    }
                ]
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, {
            fetch: fakeFetch,
            openDiscussionTarget: (target) => {
                bridgePayload = target;
            }
        });
        await new Promise(r => setTimeout(r, 10));

        const replies = list.querySelectorAll('.novel-comment--reply');
        assert.strictEqual(replies.length, 2);

        const nestedReply = replies[1];
        const trigger = nestedReply.querySelector('.novel-comment-menu-trigger');
        trigger.dispatchEvent({ type: 'click', preventDefault: () => {}, stopPropagation: () => {} });

        const originBtn = nestedReply.querySelector('.novel-comment-menu-item');
        originBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });

        // Reader document scroll untouched
        assert.strictEqual(block1.scrollIntoViewCalled, undefined);

        // Targets root discussion ID, NOT nested reply ID or intermediate parent ID
        assert.ok(bridgePayload);
        assert.strictEqual(bridgePayload.threadId, 'r-deep-root');
        assert.strictEqual(bridgePayload.blockKey, 'blk-test-1');
    });

    test('30. When restore bridge is unavailable, clicking "Xem đoạn gốc" safely no-ops without throwing or scrolling', async () => {
        const { doc, list, block1, block2 } = createStandardFixture('c3000');

        const items = [
            {
                rootCommentId: 'r-unavailable-bridge',
                author: { userId: 'u1', displayName: 'Root', avatarUrl: null },
                body: 'Target',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                edited: false,
                anchorStatus: 'CURRENT',
                blockKey: 'blk-test-1',
                replyCount: 0,
                replies: []
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        // Init WITHOUT openDiscussionTarget and WITHOUT global NovelReaderBlockDiscussionRestore
        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        const trigger = list.querySelector('.novel-comment-menu-trigger');
        trigger.dispatchEvent({ type: 'click', preventDefault: () => {}, stopPropagation: () => {} });

        const originBtn = list.querySelector('.novel-comment-menu-item');
        assert.doesNotThrow(() => {
            originBtn.dispatchEvent({ type: 'click', preventDefault: () => {} });
        });

        assert.strictEqual(trigger.getAttribute('aria-expanded'), 'false');
        assert.strictEqual(block1.scrollIntoViewCalled, undefined);
        assert.strictEqual(block2.scrollIntoViewCalled, undefined);
    });

    test('31. Escape closes menu and restores focus to trigger', async () => {
        const { doc, list } = createStandardFixture('c3100');
        const items = [
            {
                rootCommentId: 'r-esc',
                author: { userId: 'u1', displayName: 'Root', avatarUrl: null },
                body: 'Root',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                edited: false,
                anchorStatus: 'CURRENT',
                blockKey: 'blk-test-1',
                replyCount: 0,
                replies: []
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        const trigger = list.querySelector('.novel-comment-menu-trigger');
        trigger.dispatchEvent({ type: 'click', preventDefault: () => {}, stopPropagation: () => {} });

        assert.strictEqual(trigger.getAttribute('aria-expanded'), 'true');
        assert.ok(commentsModule.getActiveOpenMenu());

        // Press Escape
        doc.dispatchEvent({ type: 'keydown', key: 'Escape', keyCode: 27, preventDefault: () => {} });

        assert.strictEqual(trigger.getAttribute('aria-expanded'), 'false');
        assert.strictEqual(commentsModule.getActiveOpenMenu(), null);
        assert.strictEqual(trigger.isFocused, true, 'Escape must restore focus to trigger button');
    });

    test('32. Outside click closes active menu', async () => {
        const { doc, list } = createStandardFixture('c3200');
        const items = [
            {
                rootCommentId: 'r-click-out',
                author: { userId: 'u1', displayName: 'Root', avatarUrl: null },
                body: 'Root',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                edited: false,
                anchorStatus: 'CURRENT',
                blockKey: 'blk-test-1',
                replyCount: 0,
                replies: []
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        const trigger = list.querySelector('.novel-comment-menu-trigger');
        trigger.dispatchEvent({ type: 'click', preventDefault: () => {}, stopPropagation: () => {} });

        assert.strictEqual(trigger.getAttribute('aria-expanded'), 'true');

        // Click outside on body
        doc.dispatchEvent({ type: 'click', target: doc.body });

        assert.strictEqual(trigger.getAttribute('aria-expanded'), 'false');
        assert.strictEqual(commentsModule.getActiveOpenMenu(), null);
    });

    test('33. Opening menu B closes menu A', async () => {
        const { doc, list } = createStandardFixture('c3300');
        const items = [
            {
                rootCommentId: 'r-a',
                author: { userId: 'u1', displayName: 'A', avatarUrl: null },
                body: 'Comment A',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                edited: false,
                anchorStatus: 'CURRENT',
                blockKey: 'blk-test-1',
                replyCount: 0,
                replies: []
            },
            {
                rootCommentId: 'r-b',
                author: { userId: 'u2', displayName: 'B', avatarUrl: null },
                body: 'Comment B',
                createdAt: '2026-09-18T09:00:00Z',
                updatedAt: '2026-09-18T09:00:00Z',
                edited: false,
                anchorStatus: 'CURRENT',
                blockKey: 'blk-test-2',
                replyCount: 0,
                replies: []
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        const triggers = list.querySelectorAll('.novel-comment-menu-trigger');
        assert.strictEqual(triggers.length, 2);

        const triggerA = triggers[0];
        const triggerB = triggers[1];

        // Open menu A
        triggerA.dispatchEvent({ type: 'click', preventDefault: () => {}, stopPropagation: () => {} });
        assert.strictEqual(triggerA.getAttribute('aria-expanded'), 'true');
        assert.strictEqual(triggerB.getAttribute('aria-expanded'), 'false');

        // Open menu B -> menu A must close
        triggerB.dispatchEvent({ type: 'click', preventDefault: () => {}, stopPropagation: () => {} });
        assert.strictEqual(triggerA.getAttribute('aria-expanded'), 'false');
        assert.strictEqual(triggerB.getAttribute('aria-expanded'), 'true');
        assert.strictEqual(commentsModule.getActiveOpenMenu().triggerEl, triggerB);
    });

    test('34. Chapter change closes open menu and cleans up pending state', async () => {
        const { doc, list, block1 } = createStandardFixture('c3400-orig');
        const items = [
            {
                rootCommentId: 'r-orig',
                author: { userId: 'u1', displayName: 'Orig', avatarUrl: null },
                body: 'Orig',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                edited: false,
                anchorStatus: 'CURRENT',
                blockKey: 'blk-test-1',
                replyCount: 0,
                replies: []
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        const trigger = list.querySelector('.novel-comment-menu-trigger');
        trigger.dispatchEvent({ type: 'click', preventDefault: () => {}, stopPropagation: () => {} });
        assert.strictEqual(trigger.getAttribute('aria-expanded'), 'true');
        assert.ok(commentsModule.getActiveOpenMenu());

        // Transition chapter
        doc.dispatchEvent({
            type: 'kiemlai:chapter-changed',
            detail: { chapterId: 'c3400-next' }
        });

        assert.strictEqual(trigger.getAttribute('aria-expanded'), 'false');
        assert.strictEqual(commentsModule.getActiveOpenMenu(), null);
    });

    test('35. No passageExcerpt rendered anywhere in card or menu', async () => {
        const { doc, list } = createStandardFixture('c3500');
        const items = [
            {
                rootCommentId: 'r-excerpt-test',
                author: { userId: 'u1', displayName: 'Author', avatarUrl: null },
                body: 'Normal comment body',
                createdAt: '2026-09-18T10:00:00Z',
                updatedAt: '2026-09-18T10:00:00Z',
                edited: false,
                anchorStatus: 'CURRENT',
                blockKey: 'blk-test-1',
                passageExcerpt: 'Đoạn trích này tuyệt đối không được render',
                replyCount: 0,
                replies: []
            }
        ];

        const fakeFetch = () => Promise.resolve({
            ok: true,
            json: () => Promise.resolve(makeFeedResponse(items))
        });

        commentsModule.init(doc, { fetch: fakeFetch });
        await new Promise(r => setTimeout(r, 10));

        const trigger = list.querySelector('.novel-comment-menu-trigger');
        trigger.dispatchEvent({ type: 'click', preventDefault: () => {}, stopPropagation: () => {} });

        const fullText = list.textContent;
        assert.strictEqual(fullText.includes('Đoạn trích này tuyệt đối không được render'), false);
    });
});
